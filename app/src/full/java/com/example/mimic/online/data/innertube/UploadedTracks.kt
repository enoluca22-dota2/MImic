/*
 * This file is part of MImic and is derived from BitChord
 * (https://github.com/kushagrasinghx/BitChord, file shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/innertube/UploadedTracks.kt),
 * Copyright (C) BitChord contributors, licensed under the GNU General Public License v3.0.
 * Modified for MImic (package renamed to com.example.mimic.online, trimmed and adapted to
 * MImic's player and storage). Distributed under GPL-3.0; see LICENSE and THIRD_PARTY_NOTICES.md.
 */
package com.example.mimic.online.data.innertube

import java.util.concurrent.ConcurrentHashMap

/** Video ids known to be the signed-in user's own uploads, which need an upload-hinted request. */
object UploadedTracks {

    private const val MAX_REMEMBERED = 20_000

    private val ids = ConcurrentHashMap.newKeySet<String>()

    fun mark(videoId: String) {
        if (videoId.isBlank()) return
        if (ids.size >= MAX_REMEMBERED) ids.clear()
        ids.add(videoId)
    }

    fun contains(videoId: String): Boolean = videoId in ids

    /** Forgets everything — an account switch is a different private library. */
    fun clear() {
        ids.clear()
    }
}
