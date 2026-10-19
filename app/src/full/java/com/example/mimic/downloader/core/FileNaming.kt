package com.example.mimic.downloader.core

import java.io.File

/** Safe filename generation: sanitizes titles and de-duplicates against existing files. */
object FileNaming {

    private val ILLEGAL_CHARS = Regex("""[/\\:*?"<>|\x00-\x1F]""")
    private const val MAX_BASENAME_LENGTH = 120

    /** Android filesystems cap a name at 255 bytes; leave room for " (99)" and an extension. */
    private const val MAX_BASENAME_BYTES = 200

    /**
     * Turns an arbitrary media title into a filesystem-safe base name (no extension).
     * Strips path separators and control characters, collapses whitespace, prevents
     * path traversal (".." segments), and falls back to a generic name if nothing usable remains.
     */
    fun sanitize(rawTitle: String): String {
        var name = rawTitle.trim()
        name = ILLEGAL_CHARS.replace(name, " ")
        name = name.replace(Regex("\\s+"), " ").trim()
        name = name.trim('.', ' ')
        name = name.replace("..", "_")
        if (name.length > MAX_BASENAME_LENGTH) {
            name = name.take(MAX_BASENAME_LENGTH).trim()
        }
        name = truncateToUtf8Bytes(name, MAX_BASENAME_BYTES).trim()
        return name.ifBlank { "MImic Download" }
    }

    /** Cuts [text] to at most [maxBytes] UTF-8 bytes without splitting a character (emoji, CJK, …). */
    private fun truncateToUtf8Bytes(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            out.appendCodePoint(codePoint)
            bytes += size
            i += Character.charCount(codePoint)
        }
        return out.toString()
    }

    /**
     * Given a desired "name.ext" and a directory, returns a filename guaranteed not to collide
     * with an existing file, appending " (1)", " (2)", etc. before the extension as needed.
     */
    fun dedupeAgainstDirectory(directory: File, desiredBaseName: String, extension: String): String {
        val base = sanitize(desiredBaseName)
        var candidate = "$base.$extension"
        var index = 1
        while (File(directory, candidate).exists()) {
            candidate = "$base ($index).$extension"
            index++
        }
        return candidate
    }

    /**
     * Given a desired base name and a set of names already used in the destination (e.g. from a
     * MediaStore query or a SAF directory listing), returns a non-colliding "name.ext".
     */
    fun dedupeAgainstNames(existingNames: Set<String>, desiredBaseName: String, extension: String): String {
        val base = sanitize(desiredBaseName)
        var candidate = "$base.$extension"
        var index = 1
        while (existingNames.contains(candidate)) {
            candidate = "$base ($index).$extension"
            index++
        }
        return candidate
    }
}
