package com.example.mimic

import com.example.mimic.data.MusicProvider
import com.example.mimic.data.SongSource
import com.example.mimic.data.SourceFilter
import com.example.mimic.data.UnifiedLibrary
import com.example.mimic.data.cloud.AudioFormats
import com.example.mimic.data.cloud.CloudAudioTags
import com.example.mimic.data.cloud.CloudCatalogMapper
import com.example.mimic.data.cloud.CloudChanges
import com.example.mimic.data.cloud.CloudItem
import com.example.mimic.data.cloud.CloudProviderType
import com.example.mimic.data.cloud.CloudRef
import com.example.mimic.data.cloud.cloudStableId
import com.example.mimic.data.db.CloudAccountEntity
import com.example.mimic.data.db.CloudItemEntity
import com.example.mimic.data.server.remoteStableId
import com.example.mimic.data.server.songIdForRemote
import com.example.mimic.data.sync.CloudSyncPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MImic 3.1 cloud library: identity, metadata fallbacks, sync planning, source filters. */
class CloudLibraryTest {

    private val acct = CloudAccountEntity(
        accountKey = "onedrive:A1", provider = "onedrive", accountId = "A1", displayName = "Me", email = null,
        folderId = "ROOT", folderName = "MImic Music", syncToken = null, lastSyncAt = 0, lastError = null, connectedAt = 0,
    )

    private fun folder(id: String, name: String, parent: String) =
        CloudItemEntity("onedrive:A1", id, parent, name, true, null, 0, 0, 0, null, null)

    private fun file(id: String, name: String, parent: String, hash: String? = "sha1:$id", created: Long = 1000, cv: String? = "v1") =
        CloudItemEntity("onedrive:A1", id, parent, name, false, null, 100, created, created, cv, hash)

    private fun item(id: String, name: String, parent: String?, folder: Boolean = false, cv: String? = "v1", audio: CloudAudioTags? = null) =
        CloudItem(id, name, parent, folder, null, 10, 0, 0, cv, null, audio)

    // ---- identity ----

    @Test fun cloud_ref_round_trips_and_never_contains_tokens_or_urls() {
        val ref = CloudRef(CloudProviderType.GOOGLE_DRIVE, "perm:1/x", "file/Id 9")
        assertEquals(ref, CloudRef.parse(ref.remoteId))
        assertEquals(ref, CloudRef.fromPlayUri(ref.playUri))
        assertTrue(ref.playUri.startsWith("mimic-cloud://gdrive/"))
        assertEquals("gdrive:perm:1/x", ref.accountKey)
        assertNull(CloudRef.parse("3xSn49LpCsEEkGYef21M5f")) // a Navidrome id is not a cloud ref
    }

    @Test fun same_file_id_in_two_accounts_gets_two_identities() {
        val a = CloudRef(CloudProviderType.ONEDRIVE, "A1", "ITEM").remoteId
        val b = CloudRef(CloudProviderType.ONEDRIVE, "B2", "ITEM").remoteId
        val g = CloudRef(CloudProviderType.GOOGLE_DRIVE, "A1", "ITEM").remoteId
        assertEquals(3, setOf(cloudStableId(a), cloudStableId(b), cloudStableId(g)).size)
    }

    @Test fun cloud_ids_live_in_their_own_range_and_navidrome_ids_are_unchanged() {
        val c = songIdForRemote(CloudRef(CloudProviderType.ONEDRIVE, "A1", "X").remoteId)
        assertTrue(c >= 0x2000_0000_0000_0000L && c < 0x4000_0000_0000_0000L)
        assertEquals(remoteStableId("navi123"), songIdForRemote("navi123")) // old favorites still match
        val ids = (0 until 20_000).map { cloudStableId("onedrive:A1:item$it") }.toSet()
        assertEquals(20_000, ids.size)
    }

    // ---- catalog mapping ----

    @Test fun folder_layout_gives_artist_and_album_when_tags_are_missing() {
        val items = listOf(
            folder("F1", "Blue Band", "ROOT"), folder("F2", "Blue Album", "F1"),
            file("S1", "01 Ocean Floor.flac", "F2"),
            folder("F3", "Singles", "ROOT"), file("S2", "Night Drive.mp3", "F3"),
            file("S3", "Loose.mp3", "ROOT"),
        )
        val songs = CloudCatalogMapper.map(acct, items).songs.associateBy { it.title }
        val ocean = songs.getValue("Ocean Floor")
        assertEquals("Blue Band", ocean.artist)
        assertEquals("Blue Album", ocean.album)
        assertEquals(1, ocean.trackNo)
        // One folder level is an album, never guessed to be an artist.
        assertEquals("Singles", songs.getValue("Night Drive").album)
        assertEquals("Unknown Artist", songs.getValue("Night Drive").artist)
        assertEquals("Unknown Album", songs.getValue("Loose").album)
        assertTrue(songs.values.all { it.source == SongSource.CLOUD && it.provider == MusicProvider.ONEDRIVE })
    }

    @Test fun embedded_tags_win_over_folders_and_file_names() {
        val tagged = file("S1", "track.mp3", "F2").copy(title = "Real Title", artist = "Real Artist", album = "Real Album", trackNo = 4)
        val s = CloudCatalogMapper.map(acct, listOf(folder("F1", "X", "ROOT"), folder("F2", "Y", "F1"), tagged)).songs.single()
        assertEquals("Real Title", s.title)
        assertEquals("Real Artist", s.artist)
        assertEquals("Real Album", s.album)
        assertEquals(4, s.trackNo)
    }

    @Test fun unsupported_drm_and_non_audio_files_are_skipped_and_counted() {
        val items = listOf(file("a", "Song.mp3", "ROOT"), file("b", "Old.wma", "ROOT"), file("c", "Itunes.m4p", "ROOT"), file("d", "notes.txt", "ROOT"), file("e", "cover.jpg", "ROOT"))
        val c = CloudCatalogMapper.map(acct, items)
        assertEquals(listOf("Song"), c.songs.map { it.title })
        assertEquals(2, c.unsupportedCount)
        assertTrue(AudioFormats.isPlayable("x.FLAC") && AudioFormats.isPlayable("x.m4a") && AudioFormats.isPlayable("x.ogg"))
    }

    @Test fun identical_uploads_show_once_but_different_files_with_same_name_do_not_merge() {
        val items = listOf(
            file("a", "Night Drive.mp3", "ROOT", hash = "sha1:same", created = 1),
            file("b", "Night Drive (1).mp3", "ROOT", hash = "sha1:same", created = 2),
            folder("F", "Other", "ROOT"),
            file("c", "Night Drive.mp3", "F", hash = "sha1:different"),
        )
        val c = CloudCatalogMapper.map(acct, items)
        assertEquals(2, c.songs.size)
        assertEquals(1, c.duplicateCount)
        assertTrue(c.songs.any { it.remoteId!!.endsWith(":a") }) // the first upload is kept
    }

    @Test fun files_outside_the_music_folder_are_not_songs() {
        val c = CloudCatalogMapper.map(acct, listOf(file("x", "Elsewhere.mp3", "SOMEWHERE_ELSE")))
        assertTrue(c.songs.isEmpty())
    }

    @Test fun album_keys_are_per_account_and_folder_covers_apply_to_the_album() {
        val items = listOf(
            folder("F1", "A", "ROOT"), folder("F2", "Al", "F1"),
            file("s1", "1.mp3", "F2"), file("s2", "2.mp3", "F2"),
            file("img", "cover.jpg", "F2").copy(artUri = "file:///art/x.jpg"),
        )
        val songs = CloudCatalogMapper.map(acct, items).songs
        assertEquals(1, songs.map { it.albumKey }.toSet().size)
        assertTrue(songs.all { it.albumArtUri == "file:///art/x.jpg" })
        assertTrue(songs[0].albumKey.startsWith("cloud:onedrive:A1:album:"))
        val other = CloudCatalogMapper.map(acct.copy(accountKey = "onedrive:B2", accountId = "B2"), items.map { it.copy(accountKey = "onedrive:B2") }).songs
        assertNotEquals(songs[0].albumKey, other[0].albumKey)
    }

    @Test fun file_name_track_numbers_dont_eat_artist_names() {
        assertEquals(3 to "Song", CloudCatalogMapper.fileTitle("03 - Song.mp3"))
        assertEquals(3 to "Song", CloudCatalogMapper.fileTitle("03 Song.mp3"))
        assertEquals(null to "50 Cent - In Da Club", CloudCatalogMapper.fileTitle("50 Cent - In Da Club.mp3"))
        assertEquals(null to "2000s RnB", CloudCatalogMapper.fileTitle("2000s RnB.mp3"))
    }

    // ---- sync planning ----

    @Test fun full_sync_replaces_tree_and_keeps_tags_for_unchanged_content() {
        val old = file("s1", "a.mp3", "ROOT", cv = "v1").copy(title = "Tagged", artUri = "file:///a.jpg", tagsVersion = "v1")
        val plan = CloudSyncPlanner.full("onedrive:A1", "ROOT", listOf(item("ROOT", "MImic", "P", folder = true), item("s1", "a.mp3", "ROOT"), item("s2", "b.mp3", "ROOT")), mapOf("s1" to old, "gone" to file("gone", "x.mp3", "ROOT")))
        assertTrue(plan.replaceAll)
        assertEquals(setOf("s1", "s2"), plan.upserts.map { it.itemId }.toSet()) // root folder itself not stored
        assertEquals("Tagged", plan.upserts.first { it.itemId == "s1" }.title)
        assertEquals(1, plan.added)
        assertEquals(1, plan.removed)
    }

    @Test fun rename_updates_the_same_row_and_changed_content_is_reread() {
        val old = file("s1", "a.mp3", "ROOT", cv = "v1").copy(title = "Tagged", tagsVersion = "v1")
        val renamed = CloudSyncPlanner.incremental("k", "ROOT", CloudChanges.Incremental(listOf(item("s1", "renamed.mp3", "ROOT", cv = "v1")), emptyList(), "t2"), mapOf("s1" to old))
        assertEquals(listOf("s1"), renamed.upserts.map { it.itemId })
        assertEquals("renamed.mp3", renamed.upserts[0].name)
        assertEquals("Tagged", renamed.upserts[0].title)
        assertFalse(CloudSyncPlanner.needsTagRead(renamed.upserts[0]))
        assertEquals(0, renamed.added)

        val edited = CloudSyncPlanner.incremental("k", "ROOT", CloudChanges.Incremental(listOf(item("s1", "a.mp3", "ROOT", cv = "v2")), emptyList(), "t2"), mapOf("s1" to old))
        assertNull(edited.upserts[0].title)
        assertTrue(CloudSyncPlanner.needsTagRead(edited.upserts[0]))
    }

    @Test fun drive_wide_changes_are_filtered_to_the_music_folder() {
        val existing = mapOf("F" to folder("F", "Album", "ROOT"), "s1" to file("s1", "in.mp3", "F"))
        val changes = CloudChanges.Incremental(
            upserts = listOf(
                item("new", "New.mp3", "F"),                       // uploaded into the folder
                item("NF", "New Folder", "ROOT", folder = true),   // new sub-folder...
                item("nf1", "Inside.mp3", "NF"),                   // ...and its file (any order)
                item("out", "Elsewhere.mp3", "OTHER"),             // unrelated part of the drive
                item("s1", "in.mp3", "OTHER"),                     // moved OUT of the music folder
            ),
            removedIds = emptyList(), newToken = "t", listNewFolders = true,
        )
        val plan = CloudSyncPlanner.incremental("k", "ROOT", changes, existing)
        assertEquals(setOf("new", "NF", "nf1"), plan.upserts.map { it.itemId }.toSet())
        assertEquals(listOf("s1"), plan.deletes)
        assertEquals(listOf("NF"), plan.newFolders)
    }

    @Test fun deleting_a_folder_removes_everything_under_it() {
        val existing = mapOf(
            "F" to folder("F", "A", "ROOT"), "G" to folder("G", "B", "F"),
            "s1" to file("s1", "1.mp3", "F"), "s2" to file("s2", "2.mp3", "G"), "keep" to file("keep", "k.mp3", "ROOT"),
        )
        val plan = CloudSyncPlanner.incremental("k", "ROOT", CloudChanges.Incremental(emptyList(), listOf("F"), "t"), existing)
        assertEquals(setOf("F", "G", "s1", "s2"), plan.deletes.toSet())
    }

    @Test fun onedrive_audio_facet_fills_tags_without_reading_the_file() {
        val e = CloudSyncPlanner.entity("k", item("s", "x.mp3", "ROOT", audio = CloudAudioTags(title = "T", artist = "A", album = "Al", trackNo = 2, durationMs = 1000)), null)
        assertEquals("T", e.title)
        assertFalse(CloudSyncPlanner.needsTagRead(e))
    }

    // ---- unified library ----

    @Test fun downloaded_cloud_song_replaces_streamed_entry_and_filters_per_provider() {
        val ref = CloudRef(CloudProviderType.GOOGLE_DRIVE, "P", "f1")
        val streamed = CloudCatalogMapper.map(acct.copy(accountKey = "gdrive:P", provider = "gdrive", accountId = "P"), listOf(file("f1", "Song.mp3", "ROOT").copy(accountKey = "gdrive:P"))).songs.single()
        assertEquals(ref.remoteId, streamed.remoteId)
        val download = streamed.copy(uri = "file:///dl/x.mp3", source = SongSource.DOWNLOADED, albumKey = "stale")
        val merged = UnifiedLibrary.merge(emptyList(), listOf(streamed), listOf(download))
        assertEquals(1, merged.size)
        assertEquals("file:///dl/x.mp3", merged[0].uri)
        assertEquals(streamed.albumKey, merged[0].albumKey)
        assertEquals(merged, UnifiedLibrary.filter(merged, SourceFilter.GOOGLE_DRIVE))
        assertEquals(merged, UnifiedLibrary.filter(merged, SourceFilter.DOWNLOADED))
        assertTrue(UnifiedLibrary.filter(merged, SourceFilter.ONEDRIVE).isEmpty())
        assertTrue(UnifiedLibrary.filter(merged, SourceFilter.NAVIDROME).isEmpty())
        // Cloud temporarily unavailable (no catalog): the download is still there.
        assertEquals(1, UnifiedLibrary.merge(emptyList(), emptyList(), listOf(download)).size)
    }
}
