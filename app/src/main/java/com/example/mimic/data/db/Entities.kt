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

/** A track downloaded from the music server for offline playback. */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val remoteId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val fileUri: String,
    val coverArtUri: String?,
    val downloadedAt: Long,
)

/** A user-created/edited theme. Built-in presets live in code; these are editable. */
@Entity(tableName = "custom_themes")
data class CustomThemeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val isDark: Boolean,
    val primary: Long,
    val secondary: Long,
    val background: Long,
    val surface: Long,
    val surfaceElevated: Long,
    val textPrimary: Long,
    val textSecondary: Long,
    val createdAt: Long,
    /** ThemeStyle as flat JSON (MImic 2.9+). Null for themes saved before 2.9 => default style. */
    val styleJson: String? = null,
)
