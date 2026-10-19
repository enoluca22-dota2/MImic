package com.example.mimic

import com.example.mimic.data.LibraryScan
import com.example.mimic.data.LibraryScanConfig
import com.example.mimic.data.Song
import com.example.mimic.data.SongSource
import com.example.mimic.data.backup.BackupCodec
import com.example.mimic.data.backup.BackupData
import com.example.mimic.data.backup.BackupFormatException
import com.example.mimic.data.backup.BackupPlaylist
import com.example.mimic.data.backup.BackupPref
import com.example.mimic.data.backup.BackupSong
import com.example.mimic.data.backup.RestorePlanner
import com.example.mimic.data.backup.SongMatcher
import com.example.mimic.data.db.CustomThemeEntity
import com.example.mimic.data.db.FavoriteGroupEntity
import com.example.mimic.data.radio.RadioStationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** MImic 4.3: backup file format, song matching on restore, the restore plan, library folder filters. */
class BackupAndLibraryTest {

    private fun song(id: Long, title: String, artist: String, dur: Long = 200_000, source: SongSource = SongSource.LOCAL, remoteId: String? = null, folderPath: String = "Music/") =
        Song(id, title, artist, "Album", dur, "content://x/$id", null, 0, source = source, remoteId = remoteId, folderPath = folderPath)

    // ---- Format ----------------------------------------------------------------------------

    private fun sample() = BackupData(
        createdAt = 1_760_000_000_000, appVersion = "4.3.0-beta.1",
        songs = listOf(BackupSong(0, 11, "LOCAL", null, "Night Drive", "Aurora", "Singles", 180_000), BackupSong(1, 4294967297, "SERVER", "tr-9", "Aerials", "System Of A Down", "Toxicity", 243_000)),
        favorites = listOf(0 to 5L),
        favoriteGroups = listOf(FavoriteGroupEntity(FavoriteGroupEntity.KIND_ARTIST, "artist:aurora", "Aurora", "", 7)),
        playlists = listOf(BackupPlaylist("Road", 9, listOf(1, 0))),
        history = listOf(0 to 100L, 1 to 200L),
        themes = listOf(CustomThemeEntity("c1", "Mine", true, 1, 2, 3, 4, 5, 6, 7, 8, """{"glass":true}""")),
        settings = mapOf(
            "theme_id" to BackupPref(BackupPref.STRING, "c1"), "audio_crossfade_sec" to BackupPref(BackupPref.INT, 4),
            "dyn_brightness" to BackupPref(BackupPref.FLOAT, 0.25f), "dyn_blur" to BackupPref(BackupPref.BOOL, true),
            "scan_excluded_folders" to BackupPref(BackupPref.STRING_SET, setOf("Music/WhatsApp/")),
            "password_enc" to BackupPref(BackupPref.STRING, "SECRET"), "auth_token" to BackupPref(BackupPref.STRING, "SECRET"),
        ),
        radioCountries = listOf("AL", "IT"),
        radioStations = listOf(RadioStationEntity("st1", "Radio Tirana 1", "AL", "Tirana", "news", "NEWS", "https://s/rt1", null, null, "MP3", 128, favorite = true, favoritedAt = 3, updatedAt = 0)),
        serverUrl = "http://100.64.0.2:4533", serverUser = "enoluca",
    )

    @Test fun a_backup_round_trips_and_never_contains_secrets() {
        val json = BackupCodec.encode(sample())
        assertFalse("secrets must never be written", json.contains("SECRET"))
        assertFalse(json.contains("password"))
        val back = BackupCodec.decode(json)
        val s = sample()
        assertEquals(s.songs, back.songs)
        assertEquals(s.favorites, back.favorites)
        assertEquals(s.favoriteGroups, back.favoriteGroups)
        assertEquals(s.playlists, back.playlists)
        assertEquals(s.history, back.history)
        assertEquals(s.themes, back.themes)
        assertEquals(s.settings - "password_enc" - "auth_token", back.settings)
        assertEquals(listOf("AL", "IT"), back.radioCountries)
        assertEquals("Radio Tirana 1", back.radioStations.single().name)
        assertTrue(back.radioStations.single().favorite)
        assertEquals("enoluca", back.serverUser)
        assertEquals("enoluca @ http://100.64.0.2:4533", back.summary().server)
    }

    @Test fun files_that_are_not_valid_backups_are_rejected_clearly() {
        fun msg(text: String) = try { BackupCodec.decode(text); fail("accepted: $text"); "" } catch (e: BackupFormatException) { e.message!! }
        assertEquals("This file isn't a MImic backup.", msg("not json"))
        assertEquals("This file isn't a MImic backup.", msg("""{"format":"other","version":1}"""))
        assertTrue(msg("""{"format":"mimic-backup","version":99}""").contains("newer MImic"))
        assertEquals("This MImic backup is damaged.", msg("""{"format":"mimic-backup","version":1,"songs":[{"noref":1}]}"""))
        // An empty but valid backup is fine.
        assertEquals(0, BackupCodec.decode("""{"format":"mimic-backup","version":1}""").songs.size)
    }

    @Test fun references_to_songs_missing_from_the_file_are_dropped_and_bad_streams_ignored() {
        val json = """{"format":"mimic-backup","version":1,"songs":[{"ref":0,"id":1,"title":"A","artist":"B"}],
            "favorites":[{"song":0,"addedAt":1},{"song":7,"addedAt":2}],"history":[[0,5],[3,6]],
            "playlists":[{"name":"P","songs":[0,9]}],
            "radio":{"stations":[{"id":"x","stream":"file:///etc/passwd"},{"id":"y","stream":"https://ok"}]}}"""
        val b = BackupCodec.decode(json)
        assertEquals(listOf(0 to 1L), b.favorites)
        assertEquals(listOf(0 to 5L), b.history)
        assertEquals(listOf(0), b.playlists.single().songRefs)
        assertEquals(listOf("y"), b.radioStations.map { it.id })
    }

    // ---- Matching songs on this phone -----------------------------------------------------------

    @Test fun songs_are_matched_safely_on_this_or_another_phone() {
        val library = listOf(
            song(500, "Night Drive", "Aurora", 181_000),                       // same song, different MediaStore id (new phone)
            song(4294967297, "Aerials", "System Of A Down", source = SongSource.SERVER, remoteId = "tr-9"),
            song(11, "Something Else", "Other"),                                // id 11 now belongs to another song
            song(600, "Intro", "Various", 60_000), song(601, "Intro", "Various", 61_000), // ambiguous
            song(700, "Café del Mar", "Energy 52", 300_000),
        )
        val idx = SongMatcher.Index(library)
        assertEquals(500L, SongMatcher.match(BackupSong(0, 11, "LOCAL", null, "Night Drive", "Aurora", "", 180_000), idx)?.id)
        assertEquals(4294967297L, SongMatcher.match(BackupSong(1, 1, "SERVER", "tr-9", "renamed", "x", "", 0), idx)?.id)
        assertNull("ambiguous → skipped, never guessed", SongMatcher.match(BackupSong(2, 1, "LOCAL", null, "Intro", "Various", "", 60_500), idx))
        assertNull("length too different", SongMatcher.match(BackupSong(3, 1, "LOCAL", null, "Night Drive", "Aurora", "", 240_000), idx))
        assertEquals(700L, SongMatcher.match(BackupSong(4, 1, "LOCAL", null, "CAFE DEL MAR", "energy 52", "", 301_000), idx)?.id)
        assertNull(SongMatcher.match(BackupSong(5, 1, "LOCAL", null, "Unknown", "Nobody", "", 1), idx))
        // Saved while Navidrome was offline: only the stable server id is known → matched by id only.
        assertEquals(4294967297L, SongMatcher.match(SongMatcher.serverOnly(7, 4294967297), idx)?.id)
        assertNull("a phone id without details is never trusted", SongMatcher.match(BackupSong(8, 500, "LOCAL", null, "", "", "", 0), idx))
        assertTrue(SongMatcher.isServerId(4294967297) && !SongMatcher.isServerId(500))
        // Same phone: the id still points at the same song.
        assertEquals(11L, SongMatcher.match(BackupSong(6, 11, "LOCAL", null, "Something Else", "Other", "", 0), SongMatcher.Index(library))?.id)
    }

    // ---- Restore only adds -----------------------------------------------------------------

    @Test fun restore_adds_missing_data_and_never_duplicates() {
        val b = sample()
        val resolved = mapOf(0 to 500L, 1 to 900L)
        val plan = RestorePlanner.plan(
            b, resolved,
            existingFavorites = setOf(500L),                                  // already a favorite
            existingPlaylists = mapOf("Road" to (42L to listOf(900L))),       // same name, has one of the songs
            existingHistory = setOf(500L to 100L),                            // one play already there
        )
        assertTrue(plan.favoritesToAdd.isEmpty())
        assertEquals(listOf(RestorePlanner.PlaylistPlan("Road", 9, 42L, listOf(500L))), plan.playlists)
        assertEquals(listOf(900L to 200L), plan.historyToAdd)
        assertEquals(0, plan.unmatchedSongs)

        val fresh = RestorePlanner.plan(b, mapOf(0 to 500L), emptySet(), emptyMap(), emptySet())
        assertEquals(listOf(500L to 5L), fresh.favoritesToAdd)
        assertEquals(listOf(RestorePlanner.PlaylistPlan("Road", 9, null, listOf(500L))), fresh.playlists)
        assertEquals(1, fresh.unmatchedSongs) // "Aerials" isn't in this library

        // Restoring the same backup twice changes nothing the second time.
        val again = RestorePlanner.plan(b, resolved, setOf(500L), mapOf("Road" to (42L to listOf(900L, 500L))), setOf(500L to 100L, 900L to 200L))
        assertTrue(again.favoritesToAdd.isEmpty() && again.playlists.isEmpty() && again.historyToAdd.isEmpty())
    }

    // ---- Library folders & minimum length ------------------------------------------------------

    @Test fun excluded_folders_and_short_files_are_hidden_but_other_sources_stay() {
        val songs = listOf(
            song(1, "Song", "A", 200_000, folderPath = "Music/"),
            song(2, "Voice note", "Me", 8_000, folderPath = "Music/"),
            song(3, "Chat audio", "B", 120_000, folderPath = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/"),
            song(4, "Deep", "C", 120_000, folderPath = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/Private/"),
            song(5, "Server", "D", 5_000, source = SongSource.SERVER, remoteId = "r", folderPath = ""),
        )
        assertEquals(listOf(1L, 3L, 4L, 5L), LibraryScan.filter(songs, LibraryScanConfig()).map { it.id }) // 30 s default, as before 4.3
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), LibraryScan.filter(songs, LibraryScanConfig(minDurationSec = 0)).map { it.id })
        val noWhatsApp = LibraryScanConfig(excludedFolders = setOf("android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio"))
        assertEquals("subfolders are hidden too; case/slashes don't matter", listOf(1L, 5L), LibraryScan.filter(songs, noWhatsApp).map { it.id })

        val folders = LibraryScan.folders(songs, noWhatsApp)
        assertEquals(listOf("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/Private/", "Music/"), folders.map { it.path })
        assertEquals(listOf(true, true, false), folders.map { it.excluded })
        assertEquals(2, folders.last().songCount)
        assertEquals("WhatsApp Audio", folders.first().name)
        assertEquals("Music/MImic/", LibraryScan.normalize("/Music/MImic"))
        assertEquals("", LibraryScan.normalize("/"))
        assertFalse(LibraryScan.isExcluded("Music/Mimic2/", setOf("Music/Mimic/")))
    }
}
