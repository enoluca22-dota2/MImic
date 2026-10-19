package com.example.mimic.downloader.core

import android.webkit.MimeTypeMap

object MimeTypes {
    fun forExtension(extension: String): String {
        val clean = extension.trim('.').lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(clean)
            ?: when (clean) {
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "opus" -> "audio/opus"
                "srt" -> "application/x-subrip"
                "vtt" -> "text/vtt"
                else -> "application/octet-stream"
            }
    }
}
