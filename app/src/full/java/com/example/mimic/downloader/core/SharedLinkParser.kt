package com.example.mimic.downloader.core

/**
 * Turns an incoming Intent's fields into the text the analyzer should receive. Pure (no Android
 * types) so every share/open case is unit-tested.
 *
 * - ACTION_SEND (text/…): EXTRA_TEXT, then EXTRA_SUBJECT, then the data URI. Apps often wrap the
 *   link in prose ("Check this out: …") or send several links; all links are kept, one per line.
 * - ACTION_VIEW: the opened URI itself (a YouTube/TikTok link tapped in another app).
 *
 * URLs are passed through untouched: `watch?v=AAA&list=BBB&index=5` keeps every parameter, so
 * the "Current video / Playlist" choice is still offered later.
 */
object SharedLinkParser {

    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_VIEW = "android.intent.action.VIEW"

    sealed interface Result {
        /** One or more links, newline-separated, ready for the analyzer. */
        data class Links(val urls: List<String>) : Result {
            val text: String get() = urls.joinToString("\n")
        }

        /** It was a share/open, but nothing usable was in it (empty or malformed). */
        data class NoLink(val sharedText: String?) : Result
    }

    /** Returns null when the intent isn't a share/open meant for us (e.g. the launcher). */
    fun parse(
        action: String?,
        mimeType: String?,
        extraText: CharSequence?,
        extraSubject: String?,
        dataString: String?,
    ): Result? = when (action) {
        ACTION_SEND -> {
            if (mimeType != null && !mimeType.startsWith("text/")) {
                NoLinkOf(null)
            } else {
                // EXTRA_TEXT first: it is where every mainstream app (incl. YouTube and TikTok) puts the link.
                val sources = listOfNotNull(extraText?.toString(), extraSubject, dataString)
                val urls = sources.flatMap { UrlValidator.extractAllUrls(it) }.distinct()
                if (urls.isEmpty()) NoLinkOf(sources.firstOrNull { it.isNotBlank() }) else Result.Links(urls)
            }
        }
        ACTION_VIEW -> {
            val url = dataString?.let { UrlValidator.normalize(it) }
            if (url == null) NoLinkOf(dataString) else Result.Links(listOf(url))
        }
        else -> null
    }

    @Suppress("FunctionName")
    private fun NoLinkOf(text: String?) = Result.NoLink(text?.trim()?.take(300)?.takeIf { it.isNotEmpty() })
}
