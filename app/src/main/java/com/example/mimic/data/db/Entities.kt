package com.example.mimic.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A song the user marked as a favorite. Keyed by MediaStore song id. */
@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val songId: Long,
    val addedAt: Long,
)

/** One playback event, used for recently-played and play-count aggregation. */
@Entity(
    tableName = "play_history",
    indices = [Index("songId"), Index("playedAt")],
)
data class PlayHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: Long,
    val playedAt: Long,
)

/** A user-created playlist. */
@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/** Membership of a song in a playlist, with manual ordering via [position]. */
@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index("playlistId"), Index("position")],
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: Long,
    val position: Int,
)

/** Aggregated play count for a song (projection, not a stored table). */
data class PlayCount(
    val songId: Long,
    val count: Int,
)
