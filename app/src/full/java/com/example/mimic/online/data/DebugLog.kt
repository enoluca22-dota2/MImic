/*
 * This file is part of MImic and is derived from BitChord
 * (https://github.com/kushagrasinghx/BitChord, file shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/DebugLog.kt),
 * Copyright (C) BitChord contributors, licensed under the GNU General Public License v3.0.
 * Modified for MImic (package renamed to com.example.mimic.online, trimmed and adapted to
 * MImic's player and storage). Distributed under GPL-3.0; see LICENSE and THIRD_PARTY_NOTICES.md.
 */
package com.example.mimic.online.data

/**
 * Debug logging for code both applications run.
 *
 * Silent until an application installs a [sink]: the phone routes it to
 * logcat on debug builds only, the desktop to its track log.
 */
object DebugLog {
    fun interface Sink {
        fun log(level: Char, tag: String, message: String, error: Throwable?)
    }

    @Volatile
    var sink: Sink? = null

    fun d(tag: String, message: String) = sink?.log('D', tag, message, null) ?: Unit

    fun i(tag: String, message: String) = sink?.log('I', tag, message, null) ?: Unit

    fun w(tag: String, message: String) = sink?.log('W', tag, message, null) ?: Unit

    fun w(tag: String, message: String, error: Throwable) = sink?.log('W', tag, message, error) ?: Unit

    fun e(tag: String, message: String) = sink?.log('E', tag, message, null) ?: Unit

    fun e(tag: String, message: String, error: Throwable) = sink?.log('E', tag, message, error) ?: Unit
}
