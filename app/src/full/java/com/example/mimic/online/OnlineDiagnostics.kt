package com.example.mimic.online

import com.example.mimic.downloader.diagnostics.DiagnosticText
import com.example.mimic.online.data.TrackLog
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * The last online-playback and online-download outcomes, for Settings → Advanced → Download
 * Diagnostics. Each entry holds a category and a sanitized message only: no stream URLs, cookies,
 * tokens or account names ever reach it (messages pass through [DiagnosticText.clean], which
 * strips URLs and addresses).
 */
object OnlineDiagnostics {
    data class Entry(val at: Long, val videoId: String, val what: String, val category: String, val message: String)

    private const val MAX = 30
    private val entries = ConcurrentLinkedDeque<Entry>()

    fun record(videoId: String, category: String, error: Throwable?, what: String = "play") {
        val msg = DiagnosticText.clean("${error?.javaClass?.simpleName ?: ""}: ${error?.message ?: ""}").take(300)
        add(Entry(System.currentTimeMillis(), videoId, what, category, msg))
    }

    fun recordSuccess(videoId: String, what: String = "play") =
        add(Entry(System.currentTimeMillis(), videoId, what, "ok", ""))

    private fun add(e: Entry) {
        entries.addFirst(e)
        while (entries.size > MAX) entries.pollLast()
    }

    fun recent(): List<Entry> = entries.toList()

    /** Sanitized resolver log tail (BitChord's TrackLog), safe to include in a shared report. */
    suspend fun resolverLog(lines: Int = 120): String = redact(DiagnosticText.clean(TrackLog.recent(lines)))

    /** Masks anything token-shaped (visitor data, PoTokens, channel / session ids) and email addresses. */
    internal fun redact(text: String): String = text
        .replace(Regex("""[A-Za-z0-9_%=+/-]{28,}"""), "[redacted]")
        .replace(Regex("""(?i)(pageId|dataSyncId|authUser|cookie|SAPISID\w*)=\S+"""), "$1=[redacted]")
        .replace(Regex("""[\w.+-]+@[\w-]+\.[\w.]+"""), "[email]")
}
