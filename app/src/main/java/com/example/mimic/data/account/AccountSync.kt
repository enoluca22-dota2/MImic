package com.example.mimic.data.account

import android.content.Context
import com.example.mimic.data.Song
import com.example.mimic.data.backup.BackupCodec
import com.example.mimic.data.backup.BackupData
import com.example.mimic.data.backup.BackupManager
import com.example.mimic.data.online.OnlineIds
import com.example.mimic.data.online.OnlineTrack
import com.example.mimic.data.online.OnlineTrackStore
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Account sync (4.4): playlists, favorites, play history, radio countries and themes follow the
 * MImic account between phones.
 *
 * How it works — deliberately built on the 4.3 backup format, which is already sanitised (no
 * server password, no cookies or tokens, no audio) and already knows how to match songs across
 * phones (Navidrome id, YouTube id, or unique title + artist + length):
 *  - push: this phone's snapshot goes to `users/{uid}/devices/{deviceId}` (one document per phone,
 *    so phones never overwrite each other's data);
 *  - pull: every other phone's snapshot is merged in ADDITIVELY with the restore planner — new
 *    favorites / playlist songs / history are added, nothing local is ever deleted, and running it
 *    twice adds nothing twice (idempotent);
 *  - online (YouTube) songs from another phone are remembered here with their metadata so they
 *    appear in playlists and favorites and can be downloaded again; downloads are never uploaded.
 *  - settings (theme, player layout…) are only applied when the user taps "Restore settings".
 *
 * Firestore security rules (docs/firebase/firestore.rules) allow each signed-in user to read and
 * write only `users/{their uid}/…`.
 *
 * Known limit: deleting a playlist / unfavoriting on one phone does not remove it on the others
 * (additive merge never deletes). That is the safe direction; delete-propagation is future work.
 */
class AccountSync(
    private val context: Context,
    private val account: MimicAccount,
    private val backup: BackupManager,
    private val online: OnlineTrackStore,
) {
    sealed interface Status {
        data object Off : Status
        data object Idle : Status
        data object Syncing : Status
        data class Done(val at: Long, val added: String) : Status
        data class Failed(val message: String) : Status
    }

    private val prefs = context.getSharedPreferences("account_sync", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow<Status>(if (enabled) Status.Idle else Status.Off)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val lock = Mutex()

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) {
            prefs.edit().putBoolean("enabled", v).apply()
            _status.value = if (v) Status.Idle else Status.Off
        }

    /** Random per-install id; never derived from hardware identifiers. */
    private val deviceId: String
        get() = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }

    private fun db(): FirebaseFirestore? = account.firebaseApp()?.let { FirebaseFirestore.getInstance(it) }

    /** Push this phone's snapshot, then merge every other phone's. [library] = everything MImic knows. */
    suspend fun syncNow(library: List<Song>): Status = lock.withLock {
        if (!enabled) return Status.Off.also { _status.value = it }
        val uid = account.uid ?: return Status.Failed("Sign in to sync.").also { _status.value = it }
        val db = db() ?: return Status.Failed("Sync isn't configured.").also { _status.value = it }
        _status.value = Status.Syncing
        val result = runCatching {
            val userDoc = db.collection("users").document(uid)
            val (snapshot, _) = backup.build(library)
            val payload = BackupCodec.encode(snapshot.trimmedForCloud())
            userDoc.collection("devices").document(deviceId).set(
                mapOf(
                    "format" to 1, "updatedAt" to System.currentTimeMillis(), "deviceName" to android.os.Build.MODEL,
                    "snapshot" to payload,
                ),
                SetOptions.merge(),
            ).await()
            val others = userDoc.collection("devices").get().await().documents.filter { it.id != deviceId }
            var fav = 0; var pl = 0; var plSongs = 0; var missing = 0
            for (doc in others) {
                val text = doc.getString("snapshot") ?: continue
                val data = runCatching { BackupCodec.decode(text) }.getOrNull() ?: continue
                // Make other phones' YouTube songs known here, so they can be matched and shown.
                val ytTracks = data.songs.mapNotNull { s ->
                    OnlineIds.videoIdOf(s.remoteId)?.let { id ->
                        OnlineTrack(id, s.title, s.artist, s.album.takeIf { it.isNotBlank() }, durationMs = s.durationMs, artworkUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg")
                    }
                }
                if (ytTracks.isNotEmpty()) online.remember(ytTracks, inLibrary = true)
                val withOnline = library + ytTracks.map { it.toSong() }.filter { s -> library.none { it.id == s.id } }
                val r = backup.restore(data, withOnline, BackupManager.RestoreOptions(music = true, settings = false, radio = true))
                fav += r.favoritesAdded; pl += r.playlistsCreated; plSongs += r.playlistSongsAdded; missing += r.songsNotFound
            }
            prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
            Status.Done(
                System.currentTimeMillis(),
                buildList {
                    if (fav > 0) add("$fav favorites")
                    if (pl > 0) add("$pl playlists")
                    if (plSongs > 0) add("$plSongs playlist songs")
                    if (missing > 0) add("$missing songs not on this phone")
                }.joinToString(", ").ifEmpty { "Everything was already up to date" },
            )
        }.getOrElse { Status.Failed(friendly(it)) }
        _status.value = result
        result
    }

    /** Explicit, user-confirmed: apply settings (theme, layouts…) from the newest other phone. */
    suspend fun restoreSettings(library: List<Song>): Boolean = lock.withLock {
        val uid = account.uid ?: return false
        val db = db() ?: return false
        val newest = db.collection("users").document(uid).collection("devices").get().await().documents
            .filter { it.id != deviceId }.maxByOrNull { it.getLong("updatedAt") ?: 0L } ?: return false
        val data = runCatching { BackupCodec.decode(newest.getString("snapshot") ?: return false) }.getOrNull() ?: return false
        backup.restore(data, library, BackupManager.RestoreOptions(music = false, settings = true, radio = false))
        true
    }

    /** Explicit, user-confirmed: delete this account's synced copies from the cloud (local data stays). */
    suspend fun deleteCloudData(): Boolean {
        val uid = account.uid ?: return false
        val db = db() ?: return false
        val devices = db.collection("users").document(uid).collection("devices").get().await().documents
        for (d in devices) d.reference.delete().await()
        return true
    }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            "PERMISSION_DENIED" in m -> "The cloud refused access. Check the Firestore rules (docs/firebase/firestore.rules)."
            "UNAVAILABLE" in m || "offline" in m.lowercase() -> "Offline — your changes are kept and will sync next time."
            else -> "Sync failed: ${m.take(160)}"
        }
    }

    companion object {
        /** Firestore documents are limited to 1 MiB; keep the newest history when a library is huge. */
        internal fun BackupData.trimmedForCloud(): BackupData =
            if (history.size <= 5000) copy(serverUser = null, serverUrl = null) else copy(history = history.sortedByDescending { it.second }.take(5000), serverUser = null, serverUrl = null)
    }
}
