package com.example.mimic.party

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.mimic.data.Song
import com.example.mimic.data.SongSource
import com.example.mimic.data.lyrics.LyricsQuery
import com.example.mimic.data.online.OnlineIds
import com.example.mimic.data.online.OnlineSearchFilter
import com.example.mimic.data.online.OnlineTrack
import com.example.mimic.data.radio.RadioIds
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * The songs this device can play, for the party resolver (MImic 4.5). The UI publishes its
 * library ([publish]); songs resolved for a party are added ([remember]) so the player UI can
 * show them even when they aren't in the library (an online song a friend queued).
 */
object PartyLibrary {
    @Volatile private var songs: List<Song> = emptyList()
    @Volatile private var byId: Map<Long, Song> = emptyMap()
    private val extra = ConcurrentHashMap<Long, Song>()

    fun publish(list: List<Song>) {
        songs = list
        byId = list.associateBy { it.id }
    }

    fun remember(song: Song) { extra[song.id] = song }
    fun song(id: Long): Song? = byId[id] ?: extra[id]
    fun all(): List<Song> = songs
}

/**
 * Party track identity and resolution (MImic 4.5).
 *
 * Each device plays a party song from a source *it* is authorized to use — never audio relayed
 * from another member:
 *  1. an online track (YouTube id): this device's own download of it, else its own online stream
 *     (full edition);
 *  2. anything else (phone file, Navidrome, a download): the same recording in this device's
 *     library — cleaned title equal, artist credited, length within [DURATION_TOLERANCE_MS] —
 *     preferring an offline copy; else (full edition) an online match found by search.
 * When nothing matches, the song is "not available on this device": the device stays quiet for
 * that song and rejoins on the next one.
 */
object PartyTracks {
    private const val DURATION_TOLERANCE_MS = 4_000L

    fun isMetaKey(key: String) = key.startsWith("m:")

    /** "m:" + hash of the cleaned title and artist (length is matched separately). */
    fun metaKey(title: String, artist: String): String {
        val q = LyricsQuery.of(title, artist)
        val d = MessageDigest.getInstance("SHA-256").digest((LyricsQuery.compact(q.title) + "|" + LyricsQuery.compact(q.artist)).toByteArray())
        return "m:" + d.take(10).joinToString("") { "%02x".format(it) }
    }

    /** The party's view of [song]. Live radio isn't a party track. */
    fun fromSong(song: Song, playerDurationMs: Long = 0L): PartyTrack? {
        if (RadioIds.isRadio(song.id)) return null
        val duration = playerDurationMs.takeIf { it > 0 } ?: song.durationMs.takeIf { it > 0 }
        val videoId = OnlineIds.videoIdOf(song.remoteId)
        return if (videoId != null) {
            // Only a public https artwork URL is shared — never a Navidrome cover (it carries a login token).
            PartyTrack(videoId, song.title, song.artist, song.albumArtUri?.takeIf { it.startsWith("https://") }, duration)
        } else {
            PartyTrack(metaKey(song.title, song.artist), song.title, song.artist, null, duration)
        }
    }

    /** Pure: does [song] look like the same recording as [t]? */
    fun sameRecording(t: PartyTrack, song: Song): Boolean {
        val a = LyricsQuery.of(t.title, t.artist)
        val b = LyricsQuery.of(song.title, song.artist)
        if (LyricsQuery.compact(a.title).isEmpty() || LyricsQuery.compact(a.title) != LyricsQuery.compact(b.title)) return false
        if (a.versions != b.versions) return false
        if (a.artistKnown && b.artistKnown) {
            val ours = b.artists.map { it.replace(" ", "") }
            val credited = a.artists.map { it.replace(" ", "") }.any { x -> ours.any { y -> x == y || (x.length >= 4 && y.length >= 4 && (x.contains(y) || y.contains(x))) } }
            if (!credited) return false
        }
        val d = t.durationMs ?: 0L
        if (d > 0 && song.durationMs > 0 && abs(d - song.durationMs) > DURATION_TOLERANCE_MS) return false
        return a.artistKnown || (d > 0 && song.durationMs > 0)
    }

    /** Best library match: offline copies first, then Navidrome, then saved online tracks. */
    fun matchInLibrary(t: PartyTrack, library: List<Song>): Song? {
        if (!isMetaKey(t.videoId)) {
            library.firstOrNull { OnlineIds.videoIdOf(it.remoteId) == t.videoId && it.isOffline }?.let { return it }
        }
        return library.filter { !RadioIds.isRadio(it.id) && !it.fileMissing && sameRecording(t, it) }
            .minByOrNull {
                when {
                    it.source == SongSource.LOCAL -> 0
                    it.source == SongSource.DOWNLOADED -> 1
                    it.source == SongSource.SERVER -> 2
                    else -> 3
                }
            }
    }

    /** Resolve [t] to something this device may play, or null ("not available on this device"). */
    suspend fun resolve(t: PartyTrack): Song? {
        val library = PartyLibrary.all()
        matchInLibrary(t, library)?.let { return it }
        if (!isMetaKey(t.videoId)) {
            if (com.example.mimic.online.OnlineMusic.AVAILABLE) {
                return OnlineTrack(t.videoId, t.title, t.artist, null, durationMs = t.durationMs ?: 0L, artworkUrl = t.thumbnailUrl).toSong()
            }
            return null
        }
        if (!com.example.mimic.online.OnlineMusic.AVAILABLE) return null
        val q = LyricsQuery.of(t.title, t.artist)
        val text = listOf(q.artist, q.title).filter { it.isNotBlank() }.joinToString(" ")
        // A failed search is an error (retried), not "no such song here".
        val found = com.example.mimic.online.OnlineMusic.search(text, OnlineSearchFilter.SONGS).getOrThrow()
        return found.tracks.map { it.toSong() }.firstOrNull { sameRecording(t, it) }
    }

    /** The same MediaItem MImic's player builds for a song (id, URI, metadata). */
    fun mediaItem(song: Song): MediaItem = MediaItem.Builder()
        .setMediaId(song.id.toString())
        .setUri(song.uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setTitle(song.title).setArtist(song.artist).setAlbumTitle(song.album)
                .setArtworkUri(song.albumArtUri?.let(android.net.Uri::parse)).build(),
        )
        .build()
}
