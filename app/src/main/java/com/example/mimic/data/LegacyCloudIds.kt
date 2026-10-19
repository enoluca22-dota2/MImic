package com.example.mimic.data

import java.security.MessageDigest

/**
 * MImic 4.1 removed the Google Drive / OneDrive sources. Songs that were already DOWNLOADED from
 * them stay on the phone as ordinary offline songs; their remote ids ("onedrive:<a>:<i>",
 * "gdrive:<a>:<i>") and song ids must stay exactly as before so favorites, playlists and history
 * keep pointing at them. This is all that remains of the cloud code.
 */
object LegacyCloudIds {
    private val prefixes = listOf("onedrive:", "gdrive:")

    fun isCloud(remoteId: String?): Boolean =
        remoteId != null && prefixes.any { remoteId.startsWith(it) } && remoteId.count { it == ':' } == 2

    /** Identical to the 3.1–4.0 cloudStableId: 62 bits of SHA-256, in [2^61, 2^62). */
    fun stableId(remoteId: String): Long {
        val d = MessageDigest.getInstance("SHA-256").digest(remoteId.toByteArray())
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return (v and 0x1FFF_FFFF_FFFF_FFFFL) or 0x2000_0000_0000_0000L
    }
}
