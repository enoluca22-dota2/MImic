package com.example.mimic.data.sync

import com.example.mimic.data.cloud.AudioFormats
import com.example.mimic.data.cloud.CloudChanges
import com.example.mimic.data.cloud.CloudItem
import com.example.mimic.data.db.CloudItemEntity

/**
 * Pure planning of a catalog sync (JVM-tested): turns a provider's full listing or incremental
 * changes into rows to write and ids to delete, keyed by the provider's stable item ids.
 *
 * - Rename / move inside the folder: same id, so the row is updated, never duplicated.
 * - Moved out of the folder, deleted or trashed: the row (and, for a folder, everything under it)
 *   is deleted. Downloads live in a different table and are never touched by a sync.
 * - Tags already read for a file are kept as long as its content version is unchanged.
 */
object CloudSyncPlanner {

    data class Plan(
        val upserts: List<CloudItemEntity>,
        val deletes: List<String>,
        /** True = [upserts] is the complete new tree (replace everything). */
        val replaceAll: Boolean,
        /** Folders that newly entered the tree; their existing children must be listed (Drive). */
        val newFolders: List<String>,
        val added: Int,
        val changed: Int,
        val removed: Int,
    )

    fun plan(accountKey: String, root: String, changes: CloudChanges, existing: Map<String, CloudItemEntity>): Plan = when (changes) {
        is CloudChanges.Full -> full(accountKey, root, changes.items, existing)
        is CloudChanges.Incremental -> incremental(accountKey, root, changes, existing)
    }

    fun full(accountKey: String, root: String, items: List<CloudItem>, existing: Map<String, CloudItemEntity>): Plan {
        val latest = items.filter { it.id != root }.associateBy { it.id } // later duplicates win
        val rows = latest.values.map { entity(accountKey, it, existing[it.id]) }
        val added = latest.keys.count { it !in existing }
        val removed = existing.keys.count { it !in latest }
        val changed = latest.values.count { existing[it.id]?.let { old -> old.name != it.name || old.parentId != it.parentId || old.contentVersion != it.contentVersion } == true }
        return Plan(rows, emptyList(), replaceAll = true, newFolders = emptyList(), added = added, changed = changed, removed = removed)
    }

    fun incremental(accountKey: String, root: String, changes: CloudChanges.Incremental, existing: Map<String, CloudItemEntity>): Plan {
        val removedIds = changes.removedIds.toSet()
        val pending = changes.upserts.filter { it.id != root && it.id !in removedIds }.associateBy { it.id }.toMutableMap()

        // Folders currently in the tree: the root plus stored folders not being moved/removed.
        val folders = HashSet<String>().apply {
            add(root)
            existing.values.filter { it.isFolder && it.itemId !in pending && it.itemId !in removedIds }.forEach { add(it.itemId) }
        }
        // Accept items whose parent is in the tree, growing the tree until nothing changes (a new
        // folder and its new children can arrive in any order within one batch).
        val accepted = LinkedHashMap<String, CloudItem>()
        var progress = true
        while (progress) {
            progress = false
            val it = pending.values.iterator()
            while (it.hasNext()) {
                val item = it.next()
                if (item.parentId != null && item.parentId in folders) {
                    accepted[item.id] = item
                    if (item.isFolder) folders += item.id
                    it.remove()
                    progress = true
                }
            }
        }
        // Whatever is left was moved out of the music folder (or never was in it).
        val gone = HashSet<String>()
        for (id in removedIds + pending.keys) if (id in existing) gone += id
        // A removed folder takes its stored descendants with it.
        val children = existing.values.groupBy { it.parentId }
        val stack = ArrayDeque(gone.filter { existing[it]?.isFolder == true })
        while (stack.isNotEmpty()) {
            for (c in children[stack.removeLast()].orEmpty()) {
                if (c.itemId !in accepted && gone.add(c.itemId) && c.isFolder) stack += c.itemId
            }
        }
        val rows = accepted.values.map { entity(accountKey, it, existing[it.id]) }
        return Plan(
            upserts = rows,
            deletes = gone.toList(),
            replaceAll = false,
            newFolders = if (changes.listNewFolders) accepted.values.filter { it.isFolder && it.id !in existing }.map { it.id } else emptyList(),
            added = accepted.keys.count { it !in existing },
            changed = accepted.keys.count { it in existing },
            removed = gone.size,
        )
    }

    /** Row for [item], keeping tags/art read earlier when the file's content didn't change. */
    fun entity(accountKey: String, item: CloudItem, old: CloudItemEntity?): CloudItemEntity {
        val same = old != null && old.contentVersion == item.contentVersion && item.contentVersion != null
        val base = CloudItemEntity(
            accountKey = accountKey, itemId = item.id, parentId = item.parentId, name = item.name,
            isFolder = item.isFolder, mimeType = item.mimeType, size = item.size,
            createdAt = item.createdAt, modifiedAt = item.modifiedAt,
            contentVersion = item.contentVersion, hash = item.hash,
        )
        val facet = item.audio
        return when {
            facet != null -> base.copy(
                title = facet.title, artist = facet.artist, albumArtist = facet.albumArtist, album = facet.album,
                trackNo = facet.trackNo, discNo = facet.discNo, durationMs = facet.durationMs,
                genre = facet.genre, year = facet.year,
                artUri = if (same) old!!.artUri else null,
                tagsVersion = if (same && old!!.tagsVersion?.startsWith(FACET_ART) == true) old.tagsVersion else FACET + item.contentVersion,
            )
            same -> base.copy(
                title = old!!.title, artist = old.artist, albumArtist = old.albumArtist, album = old.album,
                trackNo = old.trackNo, discNo = old.discNo, durationMs = old.durationMs, genre = old.genre,
                year = old.year, artUri = old.artUri, tagsVersion = old.tagsVersion,
            )
            else -> base
        }
    }

    /** Audio whose embedded tags haven't been read for its current content yet. */
    fun needsTagRead(row: CloudItemEntity): Boolean =
        !row.isFolder && AudioFormats.isPlayable(row.name) && row.contentVersion != null &&
            row.tagsVersion != row.contentVersion && row.tagsVersion?.startsWith(FACET) != true &&
            row.tagsVersion?.startsWith(FACET_ART) != true

    /** A cover.jpg / folder.png that hasn't been fetched for its current content. */
    fun needsFolderCover(row: CloudItemEntity): Boolean =
        !row.isFolder && AudioFormats.isFolderCover(row.name) && (row.artUri == null || row.tagsVersion != row.contentVersion)

    const val FACET = "facet:"
    const val FACET_ART = "facetart:"
}
