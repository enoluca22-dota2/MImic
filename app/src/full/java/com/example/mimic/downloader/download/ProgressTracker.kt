package com.example.mimic.downloader.download

/**
 * Turns yt-dlp's machine-readable output into real, byte-based progress for one download.
 *
 * DownloadRequestBuilder asks yt-dlp for two extra kinds of stdout line:
 *
 *   [ytdsizes] <per-stream filesize list>|<per-stream approx list>|<filesize>|<filesize approx>
 *       printed once before the transfer: the size of every stream that will be downloaded
 *       (video + audio for a merge), so progress spans the whole download instead of
 *       restarting at 0% when the audio stream begins.
 *   [ytdprog] <status>|<downloaded>|<total>|<total estimate>|<speed>|<eta>|<format id>
 *       printed for every progress tick of the stream currently downloading.
 *
 * Nothing here is estimated from time: when the total isn't known, [Snapshot.fraction] is null
 * and the UI shows an indeterminate bar with the downloaded size instead of a made-up percentage.
 */
class ProgressTracker {

    data class Snapshot(
        val downloadedBytes: Long,
        /** Size of the whole download (all streams), or null while unknown. */
        val totalBytes: Long?,
        /** 0..1, or null when the total is unknown. */
        val fraction: Float?,
        val speedBytesPerSecond: Long?,
        val etaSeconds: Long?,
    ) {
        val percent: Float? get() = fraction?.times(100f)
    }

    private class Stream(var downloaded: Long, var total: Long?, var finished: Boolean)

    private var expectedStreams: Int? = null
    private var expectedTotal: Long? = null
    private val streams = LinkedHashMap<String, Stream>()
    private var lastFraction = 0f

    /** Feeds one stdout line; returns a new snapshot if the line carried progress. */
    fun onLine(line: String): Snapshot? {
        val trimmed = line.trim()
        return when {
            trimmed.startsWith(SIZES_PREFIX) -> {
                readSizes(trimmed.removePrefix(SIZES_PREFIX).trim())
                null
            }
            trimmed.startsWith(PROGRESS_PREFIX) -> readProgress(trimmed.removePrefix(PROGRESS_PREFIX).trim())
            else -> null
        }
    }

    private fun readSizes(payload: String) {
        val parts = payload.split('|')
        if (parts.size < 4) return
        val exact = parseList(parts[0])
        val approx = parseList(parts[1])
        if (exact != null || approx != null) {
            // A merge: one entry per stream; each stream's exact size, else its estimate.
            val count = maxOf(exact?.size ?: 0, approx?.size ?: 0)
            expectedStreams = count
            val sizes = (0 until count).map { i -> exact?.getOrNull(i) ?: approx?.getOrNull(i) }
            expectedTotal = if (sizes.all { it != null && it > 0 }) sizes.sumOf { it!! } else null
        } else {
            expectedStreams = 1
            expectedTotal = (number(parts[2]) ?: number(parts[3]))?.toLong()?.takeIf { it > 0 }
        }
    }

    private fun readProgress(payload: String): Snapshot? {
        val p = payload.split('|')
        if (p.size < 7) return null
        val status = p[0]
        val downloaded = number(p[1])?.toLong() ?: return null
        val streamTotal = (number(p[2]) ?: number(p[3]))?.toLong()?.takeIf { it > 0 }
        val speed = number(p[4])?.toLong()?.takeIf { it >= 0 }
        val streamId = p[6].ifBlank { "0" }

        val stream = streams.getOrPut(streamId) { Stream(0, null, false) }
        stream.downloaded = downloaded
        if (streamTotal != null) stream.total = streamTotal
        if (status == "finished") {
            stream.finished = true
            stream.total = stream.total ?: downloaded
            stream.downloaded = stream.total ?: downloaded
        }

        val downloadedAll = streams.values.sumOf { it.downloaded }
        val total = overallTotal()
        var fraction = total?.let { (downloadedAll.toDouble() / it).toFloat().coerceIn(0f, 1f) }
        if (fraction != null) {
            // 100% only when yt-dlp says every stream is finished — an estimated size that
            // turns out too small must not show "done" while bytes are still arriving.
            if (!allFinished()) fraction = minOf(fraction, MAX_BEFORE_FINISHED)
            // Size estimates (fragmented streams) can shrink a little; never move the bar backwards.
            fraction = maxOf(fraction, lastFraction)
            lastFraction = fraction
        }
        val eta = if (total != null && speed != null && speed > 0) ((total - downloadedAll).coerceAtLeast(0) / speed) else number(p[5])?.toLong()
        return Snapshot(downloadedAll, total, fraction, speed, eta)
    }

    private fun allFinished(): Boolean =
        streams.size >= (expectedStreams ?: 1) && streams.values.all { it.finished }

    /** The whole download's size, only when it is really known. */
    private fun overallTotal(): Long? {
        expectedTotal?.let { expected ->
            // A stream bigger than announced: trust what yt-dlp reports now.
            val reported = streams.values.sumOf { it.total ?: 0L }
            return maxOf(expected, reported)
        }
        val expected = expectedStreams ?: 1
        if (streams.size < expected) return null // a later stream's size isn't known yet
        if (streams.values.any { it.total == null }) return null
        return streams.values.sumOf { it.total!! }.takeIf { it > 0 }
    }

    private fun parseList(text: String): List<Long?>? {
        val t = text.trim()
        if (!t.startsWith("[")) return null
        val inner = t.removePrefix("[").removeSuffix("]").trim()
        if (inner.isEmpty()) return null // "[]": a single-stream download, sizes are in the other fields
        return inner.split(',').map { number(it)?.toLong() }
    }

    private fun number(text: String): Double? = text.trim().takeIf { it != "NA" && it != "null" && it != "None" }?.toDoubleOrNull()

    companion object {
        const val SIZES_PREFIX = "[ytdsizes]"
        private const val MAX_BEFORE_FINISHED = 0.99f
        const val PROGRESS_PREFIX = "[ytdprog]"

        /** `--print before_dl:` template: sizes of every stream about to be downloaded. */
        const val SIZES_TEMPLATE =
            "$SIZES_PREFIX %(requested_formats.:.filesize)j|%(requested_formats.:.filesize_approx)j|%(filesize)j|%(filesize_approx)j"

        /** `--progress-template download:` template. */
        const val PROGRESS_TEMPLATE =
            "$PROGRESS_PREFIX %(progress.status)s|%(progress.downloaded_bytes)s|%(progress.total_bytes)s|" +
                "%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s|%(info.format_id)s"
    }
}
