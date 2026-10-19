package com.example.mimic.downloader.data.provider

import com.example.mimic.downloader.data.model.FormatKind
import com.example.mimic.downloader.data.model.MediaFormat
import com.example.mimic.downloader.data.model.MediaInfo
import com.example.mimic.downloader.data.model.Metric
import com.example.mimic.downloader.data.model.QualityTier
import com.example.mimic.downloader.data.model.Watermark
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo

/**
 * Pure mapping from yt-dlp's raw `-F`/`--dump-json` output (as decoded by youtubedl-android's
 * Jackson mappers) to the app's domain model. No values are invented: fields yt-dlp didn't report
 * become [Metric.Unknown] rather than a guessed number, per the "never invent information" rule.
 */
object FormatMapper {

    private const val HDR_MARKER = "hdr"
    private const val NONE = "none"
    private const val WATERMARK_MARKER = "watermark"

    fun toMediaInfo(sourceUrl: String, info: VideoInfo): MediaInfo {
        val rawFormats = info.formats.orEmpty()
        val mapped = rawFormats.mapNotNull { toMediaFormat(it) }
        val watermarkAware = markWatermarks(mapped)
        val videoFormats = withBestAvailable(
            assignTiers(dedupeVideoByResolution(watermarkAware.filter { it.kind == FormatKind.VIDEO }), byVideo = true),
        )
        val audioSources = watermarkAware.filter { it.kind == FormatKind.AUDIO }
        val audioFormats = mp3Options(
            dedupeAudioByBitrate(audioSources),
            info.duration.takeIf { it > 0 }?.toLong(),
        ) + listOfNotNull(nativeM4a(audioSources))
        return MediaInfo(
            sourceUrl = sourceUrl,
            webpageUrl = info.webpageUrl ?: sourceUrl,
            title = info.title ?: info.fulltitle ?: "Untitled",
            uploader = info.uploader,
            thumbnailUrl = info.thumbnail,
            durationSeconds = info.duration.takeIf { it > 0 }?.toLong(),
            videoFormats = videoFormats,
            audioFormats = audioFormats,
            subtitles = emptyList(), // not exposed by youtubedl-android's VideoInfo mapper
            extractorKey = info.extractorKey,
        )
    }

    private fun toMediaFormat(raw: VideoFormat): MediaFormat? {
        val id = raw.formatId ?: return null
        if (raw.ext == "mhtml") return null // storyboard thumbnails, not a real downloadable stream

        val vcodec = raw.vcodec?.takeIf { it.isNotBlank() && it != NONE }
        val acodec = raw.acodec?.takeIf { it.isNotBlank() && it != NONE }
        val hasVideo = vcodec != null && raw.height > 0
        val hasAudioOnly = acodec != null && !hasVideo
        val kind = when {
            hasVideo -> FormatKind.VIDEO
            hasAudioOnly -> FormatKind.AUDIO
            else -> return null
        }

        val hdrHint = (raw.formatNote?.lowercase()?.contains(HDR_MARKER) == true) ||
            (raw.format?.lowercase()?.contains(HDR_MARKER) == true)
        val watermarked = raw.formatNote?.lowercase()?.contains(WATERMARK_MARKER) == true

        val fileSize: Metric<Long> = when {
            raw.fileSize > 0 -> Metric.Known(raw.fileSize)
            raw.fileSizeApproximate > 0 -> Metric.Estimated(raw.fileSizeApproximate)
            else -> Metric.Unknown
        }

        val bitrate: Metric<Int> = when {
            kind == FormatKind.AUDIO && raw.abr > 0 -> Metric.Known(raw.abr)
            raw.tbr > 0 -> Metric.Known(raw.tbr)
            else -> Metric.Unknown
        }

        val sampleRate: Metric<Int> = if (raw.asr > 0) Metric.Known(raw.asr) else Metric.Unknown

        return MediaFormat(
            formatId = id,
            kind = kind,
            container = raw.ext,
            resolutionLabel = if (kind == FormatKind.VIDEO && raw.height > 0) "${raw.height}p" else null,
            heightPx = raw.height.takeIf { it > 0 },
            fps = raw.fps.takeIf { it > 0 },
            videoCodec = vcodec,
            audioCodec = acodec,
            hasAudio = acodec != null,
            isHdr = hdrHint,
            bitrateKbps = bitrate,
            sampleRateHz = sampleRate,
            fileSizeBytes = fileSize,
            tier = QualityTier.MEDIUM, // replaced by assignTiers()
            watermark = if (watermarked) Watermark.WATERMARKED else Watermark.UNKNOWN,
        )
    }

    /**
     * yt-dlp typically reports several raw streams per resolution (different codecs, muxed vs
     * DASH, etc). Collapse those to a single representative per real height so the picker reads
     * like "360p / 720p / 1080p / …" instead of a dozen near-duplicate rows. Never invents a
     * resolution that wasn't actually reported.
     */
    private fun dedupeVideoByResolution(formats: List<MediaFormat>): List<MediaFormat> {
        return formats
            .filter { it.heightPx != null }
            .groupBy { it.heightPx }
            .values
            .map { group ->
                group.sortedWith(
                    // Never pick a stream the extractor marks as watermarked when a clean one exists.
                    compareBy<MediaFormat> { it.watermark == Watermark.WATERMARKED }
                        .thenByDescending { it.hasAudio }
                        .thenByDescending { it.videoCodec?.startsWith("avc1") == true }
                        .thenByDescending {
                            (it.fileSizeBytes as? Metric.Known)?.value
                                ?: (it.fileSizeBytes as? Metric.Estimated)?.value
                                ?: (it.bitrateKbps as? Metric.Known)?.value?.toLong()
                                ?: 0L
                        },
                ).first()
            }
            .sortedByDescending { it.heightPx }
    }

    /**
     * Same idea for audio: collapse near-duplicate bitrates (e.g. 127.x vs 128.x kbps reported
     * for effectively the same stream) into one entry per real, distinct quality tier.
     */
    private fun dedupeAudioByBitrate(formats: List<MediaFormat>): List<MediaFormat> {
        val (known, unknown) = formats.partition { it.bitrateKbps is Metric.Known || it.bitrateKbps is Metric.Estimated }
        val deduped = known
            .groupBy { bitrateBucket(it.bitrateKbps) }
            .values
            .map { group -> group.maxByOrNull { bitrateValue(it.bitrateKbps) ?: 0 }!! }
            .sortedByDescending { bitrateValue(it.bitrateKbps) ?: 0 }
        // If nothing reported a bitrate at all, still surface one real entry rather than nothing.
        return if (deduped.isEmpty() && unknown.isNotEmpty()) listOf(unknown.first()) else deduped
    }

    /**
     * Audio is always delivered as MP3. Offer "Original quality (VBR)" — the encoder chooses the
     * bitrate, so the file follows the real source quality — plus the standard fixed bitrates.
     * Every option downloads the best real source stream. Fixed-bitrate sizes are estimates
     * (duration × bitrate), marked as such; the VBR size can't be known in advance, so it's Unknown.
     */
    private fun mp3Options(sources: List<MediaFormat>, durationSeconds: Long?): List<MediaFormat> {
        val best = sources.firstOrNull() ?: return emptyList()
        val vbr = best.copy(mp3BitrateKbps = null, fileSizeBytes = Metric.Unknown, tier = QualityTier.BEST)
        val fixed = MP3_BITRATES_KBPS.mapIndexed { index, kbps ->
            best.copy(
                mp3BitrateKbps = kbps,
                bitrateKbps = Metric.Known(kbps),
                fileSizeBytes = durationSeconds?.let { Metric.Estimated(it * kbps * 1000 / 8) } ?: Metric.Unknown,
                tier = MP3_TIERS[index],
            )
        }
        return listOf(vbr) + fixed
    }

    /**
     * Only when the extractor itself distinguishes watermarked streams (TikTok marks them in the
     * format note) do we label the *other* streams as watermark-free. Otherwise nothing is claimed.
     */
    private fun markWatermarks(formats: List<MediaFormat>): List<MediaFormat> {
        if (formats.none { it.watermark == Watermark.WATERMARKED }) return formats
        return formats.map { if (it.watermark == Watermark.UNKNOWN) it.copy(watermark = Watermark.NO_WATERMARK) else it }
    }

    /** "Best available" on top when there's a real choice: yt-dlp takes the best video+audio at download time. */
    private fun withBestAvailable(videos: List<MediaFormat>): List<MediaFormat> {
        if (videos.size < 2) return videos
        val top = videos.first()
        val best = top.copy(
            formatId = "bestvideo",
            isBestAvailable = true,
            hasAudio = false, // always merged with the best audio track
            resolutionLabel = null,
            fileSizeBytes = Metric.Unknown,
            tier = QualityTier.BEST,
        )
        return listOf(best) + videos
    }

    /** The source's own M4A audio, downloaded as-is — offered only if such a stream really exists. */
    private fun nativeM4a(audio: List<MediaFormat>): MediaFormat? =
        audio.filter { it.container.equals("m4a", ignoreCase = true) }
            .maxByOrNull { bitrateValue(it.bitrateKbps) ?: 0 }
            ?.copy(nativeAudio = true, mp3BitrateKbps = null, tier = QualityTier.HIGH)

    private val MP3_BITRATES_KBPS = listOf(320, 256, 192, 128)
    private val MP3_TIERS = listOf(QualityTier.BEST, QualityTier.HIGH, QualityTier.MEDIUM, QualityTier.LOW)

    private fun bitrateValue(metric: Metric<Int>): Int? = when (metric) {
        is Metric.Known -> metric.value
        is Metric.Estimated -> metric.value
        Metric.Unknown -> null
    }

    private fun bitrateBucket(metric: Metric<Int>): Int? = bitrateValue(metric)?.let { (it / 24) * 24 }

    /** Ranks real, provider-reported formats against each other; never assigns a tier out of thin air. */
    private fun assignTiers(formats: List<MediaFormat>, byVideo: Boolean): List<MediaFormat> {
        if (formats.isEmpty()) return formats
        fun rank(f: MediaFormat): Int = if (byVideo) {
            f.heightPx ?: (f.bitrateKbps as? Metric.Known)?.value ?: 0
        } else {
            (f.bitrateKbps as? Metric.Known)?.value ?: (f.bitrateKbps as? Metric.Estimated)?.value ?: 0
        }

        val distinctRanksDesc = formats.map(::rank).distinct().sortedDescending()
        if (distinctRanksDesc.size == 1) {
            return formats.map { it.copy(tier = QualityTier.HIGH) }
        }

        fun tierFor(value: Int): QualityTier {
            val position = distinctRanksDesc.indexOf(value)
            val fraction = position.toFloat() / (distinctRanksDesc.size - 1).coerceAtLeast(1)
            return when {
                position == 0 -> QualityTier.BEST
                fraction <= 0.34f -> QualityTier.HIGH
                fraction <= 0.67f -> QualityTier.MEDIUM
                else -> QualityTier.LOW
            }
        }

        return formats
            .map { it.copy(tier = tierFor(rank(it))) }
            .sortedByDescending { rank(it) }
    }

}
