package com.example.mimic.data.lyrics

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Where a song's lyrics stand (MImic 4.5). Stored as the enum name. */
enum class LyricsStatus {
    /** Timestamped lyrics saved. */
    SYNCED,
    /** Plain (untimed) lyrics saved — never shown as synced. */
    PLAIN,
    /** Searched every source; none has lyrics for this recording. Re-checked after a while. */
    UNAVAILABLE,
    /** A lookup couldn't complete (no connection, provider down). Retried on the next play. */
    FAILED,
    /** Several plausible matches; the user picks one in the lyrics screen. */
    NEEDS_CHOICE,
    /** Queued for a background lookup (after a download, or Find Missing Lyrics). */
    PENDING,
}

/**
 * Saved lyrics, one row per song id (the same id favorites / history use, so a download and its
 * source share a row). [trackKey] records the metadata the lyrics were matched for: a row whose
 * key no longer matches the song (a file replaced under the same id, retagged metadata) is
 * ignored rather than shown — lyrics must never appear for the wrong track.
 */
@Entity(tableName = "lyrics", indices = [Index("status")])
data class LyricsEntity(
    @PrimaryKey val songId: Long,
    val status: String,
    val trackKey: String,
    /** LRC or plain text; null unless SYNCED / PLAIN. */
    val text: String? = null,
    /** Shown in the lyrics screen: "LRCLIB", "Navidrome", "Embedded in the file", "Your lyrics"… */
    val source: String? = null,
    val providerId: String? = null,
    val matchedTitle: String? = null,
    val matchedArtist: String? = null,
    val matchedDurationMs: Long = 0,
    /** The user chose these lyrics (or typed them): never replaced by an automatic lookup. */
    val userChosen: Boolean = false,
    val attempts: Int = 0,
    val updatedAt: Long,
) {
    val lyricsStatus: LyricsStatus get() = runCatching { LyricsStatus.valueOf(status) }.getOrDefault(LyricsStatus.FAILED)
    val hasLyrics: Boolean get() = (lyricsStatus == LyricsStatus.SYNCED || lyricsStatus == LyricsStatus.PLAIN) && !text.isNullOrBlank()
}

data class LyricsStatusCount(val status: String, val n: Int)

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun get(songId: Long): LyricsEntity?

    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    fun observe(songId: Long): Flow<LyricsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LyricsEntity)

    @Query("DELETE FROM lyrics WHERE songId = :songId")
    suspend fun delete(songId: Long)

    @Query("SELECT songId, status, trackKey, NULL AS text, source, providerId, matchedTitle, matchedArtist, matchedDurationMs, userChosen, attempts, updatedAt FROM lyrics")
    suspend fun allWithoutText(): List<LyricsEntity>

    @Query("SELECT status, COUNT(*) AS n FROM lyrics GROUP BY status")
    fun statusCounts(): Flow<List<LyricsStatusCount>>
}
