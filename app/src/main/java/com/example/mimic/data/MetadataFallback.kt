package com.example.mimic.data

/**
 * One consistent policy for incomplete tags (MImic 3.0). Pure + JVM-tested.
 *
 *  1. Real audio metadata always wins.
 *  2. A missing title falls back to the file name (without extension) — the file's actual name.
 *  3. Missing artist / album become "Unknown Artist" / "Unknown Album". Nothing is invented and
 *     the original tags are never rewritten.
 *
 * MediaStore and Navidrome report missing values in several ways ("<unknown>", "[Unknown Album]",
 * blank, null); they are all normalized here so grouping and display agree.
 */
object MetadataFallback {
    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val UNKNOWN_ALBUM = "Unknown Album"
    const val UNKNOWN_TITLE = "Unknown Title"

    private val unknownMarkers = setOf(
        "<unknown>", "unknown", "[unknown]", "[unknown artist]", "[unknown album]",
        "unknown artist", "unknown album",
    )

    private fun isUnknown(value: String?): Boolean =
        value == null || value.isBlank() || value.trim().lowercase() in unknownMarkers

    fun title(tag: String?, fileName: String?): String = when {
        !isUnknown(tag) -> tag!!.trim()
        !fileName.isNullOrBlank() -> fileName.substringAfterLast('/').substringBeforeLast('.').ifBlank { fileName }.trim()
        else -> UNKNOWN_TITLE
    }

    fun artist(tag: String?): String = if (isUnknown(tag)) UNKNOWN_ARTIST else tag!!.trim()

    fun album(tag: String?): String = if (isUnknown(tag)) UNKNOWN_ALBUM else tag!!.trim()

    /** Artist grouping key: case/space-insensitive so "Drake" and "drake " are one artist. */
    fun artistKey(artist: String): String =
        "artist:" + artist(artist).lowercase().replace(Regex("\\s+"), " ")
}
