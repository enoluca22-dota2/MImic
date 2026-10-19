package com.example.mimic.data.backup

import android.content.Context
import android.net.Uri
import com.example.mimic.data.SettingsRepository
import com.example.mimic.data.Song
import com.example.mimic.data.db.MimicDatabase
import com.example.mimic.data.db.PlayHistoryEntity
import com.example.mimic.data.db.PlaylistEntity
import com.example.mimic.data.db.PlaylistSongEntity
import com.example.mimic.data.db.FavoriteEntity
import com.example.mimic.data.radio.RadioCountryStore
import com.example.mimic.data.server.ServerCredentialsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Backup & Restore (MImic 4.3) to a file the user picks with Android's file picker — on the phone,
 * an SD card, or any cloud app that offers a folder there. See [BackupData] for what's inside.
 * Restoring only ADDS: it never deletes music, downloads, favorites, playlists or history.
 */
class BackupManager(
    private val context: Context,
    private val db: MimicDatabase,
    private val settings: SettingsRepository,
    private val radioCountries: RadioCountryStore,
    private val server: ServerCredentialsRepository,
    private val appVersion: String,
) {
    data class ExportResult(val summary: BackupData.Summary, val skippedMissingSongs: Int)

    data class RestoreOptions(val music: Boolean = true, val settings: Boolean = true, val radio: Boolean = true)

    data class RestoreResult(
        val favoritesAdded: Int,
        val playlistsCreated: Int,
        val playlistSongsAdded: Int,
        val historyAdded: Int,
        val themesAdded: Int,
        val settingsRestored: Int,
        val radioStations: Int,
        val radioCountriesAdded: Int,
        val songsNotFound: Int,
        val serverAddressFilled: Boolean,
    )

    /** Builds the backup. [library] = every song MImic knows (incl. hidden folders), for song details. */
    suspend fun build(library: List<Song>, now: Long = System.currentTimeMillis()): Pair<BackupData, Int> = withContext(Dispatchers.IO) {
        val byId = library.associateBy { it.id }
        val favorites = db.favoriteDao().allOnce()
        val history = db.historyDao().allOnce()
        val playlistDao = db.playlistDao()
        val playlists = playlistDao.playlists().first().map { it to playlistDao.songIdsIn(it.id).first() }

        val refs = LinkedHashMap<Long, Int>()
        var skipped = 0
        fun ref(id: Long): Int? {
            refs[id]?.let { return it }
            // A Navidrome song's id is the same on every phone, so it is kept even while the server
            // is offline (written without details); a missing phone song can't be identified later.
            if (byId[id] == null && !SongMatcher.isServerId(id)) return null
            return refs.size.also { refs[id] = it }
        }
        val favs = favorites.mapNotNull { f -> ref(f.songId)?.let { it to f.addedAt } ?: run { skipped++; null } }
        val pls = playlists.map { (p, ids) -> BackupPlaylist(p.name, p.createdAt, ids.mapNotNull { id -> ref(id) ?: run { skipped++; null } }) }
        // History of songs no longer on the phone isn't shown anywhere — it is left out quietly.
        val hist = history.mapNotNull { h -> ref(h.songId)?.let { it to h.playedAt } }

        val data = BackupData(
            createdAt = now,
            appVersion = appVersion,
            songs = refs.map { (id, r) -> byId[id]?.let { SongMatcher.toBackupSong(r, it) } ?: SongMatcher.serverOnly(r, id) },
            favorites = favs,
            favoriteGroups = db.favoriteGroupDao().all().first(),
            playlists = pls,
            history = hist,
            themes = db.customThemeDao().allOnce(),
            settings = settings.backupPrefs(),
            radioCountries = radioCountries.current(),
            radioStations = db.radioDao().saved(),
            serverUrl = server.lastServerUrl.first()?.takeIf { it.isNotBlank() },
            serverUser = server.savedUser.first()?.takeIf { it.isNotBlank() },
        )
        data to skipped
    }

    suspend fun export(library: List<Song>, target: Uri): ExportResult = withContext(Dispatchers.IO) {
        val (data, skipped) = build(library)
        val bytes = BackupCodec.encode(data).toByteArray(Charsets.UTF_8)
        (context.contentResolver.openOutputStream(target, "wt") ?: throw java.io.IOException("Can't write to that file"))
            .use { it.write(bytes) }
        ExportResult(data.summary(), skipped)
    }

    suspend fun read(source: Uri): BackupData = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(source) ?: throw BackupFormatException("Can't open that file.")
        val text = input.use { stream ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                if (total > BackupCodec.MAX_BYTES) throw BackupFormatException("This file is too large to be a MImic backup.")
                buf.write(chunk, 0, n)
            }
            buf.toString(Charsets.UTF_8.name())
        }
        BackupCodec.decode(text)
    }

    suspend fun restore(b: BackupData, library: List<Song>, options: RestoreOptions): RestoreResult = withContext(Dispatchers.IO) {
        var favoritesAdded = 0; var playlistsCreated = 0; var playlistSongsAdded = 0; var historyAdded = 0
        var themesAdded = 0; var settingsRestored = 0; var radioStations = 0; var countriesAdded = 0
        var notFound = 0; var serverFilled = false

        if (options.music) {
            val resolved = SongMatcher.resolve(b.songs, library)
            val playlistDao = db.playlistDao()
            val existingPlaylists = playlistDao.playlists().first().associate { it.name.trim() to (it.id to playlistDao.songIdsIn(it.id).first()) }
            val plan = RestorePlanner.plan(
                b, resolved,
                existingFavorites = db.favoriteDao().allOnce().map { it.songId }.toSet(),
                existingPlaylists = existingPlaylists,
                existingHistory = db.historyDao().allOnce().map { it.songId to it.playedAt }.toSet(),
            )
            db.favoriteDao().addAll(plan.favoritesToAdd.map { (id, at) -> FavoriteEntity(id, at) })
            favoritesAdded = plan.favoritesToAdd.size
            for (p in plan.playlists) {
                val id = p.existingId ?: playlistDao.create(PlaylistEntity(name = p.name, createdAt = p.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis())).also { playlistsCreated++ }
                var pos = playlistDao.nextPosition(id)
                for (songId in p.songIdsToAdd) playlistDao.insertMember(PlaylistSongEntity(id, songId, pos++))
                playlistSongsAdded += p.songIdsToAdd.size
            }
            db.historyDao().insertAll(plan.historyToAdd.map { (id, at) -> PlayHistoryEntity(songId = id, playedAt = at) })
            historyAdded = plan.historyToAdd.size
            notFound = plan.unmatchedSongs
            // Album/artist favorites only where that album/artist exists in this library.
            val keys = library.flatMap { listOf(it.albumKey, it.artistKey) }.toSet()
            db.favoriteGroupDao().addAllIfMissing(b.favoriteGroups.filter { it.groupKey in keys })
        }
        if (options.settings) {
            val themeDao = db.customThemeDao()
            val existing = themeDao.allOnce().map { it.id }.toSet()
            b.themes.filter { it.id !in existing }.forEach { themeDao.upsert(it); themesAdded++ }
            settings.restorePrefs(b.settings)
            settingsRestored = b.settings.size
            b.serverUrl?.let { url ->
                val before = server.lastServerUrl.first()
                server.prefillServerIfEmpty(url)
                serverFilled = before.isNullOrBlank()
            }
        }
        if (options.radio) {
            val before = radioCountries.current()
            radioCountries.mergeIn(b.radioCountries)
            countriesAdded = radioCountries.current().size - before.size
            val dao = db.radioDao()
            val now = System.currentTimeMillis()
            for (r in b.radioStations) {
                val cur = dao.get(r.id)
                val merged = if (cur == null) r.copy(updatedAt = now) else cur.copy(
                    favorite = cur.favorite || r.favorite,
                    favoritedAt = if (cur.favorite) cur.favoritedAt else r.favoritedAt,
                    lastPlayedAt = listOfNotNull(cur.lastPlayedAt, r.lastPlayedAt).maxOrNull(),
                    playCount = maxOf(cur.playCount, r.playCount),
                    updatedAt = now,
                )
                if (cur == null || merged.copy(updatedAt = 0) != cur.copy(updatedAt = 0)) { dao.upsert(merged); radioStations++ }
            }
        }
        RestoreResult(favoritesAdded, playlistsCreated, playlistSongsAdded, historyAdded, themesAdded, settingsRestored,
            radioStations, countriesAdded, notFound, serverFilled)
    }
}
