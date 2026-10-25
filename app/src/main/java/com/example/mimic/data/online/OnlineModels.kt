package com.example.mimic.data.online

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.mimic.data.MetadataFallback
import com.example.mimic.data.Song
import com.example.mimic.data.SongSource
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

/**
 * Online music (MImic 4.4, full edition): YouTube Music tracks played through MImic's own player.
 *
 * Identity: remote id `yt:<videoId>`, song id = 62 bits of SHA-256 in [2^62, 2^63) — a range no
 * other source uses (phone ids are MediaStore ids, Navidrome ids sit just above 2^32, legacy cloud
 * downloads in [2^61, 2^62), radio is negative). So a YouTube track is never mistaken for a
 * Navidrome or phone track that merely shares its title.
 *
 * Playback URI `mimic-yt://track?v=<videoId>` is resolved to a fresh stream only when the player
 * opens it (see the full edition's OnlineMusic), so a queued URL can never be stale.
 */
object OnlineIds {
    const val PREFIX = "yt:"
    const val SCHEME = "mimic-yt"

    fun isOnline(remoteId: String?): Boolean = remoteId != null && remoteId.startsWith(PREFIX) && remoteId.length > PREFIX.length

    fun remoteId(videoId: String): String = PREFIX + videoId

    fun videoIdOf(remoteId: String?): String? = remoteId?.takeIf(::isOnline)?.removePrefix(PREFIX)

    fun songId(videoId: String): Long {
        val d = MessageDigest.getInstance("SHA-256").digest(remoteId(videoId).toByteArray())
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return (v and 0x3FFF_FFFF_FFFF_FFFFL) or 0x4000_0000_0000_0000L
    }

    fun isOnlineSongId(id: Long): Boolean = id >= 0x4000_0000_0000_0000L

    fun playUri(videoId: String): String = "$SCHEME://track?v=$videoId"

    /** The videoId inside a [playUri], or null for any other URI. */
    fun videoIdOfUri(uri: String?): String? =
        uri?.takeIf { it.startsWith("$SCHEME://") }?.substringAfter("v=", "")?.substringBefore('&')?.takeIf { it.isNotEmpty() }

    fun albumKey(albumId: String?, album: String): String =
        if (!albumId.isNullOrBlank()) "yt:album:$albumId" else "yt:album:" + album.trim().lowercase()
}

/** One online track, as search results and collection pages return it. */
data class OnlineTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val durationMs: Long = 0L,
    /** The track's own artwork URL (never a shared placeholder). */
    val artworkUrl: String? = null,
    val isVideo: Boolean = false,
    val trackNo: Int = 0,
) {
    fun toSong(): Song = Song(
        id = OnlineIds.songId(videoId),
        title = MetadataFallback.title(title, null),
        artist = MetadataFallback.artist(artist),
        album = MetadataFallback.album(album),
        durationMs = durationMs,
        uri = OnlineIds.playUri(videoId),
        albumArtUri = artworkUrl,
        dateAddedSec = 0L,
        folder = "YouTube Music",
        source = SongSource.ONLINE,
        remoteId = OnlineIds.remoteId(videoId),
        albumKey = OnlineIds.albumKey(albumId, MetadataFallback.album(album)),
        trackNo = trackNo,
    )
}

enum class OnlineCollectionKind { ALBUM, ARTIST, PLAYLIST }

/** An album, artist or playlist in search results. */
data class OnlineCollection(
    val browseId: String,
    val kind: OnlineCollectionKind,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
)

data class OnlineSearchResults(
    val tracks: List<OnlineTrack>,
    val collections: List<OnlineCollection>,
)

/** What an album / playlist / artist page shows. */
data class OnlineCollectionPage(
    val browseId: String,
    val kind: OnlineCollectionKind,
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
    val tracks: List<OnlineTrack>,
    /** Artist pages: their albums / singles. */
    val related: List<OnlineCollection> = emptyList(),
)

/** Search filters MImic offers (mapped onto YouTube Music's own filter tabs). */
enum class OnlineSearchFilter(val label: String) { ALL("All"), SONGS("Songs"), ALBUMS("Albums"), ARTISTS("Artists"), PLAYLISTS("Playlists") }

/** Where an online download stands (WorkManager-backed, so it survives the app being closed). */
sealed interface OnlineDownloadState {
    data object Queued : OnlineDownloadState
    /** [fraction] is 0..1, or -1 while the size is unknown. */
    data class Running(val fraction: Float) : OnlineDownloadState
    data class Failed(val message: String) : OnlineDownloadState
}

/**
 * Online tracks MImic remembers (Room v8): every track the user played, favorited, added to a
 * playlist or downloaded, so favorites, history, playlists and the restored queue still have the
 * track's own title / artist / artwork after a restart. [inLibrary] tracks also show in Library.
 * Nothing here is a credential; only public metadata.
 */
@Entity(tableName = "online_tracks")
data class OnlineTrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val albumId: String?,
    val artistId: String?,
    val durationMs: Long,
    val artworkUrl: String?,
    val isVideo: Boolean,
    val inLibrary: Boolean,
    val addedAt: Long,
    val lastSeenAt: Long,
) {
    fun toTrack() = OnlineTrack(videoId, title, artist, album, albumId, artistId, durationMs, artworkUrl, isVideo)

    companion object {
        fun of(t: OnlineTrack, inLibrary: Boolean, now: Long = System.currentTimeMillis()) = OnlineTrackEntity(
            t.videoId, t.title, t.artist, t.album, t.albumId, t.artistId, t.durationMs, t.artworkUrl, t.isVideo,
            inLibrary, now, now,
        )
    }
}

@Dao
interface OnlineTrackDao {
    @Query("SELECT * FROM online_tracks ORDER BY lastSeenAt DESC")
    fun all(): Flow<List<OnlineTrackEntity>>

    @Query("SELECT * FROM online_tracks WHERE videoId = :videoId")
    suspend fun get(videoId: String): OnlineTrackEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(track: OnlineTrackEntity)

    @Query("UPDATE online_tracks SET inLibrary = :inLibrary WHERE videoId = :videoId")
    suspend fun setInLibrary(videoId: String, inLibrary: Boolean)
}

/** Remembers online tracks without ever demoting one the user already put in the library. */
class OnlineTrackStore(private val dao: OnlineTrackDao) {
    val all: Flow<List<OnlineTrackEntity>> = dao.all()

    suspend fun remember(tracks: List<OnlineTrack>, inLibrary: Boolean = false) {
        val now = System.currentTimeMillis()
        for (t in tracks) {
            val old = dao.get(t.videoId)
            dao.upsert(
                OnlineTrackEntity.of(t, inLibrary || old?.inLibrary == true, now)
                    .copy(addedAt = old?.addedAt ?: now, artworkUrl = t.artworkUrl ?: old?.artworkUrl),
            )
        }
    }

    suspend fun setInLibrary(videoId: String, inLibrary: Boolean) = dao.setInLibrary(videoId, inLibrary)
}

/** Saved online rows → Songs; downloads replace them in the unified library (same id). */
fun OnlineTrackEntity.toSong(): Song = toTrack().toSong().copy(dateAddedSec = addedAt / 1000)
