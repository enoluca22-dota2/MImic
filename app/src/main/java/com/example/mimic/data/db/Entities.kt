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
    // ---- MImic 3.0 (v5); nullable/defaulted so rows from older versions stay valid ----
    /** Server album id, so a download groups with the same album streamed from the server. */
    val albumId: String? = null,
    /** "user@server" that downloaded it (null = downloaded before 3.0). */
    val accountKey: String? = null,
    /** Verified size on disk when the download completed (0 = unknown, pre-3.0). */
    val sizeBytes: Long = 0L,
)

/** A favorited album or artist (MImic 3.0). [groupKey] is the source-aware Song.albumKey / artistKey. */
@Entity(tableName = "favorite_groups", primaryKeys = ["kind", "groupKey"])
data class FavoriteGroupEntity(
    val kind: String, // KIND_ALBUM / KIND_ARTIST
    val groupKey: String,
    val name: String,
    val subtitle: String,
    val addedAt: Long,
) {
    companion object {
        const val KIND_ALBUM = "ALBUM"
        const val KIND_ARTIST = "ARTIST"
    }
}

/**
 * A favorite change that still has to reach the server (star/unstar failed, e.g. offline). The
 * latest intent per (song, account) wins; it is flushed when that account is connected again.
 */
@Entity(tableName = "pending_server_ops", primaryKeys = ["remoteId", "accountKey"])
data class PendingStarEntity(
    val remoteId: String,
    val accountKey: String,
    val star: Boolean,
    val createdAt: Long,
)

/** Last time each song was played (projection). */
data class LastPlayed(
    val songId: Long,
    val playedAt: Long,
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

/**
 * A connected Google Drive / OneDrive account (MImic 3.1). Holds no tokens — those stay in the
 * provider SDK's own encrypted cache (MSAL / Google Play services). [accountKey] =
 * "<provider>:<accountId>" and scopes every cloud row, download and sync token to this account.
 */
@Entity(tableName = "cloud_accounts")
data class CloudAccountEntity(
    @PrimaryKey val accountKey: String,
    val provider: String, // CloudProviderType.id
    val accountId: String,
    val displayName: String,
    val email: String?,
    /** The one music folder the user picked; null until chosen. */
    val folderId: String?,
    val folderName: String?,
    /** OneDrive delta link / Google Drive changes page token; null = next sync is a full listing. */
    val syncToken: String?,
    val lastSyncAt: Long,
    val lastError: String?,
    val connectedAt: Long,
    /** Test-only API endpoint override (debug mock server); null = the real provider API. */
    val apiBase: String? = null,
)

/**
 * One file or folder inside an account's music folder, keyed by the provider's stable item id —
 * a rename or move updates this row instead of creating a new one. Tag fields are filled from the
 * provider's own audio metadata (OneDrive) or by reading the file's tags ([tagsVersion] records
 * which file version they were read from, so changed files are re-read and unchanged ones never).
 */
@Entity(
    tableName = "cloud_items",
    primaryKeys = ["accountKey", "itemId"],
    indices = [Index("accountKey"), Index("parentId")],
)
data class CloudItemEntity(
    val accountKey: String,
    val itemId: String,
    val parentId: String?,
    val name: String,
    val isFolder: Boolean,
    val mimeType: String?,
    val size: Long,
    val createdAt: Long,
    val modifiedAt: Long,
    /** Changes whenever the file content changes (OneDrive cTag, Drive md5/version). */
    val contentVersion: String?,
    /** "sha1:<hex>" / "md5:<hex>" for download integrity checks; null when the provider has none. */
    val hash: String?,
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val trackNo: Int? = null,
    val discNo: Int? = null,
    val durationMs: Long = 0L,
    val genre: String? = null,
    val year: Int? = null,
    /** Local file:// artwork extracted/fetched for this track, if any. */
    val artUri: String? = null,
    val tagsVersion: String? = null,
)
