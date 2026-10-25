package com.example.mimic.online

import com.example.mimic.online.download.WebmTagger
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class WebmTagProbe {
    @Test fun probe() {
        val f = System.getenv("MIMIC_WEBM")?.let(::File)
        assumeTrue(f != null && f.isFile)
        val src = f!!.readBytes()
        val out = WebmTagger.tag(src, "Mirrors", "Justin Timberlake", "The 20/20 Experience", null, File(f.parentFile, "dl.cover").readBytes(), "image/jpeg")
        println("PROBE same=${out === src} in=${src.size} out=${out.size}")
        File(f.parentFile, "tagged.webm").writeBytes(out)
    }
}
