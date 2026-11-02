package com.example.mimic.downloader.data.provider

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Put in the coroutine context of an analysis to hear how many playlist items yt-dlp has listed
 * so far ([onFound] gets the running count), so a long playlist shows progress instead of a bare
 * spinner. Absent for single links.
 */
class AnalysisProgress(val onFound: (Int) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AnalysisProgress>
}
