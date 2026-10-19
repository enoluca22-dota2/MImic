package com.example.mimic.downloader.data.provider

import com.example.mimic.downloader.data.model.FormatKind
import com.example.mimic.downloader.download.ProgressTracker
import com.yausername.youtubedl_android.YoutubeDLRequest

/** Translates a [DownloadJobRequest] into the concrete yt-dlp command-line options. */
object DownloadRequestBuilder {

    /** Prefix of the "what was delivered" line: "[ytdfinal] <height>|<format ids>". */
    const val FINAL_PREFIX = "[ytdfinal]"

    fun build(job: DownloadJobRequest): YoutubeDLRequest {
        val request = YoutubeDLRequest(job.sourceUrl)
        if (job.playlistIndex != null) {
            // An item inside a multi-item post/collection: select it by position.
            request.addOption("--playlist-items", job.playlistIndex.toString())
        } else {
            request.addOption("--no-playlist")
        }
        job.rateLimit?.let { request.addOption("--limit-rate", it) }
        request.addOption("--newline")
        applyRobustnessOptions(request, job.alternateClients)
        request.addOption("-o", "${job.outputDirectory}/${job.outputFileNameNoExt}.%(ext)s")
        // What was really downloaded (the fallback selectors below may pick a lower height than
        // asked for), then the final on-disk path as the last stdout line.
        request.addOption("--print", "after_move:$FINAL_PREFIX %(height)s|%(format_id)s")
        request.addOption("--print", "after_move:filepath")
        // --print implies --quiet, and quiet mode turns progress off and moves screen output to
        // stderr, where youtubedl-android never looks: the app then saw 0% until the file was
        // done. --no-quiet keeps progress on stdout; the templates make it machine-readable
        // (bytes, totals, speed, per stream) for ProgressTracker.
        request.addOption("--no-quiet")
        request.addOption("--print", "before_dl:${ProgressTracker.SIZES_TEMPLATE}")
        request.addOption("--progress-template", "download:${ProgressTracker.PROGRESS_TEMPLATE}")

        // The format id the user picked was resolved by whichever client/session listed formats
        // at detect time. The player_client fallback below can query a *different* client at
        // download time to dodge 403s, and that client doesn't always expose the exact same
        // format ids — so every selector below tries the exact id first, then falls back to the
        // best format at the same (or lower) height/quality rather than hard-failing with
        // "Requested format is not available".
        val heightClause = job.heightPx?.let { "[height<=$it]" }.orEmpty()
        when {
            job.formatKind == FormatKind.AUDIO && job.requiresAudioExtraction -> {
                // MP3 conversion is done ourselves afterwards (see AudioConverter), not via
                // yt-dlp's --extract-audio postprocessor: youtubedl-android's bundled ffmpeg
                // binary is packaged as "libffmpeg.so" (forced by Android's native-lib naming),
                // which breaks yt-dlp's ffmpeg/ffprobe sibling-path derivation and makes the
                // postprocessor fail with "ffprobe and ffmpeg not found". Just fetch the raw
                // audio stream here.
                request.addOption("-f", "${job.formatId}/bestaudio/best")
            }
            job.formatKind == FormatKind.VIDEO && job.requiresAudioMerge -> {
                request.addOption(
                    "-f",
                    "${job.formatId}+bestaudio/bestvideo$heightClause+bestaudio/best$heightClause/best",
                )
                request.addOption("--merge-output-format", job.container ?: "mp4")
            }
            job.formatKind == FormatKind.VIDEO -> {
                request.addOption("-f", "${job.formatId}/best$heightClause/best")
            }
            else -> {
                // Audio kept in its original container (e.g. M4A): exact stream first, then the
                // best audio in the same container, then any best audio.
                val ext = job.container?.let { "[ext=$it]" }.orEmpty()
                request.addOption("-f", "${job.formatId}/bestaudio$ext/bestaudio")
                // Title/artist/date tags written by a stream copy (no re-encode).
                request.addOption("--embed-metadata")
            }
        }
        return request
    }

    /**
     * Retries absorb transient network hiccups. By default yt-dlp picks YouTube's player clients
     * itself — its defaults are kept current by the daily update check (YtDlpUpdater), and forcing clients can
     * break downloads (e.g. when YouTube serves the android/ios clients SABR-only streams with no
     * usable URLs, "Requested format is not available" even though the formats exist).
     *
     * [alternateClients] is the fallback the engine uses once when the default attempt is refused
     * (HTTP 403) or reports the format missing: the android/ios/web clients sidestep the web
     * client's PO-token requirements that most often trigger those 403s.
     */
    fun applyRobustnessOptions(request: YoutubeDLRequest, alternateClients: Boolean = false) {
        if (alternateClients) request.addOption("--extractor-args", "youtube:player_client=android,ios,web")
        request.addOption("--retries", "3")
        request.addOption("--fragment-retries", "3")
    }
}
