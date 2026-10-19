package com.example.mimic.downloader

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Standard edition: no media downloader (no yt-dlp, no GPL-licensed youtubedl-android). Same API
 * as the full edition's `Downloader` so the rest of MImic compiles unchanged.
 */
object Downloader {
    const val AVAILABLE = false

    fun init(context: Context) = Unit

    fun completions(context: Context): Flow<Unit> = emptyFlow()

    fun sharedText(intent: Intent?): String? = null

    @Composable
    fun Tab(
        contentPadding: PaddingValues,
        sharedText: String?,
        onSharedTextConsumed: () -> Unit,
        playAudio: (uri: String, title: String, artist: String?) -> Unit,
        pauseMusic: () -> Unit,
        showMessage: (String) -> Unit,
    ) {
        Text(
            "The media downloader is part of MImic's full edition (GitHub releases). This edition plays your music only.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(contentPadding).padding(24.dp),
        )
    }
}
