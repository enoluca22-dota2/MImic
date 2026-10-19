package com.example.mimic.downloader.core

import java.net.URI

/**
 * Validates and normalizes user-supplied media URLs before they reach the provider layer.
 *
 * Deliberately implemented with plain [URI] parsing (no android.util.Patterns) so it is a
 * pure-JVM class that runs under fast local unit tests without an Android framework stub.
 */
object UrlValidator {

    private val URL_SCAN_REGEX = Regex(
        """https?://[\w\-.]+(?::\d+)?(?:/[\w\-._~:/?#\[\]@!$&'()*+,;=%]*)?""",
        RegexOption.IGNORE_CASE
    )

    /** Returns the trimmed URL if it is a syntactically valid http(s) URL, otherwise null. */
    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return try {
            val uri = URI(trimmed)
            val host = uri.host
            if (host.isNullOrBlank() || !host.contains('.')) null else trimmed
        } catch (_: Exception) {
            null
        }
    }

    fun isValid(input: String): Boolean = normalize(input) != null

    /** Finds the first http(s) URL inside an arbitrary text blob (e.g. a share-sheet payload). */
    fun extractFirstUrl(text: String): String? {
        val match = URL_SCAN_REGEX.find(text) ?: return null
        return normalize(match.value)
    }

    /**
     * All distinct http(s) URLs in [text], in order — for pasting or sharing several links at
     * once. Trailing punctuation from prose ("…see https://x.com/a/status/1.") is dropped.
     */
    fun extractAllUrls(text: String): List<String> =
        URL_SCAN_REGEX.findAll(text)
            .map { it.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\'', '"') }
            .mapNotNull { normalize(it) }
            .distinct()
            .toList()
}
