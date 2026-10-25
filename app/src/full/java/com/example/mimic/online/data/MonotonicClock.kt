/*
 * This file is part of MImic and is derived from BitChord
 * (https://github.com/kushagrasinghx/BitChord, file shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/MonotonicClock.kt),
 * Copyright (C) BitChord contributors, licensed under the GNU General Public License v3.0.
 * Modified for MImic (package renamed to com.example.mimic.online, trimmed and adapted to
 * MImic's player and storage). Distributed under GPL-3.0; see LICENSE and THIRD_PARTY_NOTICES.md.
 */
package com.example.mimic.online.data

/**
 * Milliseconds on a monotonic clock. The phone installs
 * `SystemClock.elapsedRealtime`, which keeps counting through deep sleep; the
 * desktop keeps the JVM's own.
 */
object MonotonicClock {
    private val origin = System.nanoTime()

    @Volatile
    var nowMs: () -> Long = { (System.nanoTime() - origin) / 1_000_000L }
}
