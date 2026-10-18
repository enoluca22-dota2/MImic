package com.example.mimic.data.cloud

import com.example.mimic.data.db.CloudAccountEntity

/**
 * One built-in cloud provider's API (MImic 3.1): folder browsing, catalog listing / incremental
 * changes, and stream resolution. Implementations are stateless apart from short-lived caches;
 * tokens come from [CloudTokens] so authentication stays out of URLs, songs and the database.
 */
interface CloudMusicSource {
    val provider: CloudProviderType

    /** Sub-folders of [parentId] (null = the drive root), for choosing the music folder. */
    suspend fun listFolders(account: CloudAccountEntity, parentId: String?): List<CloudFolder>

    /**
     * Catalog changes since [CloudAccountEntity.syncToken]: a full listing of the music folder
     * when the token is null or no longer valid, otherwise only what changed.
     */
    suspend fun changes(account: CloudAccountEntity): CloudChanges

    /** Every item under [folderId] (used when a folder is moved into the music folder). */
    suspend fun listTree(account: CloudAccountEntity, folderId: String): List<CloudItem>

    /**
     * Where to read the file's bytes right now. Blocking: called on Media3's loader thread or an
     * IO dispatcher. [forceRefresh] drops cached URLs/tokens after a 401/403/404/410.
     */
    fun resolveStream(account: CloudAccountEntity, itemId: String, forceRefresh: Boolean): StreamTarget

    /** Small artwork for a track (OneDrive thumbnails); null when the provider has none. */
    suspend fun thumbnail(account: CloudAccountEntity, itemId: String): ByteArray? = null
}

sealed interface CloudChanges {
    val newToken: String?

    /** Everything currently inside the music folder; anything not listed is gone. */
    data class Full(val items: List<CloudItem>, override val newToken: String?) : CloudChanges

    /** Upserts may include items outside the folder (Drive changes are drive-wide). */
    data class Incremental(
        val upserts: List<CloudItem>,
        val removedIds: List<String>,
        override val newToken: String?,
        /** Drive only: folders that newly appear need their existing children listed. */
        val listNewFolders: Boolean = false,
    ) : CloudChanges
}

/** A readable location for a file. [headers] may carry Authorization; never log either. */
class StreamTarget(val url: String, val headers: Map<String, String>, val expiresAtMs: Long = Long.MAX_VALUE) {
    override fun toString() = "StreamTarget(<redacted>)"
}

/** Bearer tokens per account, from the provider SDK's encrypted cache (or the debug mock). */
interface CloudTokens {
    /** Blocking; throws [CloudException.AuthRequired] if the user has to sign in again. */
    fun token(account: CloudAccountEntity, forceRefresh: Boolean): String
}
