package com.example.mimic.downloader.download

import android.content.Context
import android.util.Log
import com.example.mimic.downloader.data.provider.ProviderException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/**
 * Converts a downloaded audio stream to MP3 by invoking the bundled ffmpeg binary directly.
 *
 * This deliberately bypasses yt-dlp's own `--extract-audio` postprocessor: youtubedl-android
 * ships its ffmpeg binary as "libffmpeg.so" (required by Android's native-library packaging
 * rules), and yt-dlp's ffmpeg/ffprobe sibling-path derivation doesn't recognize that filename
 * pattern, so the postprocessor fails with "ffprobe and ffmpeg not found" even though ffmpeg
 * itself is right there and already works for muxing (video+audio merges use the same binary
 * successfully, since plain stream-copy muxing never needs ffprobe).
 *
 * Every running ffmpeg process is registered under the caller's key so a cancel/pause can kill
 * it immediately ([cancel]); cancelling the calling coroutine kills it too.
 */
object AudioConverter {

    private const val TAG = "AudioConverter"

    /** LAME VBR quality used for "Original quality (VBR)": ~170–210 kbps, chosen by the encoder per frame. */
    const val VBR_QUALITY = "2"

    private val running = ConcurrentHashMap<String, Process>()

    /** Tags written into the MP3 (ID3v2.3, readable by every player). */
    data class Metadata(val title: String?, val artist: String?, val comment: String? = null)

    /**
     * Encoder options for the requested output. A null [bitrateKbps] means "Original quality
     * (VBR)": the encoder decides the bitrate — it must never be replaced by a fixed default.
     */
    fun encoderArgs(bitrateKbps: Int?): List<String> =
        if (bitrateKbps == null) listOf("-q:a", VBR_QUALITY) else listOf("-b:a", "${bitrateKbps}k")

    /**
     * The full ffmpeg command line. With [cover] the image becomes the embedded front cover
     * (re-encoded to JPEG, since the source may be WebP which MP3 players don't show). Pure, so
     * it is unit-tested.
     */
    fun buildCommand(
        ffmpeg: String,
        input: File,
        output: File,
        bitrateKbps: Int?,
        metadata: Metadata?,
        cover: File?,
    ): List<String> = buildList {
        add(ffmpeg)
        add("-y")
        add("-nostdin")
        addAll(listOf("-i", input.absolutePath))
        if (cover != null) addAll(listOf("-i", cover.absolutePath))
        // Audio from the download; the cover (if any) as the only video stream.
        addAll(listOf("-map", "0:a:0"))
        if (cover != null) {
            addAll(listOf("-map", "1:v:0", "-c:v", "mjpeg"))
            addAll(listOf("-disposition:v:0", "attached_pic"))
            addAll(listOf("-metadata:s:v", "title=Album cover", "-metadata:s:v", "comment=Cover (front)"))
        }
        addAll(listOf("-c:a", "libmp3lame"))
        addAll(encoderArgs(bitrateKbps))
        // Keep the source's own tags, then overwrite with ours where we know better.
        addAll(listOf("-map_metadata", "0"))
        metadata?.title?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-metadata", "title=$it")) }
        metadata?.artist?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-metadata", "artist=$it")) }
        metadata?.comment?.takeIf { it.isNotBlank() }?.let { addAll(listOf("-metadata", "comment=$it")) }
        addAll(listOf("-id3v2_version", "3"))
        add(output.absolutePath)
    }

    /** "7.1 (arm64-v8a)"-style version of the bundled ffmpeg, or why it can't run here. */
    suspend fun version(context: Context): String = withContext(Dispatchers.IO) {
        val ffmpeg = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!ffmpeg.exists()) return@withContext "not installed"
        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        runCatching {
            val process = ProcessBuilder(ffmpeg.absolutePath, "-hide_banner", "-version")
                .redirectErrorStream(true)
                .apply { environment()["LD_LIBRARY_PATH"] = libraryPath(context) }
                .start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            val line = out.lineSequence().firstOrNull { it.startsWith("ffmpeg version") }
            when {
                line != null -> line.removePrefix("ffmpeg version ").substringBefore(' ') + " ($abi)"
                out.contains("page size") -> "bundled, can't run on this device (16 KB pages)"
                else -> "bundled (version unavailable)"
            }
        }.getOrElse { "bundled (version unavailable)" }
    }

    private fun libraryPath(context: Context): String {
        val packagesDir = File(File(context.noBackupFilesDir, "youtubedl-android"), "packages")
        return listOf("ffmpeg/usr/lib", "python/usr/lib").joinToString(File.pathSeparator) { File(packagesDir, it).absolutePath }
    }

    /** Kills the conversion registered under [key], if one is running. */
    fun cancel(key: String): Boolean {
        val process = running.remove(key) ?: return false
        process.killNow()
        return true
    }

    /**
     * Converts [inputFile] to MP3 next to it and deletes the input on success. When embedding the
     * cover fails (unreadable image) the conversion is retried once without it, so artwork is a
     * bonus that never costs the user the file.
     */
    suspend fun convertToMp3(
        context: Context,
        inputFile: File,
        bitrateKbps: Int? = null,
        metadata: Metadata? = null,
        cover: File? = null,
        key: String = inputFile.absolutePath,
    ): File = withContext(Dispatchers.IO) {
        val ffmpegBinary = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!ffmpegBinary.exists()) {
            throw ProviderException.ConversionFailed("Bundled ffmpeg binary missing at ${ffmpegBinary.path}")
        }
        val outputFile = File(inputFile.parentFile, inputFile.nameWithoutExtension + ".mp3")
        val usableCover = cover?.takeIf { it.exists() && it.length() > 0 }
        try {
            runFfmpeg(context, buildCommand(ffmpegBinary.absolutePath, inputFile, outputFile, bitrateKbps, metadata, usableCover), outputFile, key)
        } catch (e: ProviderException.ConversionFailed) {
            if (usableCover == null || e.unsupportedDevice) throw e
            Log.w(TAG, "Conversion with cover art failed; retrying without it")
            runFfmpeg(context, buildCommand(ffmpegBinary.absolutePath, inputFile, outputFile, bitrateKbps, metadata, null), outputFile, key)
        }
        inputFile.delete()
        usableCover?.delete()
        outputFile
    }

    private suspend fun runFfmpeg(context: Context, command: List<String>, outputFile: File, key: String) {
        // ffmpeg's own lib dir FIRST, the Python distribution's second. The dynamic linker takes
        // each library from the first dir that has it, so ffmpeg keeps using its own libwebp.so
        // (Python's older copy fails the 16KB page-size alignment check on newer devices), while
        // libc++_shared.so — needed by ffmpeg's librubberband.so but only shipped in the Python
        // package — is still found. Without it ffmpeg can't even start ("CANNOT LINK EXECUTABLE").
        val ldLibraryPath = libraryPath(context)

        coroutineContext.ensureActive()
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .apply { environment()["LD_LIBRARY_PATH"] = ldLibraryPath }
            .start()
        running[key] = process
        val output: String
        val exitCode: Int
        try {
            output = coroutineScope {
                val reader = async(Dispatchers.IO) { process.inputStream.bufferedReader().use { it.readText() } }
                try {
                    reader.await()
                } catch (e: CancellationException) {
                    // Coroutine cancelled (job cancelled / paused): kill ffmpeg right away; that
                    // also closes its stdout so the reader finishes.
                    process.killNow()
                    throw e
                }
            }
            exitCode = runInterruptible { process.waitFor() }
        } finally {
            running.remove(key, process)
        }
        coroutineContext.ensureActive()

        if (exitCode != 0 || !outputFile.exists() || outputFile.length() <= 0) {
            outputFile.delete()
            val tail = output.takeLast(600)
            // Killed by cancel(): SIGKILL → 137 / 9.
            if (exitCode == 137 || exitCode == 9 || exitCode == 143) throw ProviderException.Cancelled()
            Log.w(TAG, "ffmpeg exited with $exitCode: $tail")
            if (tail.contains("No space left", ignoreCase = true)) throw ProviderException.StorageFull(tail)
            // The ffmpeg build bundled by youtubedl-android (0.18.1, the latest) is 4 KB-page
            // aligned; devices running with 16 KB memory pages refuse to load it.
            if (tail.contains("cannot be smaller than system page size")) {
                throw ProviderException.ConversionFailed(tail, unsupportedDevice = true)
            }
            throw ProviderException.ConversionFailed(tail)
        }
    }
}

/** destroyForcibly() needs API 26; on 24/25 destroy() is the strongest available (SIGTERM). */
private fun Process.killNow() {
    if (android.os.Build.VERSION.SDK_INT >= 26) destroyForcibly() else destroy()
}
