package com.example.mimic.online

import com.example.mimic.online.data.innertube.StreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Manual network probe (skipped unless MIMIC_LIVE_PROBE=1): YouTube search + one stream resolve. */
class LiveSearchProbe {
    @Test fun probe(): Unit = runBlocking {
        assumeTrue(System.getenv("MIMIC_LIVE_PROBE") == "1")
        StreamResolver.ensureNewPipe()
        val yt = ServiceList.YouTube
        val ex = yt.getSearchExtractor(yt.searchQHFactory.fromQuery("justin timberlake", listOf("music_songs"), ""))
        val songs = runCatching { ex.fetchPage(); ex.initialPage.items }.onFailure { println("PROBE music_songs err $it") }.getOrDefault(emptyList())
        println("PROBE music_songs=" + songs.size)
        val ex2 = yt.getSearchExtractor(yt.searchQHFactory.fromQuery("justin timberlake", listOf("videos"), ""))
        ex2.fetchPage()
        val vids = ex2.initialPage.items.filterIsInstance<StreamInfoItem>()
        println("PROBE videos=" + vids.size)
        vids.take(4).forEach { println("PROBE v ${it.name} | ${it.uploaderName} | ${it.duration}s | ${it.url} | thumbs=${it.thumbnails.size}") }
        val id = vids.firstOrNull()?.url?.substringAfter("v=")?.take(11) ?: return@runBlocking
        val r = runCatching { StreamResolver.resolve(id) }
        println("PROBE resolve ok=${r.isSuccess} " + (r.exceptionOrNull()?.toString() ?: r.getOrNull()?.take(60)))
    }
}
