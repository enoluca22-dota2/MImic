/*
 * This file is part of MImic and is derived from BitChord
 * (https://github.com/kushagrasinghx/BitChord, file shared/src/jvmSharedMain/kotlin/com/music/bitchord/data/LikeState.kt),
 * Copyright (C) BitChord contributors, licensed under the GNU General Public License v3.0.
 * Modified for MImic (package renamed to com.example.mimic.online, trimmed and adapted to
 * MImic's player and storage). Distributed under GPL-3.0; see LICENSE and THIRD_PARTY_NOTICES.md.
 */
package com.example.mimic.online.data

import com.example.mimic.online.data.model.LikeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ratings changed during this app session, shared by the UI and playback service.
 *
 * The library remains the source for ratings that were already known at load time;
 * these overrides win over it so a notification tap and a player-screen tap paint
 * the same result immediately.
 */
object LikeState {
    private val _overrides = MutableStateFlow<Map<String, LikeStatus>>(emptyMap())
    val overrides: StateFlow<Map<String, LikeStatus>> = _overrides.asStateFlow()

    fun set(videoId: String, status: LikeStatus) {
        _overrides.value += (videoId to status)
    }

    /**
     * Records a rating read off a track's own menu, but only when the menu
     * actually states one. A missing like button or an absent rating (null)
     * must never overwrite what this session already knows — kept null
     * rather than INDIFFERENT so the two stay distinct — and an explicit
     * override already made this session always wins.
     */
    fun rememberStated(videoId: String, stated: LikeStatus?) {
        if (stated != null && stated != LikeStatus.INDIFFERENT && videoId !in _overrides.value) {
            set(videoId, stated)
        }
    }

    /** Seeds only ratings not already changed explicitly during this session. */
    fun seedLiked(videoIds: Set<String>) {
        if (videoIds.isEmpty()) return
        val next = _overrides.value.toMutableMap()
        videoIds.forEach { next.putIfAbsent(it, LikeStatus.LIKE) }
        if (next != _overrides.value) _overrides.value = next
    }

    fun clear() {
        _overrides.value = emptyMap()
    }
}
