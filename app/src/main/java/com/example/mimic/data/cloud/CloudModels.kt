package com.example.mimic.data.cloud

import com.example.mimic.data.MetadataFallback
import com.example.mimic.data.MusicProvider
import com.example.mimic.data.Song
import com.example.mimic.data.SongSource
import com.example.mimic.data.db.CloudAccountEntity
import com.example.mimic.data.db.CloudItemEntity
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/** The built-in cloud music sources (MImic 3.1). Not plugins: both are compiled into the app. */
enum class CloudProviderType(val id: String, val label: String, val musicProvider: MusicProvider) {
    ONEDRIVE("onedrive", "OneDrive", MusicProvider.ONEDRIVE),
    GOOGLE_DRIVE("gdrive", "Google Drive", MusicProvider.GOOGLE_DRIVE);

    companion object {
        fun of(id: String): CloudProviderType? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The stable identity of one cloud file: provider + account + the provider's own item id. Never a
 * name, path or download URL — names change on rename, paths on move, and OneDrive download URLs
 * expire within the hour. Two accounts can never share a ref, even for identical files.
 */
data class CloudRef(val provider: CloudProviderType, val accountId: String, val itemId: String) {
    val accountKey: String get() = accountKeyOf(provider, accountId)

    /** The id used by downloads/favorites bookkeeping ("onedrive:<account>:<item>"). */
    val remoteId: String get() = "${provider.id}:${enc(accountId)}:${enc(itemId)}"

    /** What the player is given. Holds no token and no temporary URL — resolved at play time. */
    val playUri: String get() = "$SCHEME://${provider.id}/${enc(accountId)}/${enc(itemId)}"

    companion object {
        const val SCHEME = "mimic-cloud"

        fun accountKeyOf(provider: CloudProviderType, accountId: String) = "${provider.id}:$accountId"

        fun parse(remoteId: String): CloudRef? {
            val parts = remoteId.split(':')
            if (parts.size != 3) return null
            val p = CloudProviderType.of(parts[0]) ?: return null
            return runCatching { CloudRef(p, dec(parts[1]), dec(parts[2])) }.getOrNull()
        }

        fun fromPlayUri(uri: String): CloudRef? {
            if (!uri.startsWith("$SCHEME://")) return null
            val parts = uri.removePrefix("$SCHEME://").split('/')
            if (parts.size != 3) return null
            val p = CloudProviderType.of(parts[0]) ?: return null
            return runCatching { CloudRef(p, dec(parts[1]), dec(parts[2])) }.getOrNull()
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")
    }
}

/**
 * Song id for a cloud track: 62 bits of SHA-256 of its [CloudRef.remoteId], in a range
 * ([2^61, 2^62)) that can't meet MediaStore ids or Navidrome's remoteStableId (< 2^33). A plain
 * 31-bit hash would collide somewhere in a large library; this effectively never does.
 */
fun cloudStableId(remoteId: String): Long {
    val d = MessageDigest.getInstance("SHA-256").digest(remoteId.toByteArray())
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFF)
    return (v and 0x1FFF_FFFF_FFFF_FFFFL) or 0x2000_0000_0000_0000L
}

/** One file/folder as returned by a provider API, before it is stored. */
data class CloudItem(
    val id: String,
    val name: String,
    val parentId: String?,
    val isFolder: Boolean,
    val mimeType: String?,
    val size: Long,
    val createdAt: Long,
    val modifiedAt: Long,
    val contentVersion: String?,
    val hash: String?,
    /** Provider-supplied audio tags (OneDrive's `audio` facet); null when the provider has none. */
    val audio: CloudAudioTags? = null,
)

data class CloudAudioTags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val durationMs: Long = 0L,
    val genre: String? = null,
    val year: Int? = null,
)

/** A folder shown in the "choose music folder" browser. */
data class CloudFolder(val id: String, val name: String, val childCount: Int? = null)

/** Signed-in identity returned by a provider's authorization flow. */
data class CloudIdentity(val accountId: String, val displayName: String, val email: String?)

/** Recoverable failure classes, so the UI can say what to do instead of "error". */
sealed class CloudException(message: String) : Exception(message) {
    /** Token expired/revoked and can't be renewed silently: the user must sign in again. */
    class AuthRequired(message: String = "Sign in again to continue") : CloudException(message)
    class Offline(message: String = "No internet connection") : CloudException(message)
    class Throttled(val retryAfterSec: Long) : CloudException("The provider asked MImic to slow down; retrying in ${retryAfterSec}s")
    class NotConfigured(message: String) : CloudException(message)
    /** The user backed out of the provider's sign-in UI (or it closed without a result). */
    class Cancelled(message: String = "Sign-in was cancelled") : CloudException(message)
    /** A provider failure with a status code but no more specific meaning (e.g. Google's 8). */
    class ProviderError(val code: Int, message: String) : CloudException(message)
    class Http(val code: Int, message: String) : CloudException(message)
}

/** Which audio files MImic shows. Everything else in the folder is ignored (never modified). */
object AudioFormats {
    // Formats Media3/ExoPlayer decodes on every supported Android version.
    private val supported = setOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wave")
    // Audio MImic can't play: DRM-protected iTunes (m4p), WMA, and formats ExoPlayer lacks.
    private val unsupported = setOf("m4p", "wma", "aif", "aiff", "ape", "wv", "mpc", "ra", "rm", "dsf", "dff")
    private val coverNames = setOf("cover", "folder", "front", "album", "albumart")

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isPlayable(name: String) = extension(name) in supported
    fun isUnsupportedAudio(name: String, mimeType: String?) =
        !isPlayable(name) && (extension(name) in unsupported || mimeType?.startsWith("audio/") == true)
    fun isFolderCover(name: String): Boolean {
        val ext = extension(name)
        return (ext == "jpg" || ext == "jpeg" || ext == "png") && name.substringBeforeLast('.').lowercase() in coverNames
    }
}

/** Result of turning one account's stored items into library songs. */
data class CloudCatalog(val songs: List<Song>, val unsupportedCount: Int, val duplicateCount: Int)

/**
 * Pure mapping of an account's stored cloud items to [Song]s (JVM-tested). Metadata precedence:
 * embedded / provider tags, then the folder layout (Artist/Album/Track), then the file name.
 * Artist is taken from a folder only in an Artist/Album/Track layout — a single folder level is
 * treated as an album, never guessed to be an artist.
 */
object CloudCatalogMapper {

    fun map(account: CloudAccountEntity, items: List<CloudItemEntity>): CloudCatalog {
        val provider = CloudProviderType.of(account.provider) ?: return CloudCatalog(emptyList(), 0, 0)
        val root = account.folderId ?: return CloudCatalog(emptyList(), 0, 0)
        val folders = items.filter { it.isFolder }.associateBy { it.itemId }
        var unsupported = 0
        val audio = ArrayList<CloudItemEntity>()
        for (it in items) {
            if (it.isFolder) continue
            if (AudioFormats.isPlayable(it.name)) audio += it
            else if (AudioFormats.isUnsupportedAudio(it.name, it.mimeType)) unsupported++
        }
        // The same recording uploaded twice (identical content hash + size) is shown once.
        val unique = audio
            // Keep the first upload; on a tie the plain name ("Song.mp3" over "Song (1).mp3").
            .sortedWith(compareBy<CloudItemEntity> { it.createdAt }.thenBy { it.name.length }.thenBy { it.itemId })
            .distinctBy { if (it.hash != null) "${it.hash}/${it.size}" else "id:${it.itemId}" }
        // cover.jpg / folder.png fetched for a folder applies to the tracks in it.
        val folderArt = items.filter { !it.isFolder && it.artUri != null && AudioFormats.isFolderCover(it.name) }
            .associate { it.parentId to it.artUri!! }
        val songs = unique.mapNotNull { item ->
            val path = folderPath(item.parentId, root, folders) ?: return@mapNotNull null // outside the folder
            toSong(provider, account, item, path, folderArt[item.parentId])
        }
        // One track's artwork (embedded cover / OneDrive thumbnail) stands in for its whole album.
        val albumArt = HashMap<String, String>()
        for (s in songs) if (s.albumArtUri != null) albumArt.putIfAbsent(s.albumKey, s.albumArtUri)
        val withArt = songs.map { s -> if (s.albumArtUri == null) albumArt[s.albumKey]?.let { s.copy(albumArtUri = it) } ?: s else s }
        return CloudCatalog(withArt, unsupported, audio.size - unique.size)
    }

    /** Folder names from the music root down to [parentId]; null if not inside the root. */
    fun folderPath(parentId: String?, root: String, folders: Map<String, CloudItemEntity>): List<String>? {
        val out = ArrayList<String>()
        var cur = parentId
        var guard = 0
        while (cur != null && cur != root) {
            val f = folders[cur] ?: return null
            out += f.name
            cur = f.parentId
            if (++guard > 64) return null // cycle guard
        }
        return if (cur == root) out.asReversed() else null
    }

    private fun toSong(
        provider: CloudProviderType,
        account: CloudAccountEntity,
        item: CloudItemEntity,
        path: List<String>,
        folderCover: String?,
    ): Song {
        val ref = CloudRef(provider, account.accountId, item.itemId)
        val fileTitle = fileTitle(item.name)
        val title = item.title?.takeIf { it.isNotBlank() }?.trim() ?: fileTitle.second
        val artist = MetadataFallback.artist(
            item.artist?.takeIf { it.isNotBlank() }
                ?: item.albumArtist?.takeIf { it.isNotBlank() }
                ?: path.takeIf { it.size >= 2 }?.first(),
        )
        val tagAlbum = item.album?.takeIf { it.isNotBlank() }?.trim()
        val album = MetadataFallback.album(tagAlbum ?: path.lastOrNull())
        val albumIdentity = when {
            tagAlbum != null -> "tag:" + tagAlbum.lowercase() + "|" + (item.albumArtist?.trim()?.lowercase() ?: ("dir:" + item.parentId))
            path.isNotEmpty() -> "dir:" + item.parentId
            else -> "unknown"
        }
        return Song(
            id = cloudStableId(ref.remoteId),
            title = title,
            artist = artist,
            album = album,
            durationMs = item.durationMs,
            uri = ref.playUri,
            albumArtUri = item.artUri ?: folderCover,
            dateAddedSec = item.createdAt / 1000,
            folder = (listOf(account.folderName ?: provider.label) + path).joinToString("/"),
            genre = item.genre,
            source = SongSource.CLOUD,
            remoteId = ref.remoteId,
            albumKey = "cloud:${account.accountKey}:album:$albumIdentity",
            artistKey = MetadataFallback.artistKey(artist),
            trackNo = (item.discNo ?: 0) * 1000 + (item.trackNo ?: fileTitle.first ?: 0),
        )
    }

    // "03 - Song", "3. Song", "03_Song", or zero-padded "03 Song" — but not "50 Cent - Song".
    private val separatedTrack = Regex("""^\s*(\d{1,3})\s*[-._)]\s*(.+)$""")
    private val paddedTrack = Regex("""^\s*(0\d)\s+(.+)$""")

    /** "03 - Song.mp3" -> (3, "Song"); "Song.mp3" -> (null, "Song"). */
    fun fileTitle(name: String): Pair<Int?, String> {
        val base = name.substringBeforeLast('.').trim().ifBlank { name }
        val m = separatedTrack.find(base) ?: paddedTrack.find(base) ?: return null to base
        return m.groupValues[1].toIntOrNull() to m.groupValues[2].trim()
    }
}
