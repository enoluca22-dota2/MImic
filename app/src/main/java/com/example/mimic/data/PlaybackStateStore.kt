package com.example.mimic.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * The play queue as it was when the app last ran (MImic 3.2): song ids in queue order, the current
 * index and the position in that song. Only ids are stored — never stream URLs, which carry server
 * auth tokens — and they are resolved against the unified library on restore, so a song that has
 * since been deleted (or whose server is offline and isn't downloaded) is simply skipped.
 */
data class QueueSnapshot(val ids: List<Long>, val index: Int, val positionMs: Long) {

    /** The id that was playing, or null when the snapshot is empty / out of range. */
    val currentId: Long? get() = ids.getOrNull(index)

    /**
     * Where to resume in [available] (the queue after unknown songs were dropped): the saved song
     * at the saved position when it survived, else the next surviving song after it, from 0 ms.
     */
    fun resumePoint(available: List<Long>): Pair<Int, Long>? {
        if (available.isEmpty()) return null
        val current = currentId
        val exact = available.indexOf(current)
        if (exact >= 0) return exact to positionMs.coerceAtLeast(0L)
        val survivorsAfter = ids.drop(index + 1).firstOrNull { it in available }
        return (survivorsAfter?.let(available::indexOf) ?: 0) to 0L
    }

    companion object {
        fun encodeIds(ids: List<Long>): String = ids.joinToString(",")
        fun decodeIds(csv: String?): List<Long> =
            csv?.split(',')?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()
    }
}

private val Context.playbackStore by preferencesDataStore(name = "playback_state")

class PlaybackStateStore(private val context: Context) {

    private object Keys {
        val IDS = stringPreferencesKey("queue_ids")
        val INDEX = intPreferencesKey("queue_index")
        val POSITION = longPreferencesKey("queue_position_ms")
    }

    suspend fun read(): QueueSnapshot? {
        val prefs = context.playbackStore.data.first()
        val ids = QueueSnapshot.decodeIds(prefs[Keys.IDS])
        if (ids.isEmpty()) return null
        return QueueSnapshot(ids, prefs[Keys.INDEX] ?: 0, prefs[Keys.POSITION] ?: 0L)
    }

    /** The queue changed (items added, removed, moved, replaced). Empty clears it. */
    suspend fun saveQueue(ids: List<Long>, index: Int, positionMs: Long) {
        context.playbackStore.edit {
            it[Keys.IDS] = QueueSnapshot.encodeIds(ids)
            it[Keys.INDEX] = index
            it[Keys.POSITION] = positionMs
        }
    }

    suspend fun savePosition(index: Int, positionMs: Long) {
        context.playbackStore.edit {
            it[Keys.INDEX] = index
            it[Keys.POSITION] = positionMs
        }
    }
}
