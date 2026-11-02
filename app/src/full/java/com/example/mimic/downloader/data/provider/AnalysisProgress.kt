package com.example.mimic.downloader.data.provider

import com.example.mimic.downloader.data.model.PlaylistInfo
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Put in the coroutine context of an analysis to see a playlist while yt-dlp is still reading it:
 * [onPartial] gets what is listed so far (a few times a second, never more than the real items),
 * so a long playlist fills in on screen instead of showing a bare spinner. The finished analysis
 * result replaces it. Absent for single links.
 */
class AnalysisProgress(val onPartial: (PlaylistInfo) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AnalysisProgress>
}
