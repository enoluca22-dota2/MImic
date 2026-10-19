package com.example.mimic.downloader

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.mimic.downloader.core.UrlValidator
import com.example.mimic.downloader.ui.DownloadedMediaOpener
import com.example.mimic.downloader.ui.DownloadsTab
import com.example.mimic.downloader.ui.VideoPlayerDialog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The media downloader as the rest of MImic sees it. Each edition has its own `Downloader`
 * object with this exact API: this one (full) is the real downloader merged from ENAGELYUCA;
 * src/standard has a stub that says the edition doesn't include it.
 */
object Downloader {
    const val AVAILABLE = true

    /** Start-up: unpack yt-dlp on first run and resume any queue left from before (background). */
    fun init(context: Context) {
        DownloaderContainer.get(context)
    }

    /** Emits after every finished download, so MImic can rescan its music library. */
    fun completions(context: Context): Flow<Unit> = DownloaderContainer.get(context).completed.map { }

    /** A YouTube/TikTok link shared or opened into MImic, or null. */
    fun sharedText(intent: Intent?): String? {
        if (intent == null) return null
        val text = when (intent.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return null
        return text.takeIf { UrlValidator.extractAllUrls(it).isNotEmpty() }
    }

    @Composable
    fun Tab(
        contentPadding: PaddingValues,
        sharedText: String?,
        onSharedTextConsumed: () -> Unit,
        playAudio: (uri: String, title: String, artist: String?) -> Unit,
        pauseMusic: () -> Unit,
        showMessage: (String) -> Unit,
    ) {
        var video by remember { mutableStateOf<String?>(null) }
        DownloadsTab(
            contentPadding = contentPadding,
            sharedText = sharedText,
            onSharedTextConsumed = onSharedTextConsumed,
            opener = DownloadedMediaOpener { uri, title, artist, isVideo ->
                if (isVideo) {
                    pauseMusic()
                    video = uri
                } else {
                    playAudio(uri, title, artist)
                }
            },
            showMessage = showMessage,
        )
        video?.let { VideoPlayerDialog(it) { video = null } }
    }

    /** Settings → Advanced → Download Diagnostics. */
    @Composable
    fun DiagnosticsScreen(onBack: () -> Unit) = com.example.mimic.downloader.diagnostics.DiagnosticsScreen(onBack)
}
