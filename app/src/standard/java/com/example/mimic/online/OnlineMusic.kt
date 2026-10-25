package com.example.mimic.online

import android.content.Context
import androidx.media3.datasource.DataSource
import com.example.mimic.data.online.OnlineCollection
import com.example.mimic.data.online.OnlineCollectionPage
import com.example.mimic.data.online.OnlineDownloadState
import com.example.mimic.data.online.OnlineSearchFilter
import com.example.mimic.data.online.OnlineSearchResults
import com.example.mimic.data.online.OnlineTrack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Standard edition: no online music (no YouTube / GPL stream-resolution code). Same API as the
 * full edition's `OnlineMusic` so the rest of MImic compiles unchanged; the UI hides it.
 */
object OnlineMusic {
    const val AVAILABLE = false
    private val unavailable = UnsupportedOperationException("Online music is part of MImic's full edition")

    fun init(context: Context) = Unit
    suspend fun search(query: String, filter: OnlineSearchFilter): Result<OnlineSearchResults> = Result.failure(unavailable)
    suspend fun searchMore(token: String): Result<OnlineSearchResults> = Result.failure(unavailable)
    suspend fun suggestions(input: String): List<String> = emptyList()
    suspend fun collection(c: OnlineCollection): Result<OnlineCollectionPage> = Result.failure(unavailable)
    fun wrap(upstream: DataSource.Factory): DataSource.Factory = upstream
    fun download(context: Context, track: OnlineTrack) = Unit
    fun cancelDownload(context: Context, videoId: String) = Unit
    fun downloadStates(context: Context): Flow<Map<String, OnlineDownloadState>> = flowOf(emptyMap())
    val youTubeMusicConnected: Boolean get() = false
}
