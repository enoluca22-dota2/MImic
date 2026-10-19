package com.example.mimic.data

/**
 * Which phone music MImic shows (MImic 4.3, Settings → Library & Storage). Nothing is ever
 * deleted: an excluded folder or a too-short file is only hidden from the library, and including
 * it again brings it back with its favorites, playlists and history (they are keyed by song id).
 *
 * Android's own media scanner already skips folders that contain a `.nomedia` file, so those
 * never reach MImic in the first place.
 */
data class LibraryScanConfig(
    /** Folder paths ("Music/WhatsApp/") hidden from the library, subfolders included. */
    val excludedFolders: Set<String> = emptySet(),
    /** Files shorter than this are hidden (0 = show everything). 30 s is what MImic always used. */
    val minDurationSec: Int = DEFAULT_MIN_SEC,
) {
    companion object {
        const val DEFAULT_MIN_SEC = 30
        val MIN_CHOICES = listOf(0, 10, 30, 60)
    }
}

/** A folder on the phone that holds music, for the include/exclude list. */
data class ScanFolder(val path: String, val name: String, val songCount: Int, val excluded: Boolean)

object LibraryScan {
    /** "Music/MImic" / "/Music/MImic/" → "Music/MImic/" (case kept for display). */
    fun normalize(path: String): String {
        val p = path.replace('\\', '/').trim().trim('/')
        return if (p.isEmpty()) "" else "$p/"
    }

    fun isExcluded(folderPath: String, excluded: Set<String>): Boolean {
        if (excluded.isEmpty() || folderPath.isEmpty()) return false
        val f = normalize(folderPath).lowercase()
        return excluded.any { e -> val n = normalize(e).lowercase(); n.isNotEmpty() && f.startsWith(n) }
    }

    /** The phone songs the library shows under [config] (songs from other sources pass through). */
    fun filter(songs: List<Song>, config: LibraryScanConfig): List<Song> {
        val minMs = config.minDurationSec.coerceAtLeast(0) * 1000L
        return songs.filter { s ->
            s.source != SongSource.LOCAL ||
                (s.durationMs >= minMs && !isExcluded(s.folderPath, config.excludedFolders))
        }
    }

    /** Every folder that has phone music (before filtering), with how many songs each holds. */
    fun folders(songs: List<Song>, config: LibraryScanConfig): List<ScanFolder> =
        songs.asSequence()
            .filter { it.source == SongSource.LOCAL && it.folderPath.isNotEmpty() }
            .groupBy { normalize(it.folderPath) }
            .map { (path, list) ->
                ScanFolder(path, path.trimEnd('/').substringAfterLast('/').ifEmpty { path }, list.size, isExcluded(path, config.excludedFolders))
            }
            .sortedBy { it.path.lowercase() }
}
