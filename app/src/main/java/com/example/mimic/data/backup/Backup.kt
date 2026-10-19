package com.example.mimic.data.backup

import com.example.mimic.data.Song
import com.example.mimic.data.SongSource
import com.example.mimic.data.db.CustomThemeEntity
import com.example.mimic.data.db.FavoriteGroupEntity
import com.example.mimic.data.radio.RadioStationEntity
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * MImic backup file (MImic 4.3): one versioned JSON document ("format": "mimic-backup").
 *
 * WHAT IS IN IT: favorites (songs, albums, artists), playlists, listening history, custom themes,
 * app settings (appearance, player, library, audio), radio countries + saved stations, and the
 * Navidrome address and user name. NEVER in it: passwords, tokens, music files, downloads.
 *
 * SONG IDENTITY: favorites, playlists and history point at song ids, and phone song ids come from
 * Android's media database — they differ on another phone or after a reinstall. So every song a
 * backup mentions is written with its title, artist, album, length and server id, and on restore
 * it is matched against the library on that phone ([SongMatcher]); a song that can't be matched
 * with confidence is skipped and counted, never guessed.
 */
data class BackupSong(
    val ref: Int,
    val id: Long,
    val source: String,
    val remoteId: String?,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
)

data class BackupPlaylist(val name: String, val createdAt: Long, val songRefs: List<Int>)

/** One typed preference value (DataStore keeps the type, so the backup does too). */
data class BackupPref(val type: String, val value: Any) {
    companion object {
        const val STRING = "s"; const val INT = "i"; const val LONG = "l"; const val BOOL = "b"
        const val FLOAT = "f"; const val DOUBLE = "d"; const val STRING_SET = "ss"
    }
}

data class BackupData(
    val createdAt: Long,
    val appVersion: String,
    val songs: List<BackupSong> = emptyList(),
    /** (song ref, addedAt) */
    val favorites: List<Pair<Int, Long>> = emptyList(),
    val favoriteGroups: List<FavoriteGroupEntity> = emptyList(),
    val playlists: List<BackupPlaylist> = emptyList(),
    /** (song ref, playedAt) */
    val history: List<Pair<Int, Long>> = emptyList(),
    val themes: List<CustomThemeEntity> = emptyList(),
    val settings: Map<String, BackupPref> = emptyMap(),
    val radioCountries: List<String> = emptyList(),
    val radioStations: List<RadioStationEntity> = emptyList(),
    val serverUrl: String? = null,
    val serverUser: String? = null,
) {
    /** What the restore screen shows before anything is changed. */
    data class Summary(
        val favorites: Int, val favoriteGroups: Int, val playlists: Int, val playlistSongs: Int,
        val historyEvents: Int, val themes: Int, val settings: Int, val radioCountries: Int,
        val radioStations: Int, val server: String?,
    )

    fun summary() = Summary(
        favorites.size, favoriteGroups.size, playlists.size, playlists.sumOf { it.songRefs.size },
        history.size, themes.size, settings.size, radioCountries.size, radioStations.size,
        serverUser?.let { u -> serverUrl?.let { "$u @ $it" } },
    )
}

class BackupFormatException(message: String) : Exception(message)

object BackupCodec {
    const val FORMAT = "mimic-backup"
    const val VERSION = 1
    /** Refuse absurd files before parsing (a real backup is a few MB at most). */
    const val MAX_BYTES = 32L * 1024 * 1024

    /** Preference keys that must never leave the phone, whatever store they are in. */
    private val SENSITIVE = Regex("pass|token|secret|salt|cookie|credential|auth", RegexOption.IGNORE_CASE)
    fun isSensitiveKey(key: String) = SENSITIVE.containsMatchIn(key)

    fun encode(b: BackupData): String {
        val o = JSONObject()
        o.put("format", FORMAT).put("version", VERSION).put("createdAt", b.createdAt).put("app", b.appVersion)
        o.put("songs", JSONArray().apply {
            b.songs.forEach { s ->
                put(JSONObject().put("ref", s.ref).put("id", s.id).put("source", s.source).put("remoteId", s.remoteId ?: JSONObject.NULL)
                    .put("title", s.title).put("artist", s.artist).put("album", s.album).put("durationMs", s.durationMs))
            }
        })
        o.put("favorites", JSONArray().apply { b.favorites.forEach { put(JSONObject().put("song", it.first).put("addedAt", it.second)) } })
        o.put("favoriteGroups", JSONArray().apply {
            b.favoriteGroups.forEach { put(JSONObject().put("kind", it.kind).put("key", it.groupKey).put("name", it.name).put("subtitle", it.subtitle).put("addedAt", it.addedAt)) }
        })
        o.put("playlists", JSONArray().apply {
            b.playlists.forEach { p -> put(JSONObject().put("name", p.name).put("createdAt", p.createdAt).put("songs", JSONArray(p.songRefs))) }
        })
        o.put("history", JSONArray().apply { b.history.forEach { put(JSONArray().put(it.first).put(it.second)) } })
        o.put("themes", JSONArray().apply {
            b.themes.forEach { t ->
                put(JSONObject().put("id", t.id).put("name", t.name).put("isDark", t.isDark).put("primary", t.primary).put("secondary", t.secondary)
                    .put("background", t.background).put("surface", t.surface).put("surfaceElevated", t.surfaceElevated)
                    .put("textPrimary", t.textPrimary).put("textSecondary", t.textSecondary).put("createdAt", t.createdAt)
                    .put("style", t.styleJson ?: JSONObject.NULL))
            }
        })
        o.put("settings", JSONObject().apply {
            b.settings.forEach { (k, p) ->
                if (isSensitiveKey(k)) return@forEach
                val v: Any = if (p.type == BackupPref.STRING_SET) JSONArray((p.value as Set<*>).toList()) else p.value
                put(k, JSONObject().put("t", p.type).put("v", v))
            }
        })
        o.put("radio", JSONObject().put("countries", JSONArray(b.radioCountries)).put("stations", JSONArray().apply {
            b.radioStations.forEach { r ->
                put(JSONObject().put("id", r.id).put("name", r.name).put("country", r.countryCode).put("city", r.city ?: JSONObject.NULL)
                    .put("genre", r.genre ?: JSONObject.NULL).put("category", r.category).put("stream", r.streamUrl)
                    .put("logo", r.logoUrl ?: JSONObject.NULL).put("website", r.websiteUrl ?: JSONObject.NULL)
                    .put("codec", r.codec ?: JSONObject.NULL).put("bitrate", r.bitrate ?: JSONObject.NULL).put("hls", r.hls)
                    .put("favorite", r.favorite).put("favoritedAt", r.favoritedAt ?: JSONObject.NULL)
                    .put("lastPlayedAt", r.lastPlayedAt ?: JSONObject.NULL).put("playCount", r.playCount))
            }
        }))
        if (b.serverUrl != null) o.put("server", JSONObject().put("url", b.serverUrl).put("user", b.serverUser ?: JSONObject.NULL))
        return o.toString()
    }

    /** Parses and validates; throws [BackupFormatException] with a message the user can read. */
    fun decode(text: String): BackupData {
        val o = try { JSONObject(text) } catch (e: Exception) { throw BackupFormatException("This file isn't a MImic backup.") }
        if (o.optString("format") != FORMAT) throw BackupFormatException("This file isn't a MImic backup.")
        val version = o.optInt("version", -1)
        if (version < 1) throw BackupFormatException("This MImic backup is damaged.")
        if (version > VERSION) throw BackupFormatException("This backup was made by a newer MImic. Update MImic, then try again.")
        try {
            val songs = o.optJSONArray("songs").objects().map { s ->
                BackupSong(
                    s.getInt("ref"), s.optLong("id"), s.optString("source", SongSource.LOCAL.name), s.optStringOrNull("remoteId"),
                    s.optString("title"), s.optString("artist"), s.optString("album"), s.optLong("durationMs"),
                )
            }
            val refs = songs.map { it.ref }.toSet()
            val favorites = o.optJSONArray("favorites").objects().map { it.getInt("song") to it.optLong("addedAt") }.filter { it.first in refs }
            val groups = o.optJSONArray("favoriteGroups").objects().mapNotNull { g ->
                val kind = g.optString("kind")
                if (kind != FavoriteGroupEntity.KIND_ALBUM && kind != FavoriteGroupEntity.KIND_ARTIST) null
                else FavoriteGroupEntity(kind, g.getString("key"), g.optString("name"), g.optString("subtitle"), g.optLong("addedAt"))
            }
            val playlists = o.optJSONArray("playlists").objects().map { p ->
                val arr = p.optJSONArray("songs") ?: JSONArray()
                BackupPlaylist(p.getString("name").take(200), p.optLong("createdAt"), (0 until arr.length()).map { arr.getInt(it) }.filter { it in refs })
            }
            val historyArr = o.optJSONArray("history") ?: JSONArray()
            val history = (0 until historyArr.length()).map { historyArr.getJSONArray(it) }.map { it.getInt(0) to it.getLong(1) }.filter { it.first in refs }
            val themes = o.optJSONArray("themes").objects().map { t ->
                CustomThemeEntity(
                    t.getString("id"), t.optString("name"), t.optBoolean("isDark"), t.getLong("primary"), t.getLong("secondary"),
                    t.getLong("background"), t.getLong("surface"), t.getLong("surfaceElevated"), t.getLong("textPrimary"),
                    t.getLong("textSecondary"), t.optLong("createdAt"), t.optStringOrNull("style"),
                )
            }
            val settings = LinkedHashMap<String, BackupPref>()
            o.optJSONObject("settings")?.let { s ->
                for (k in s.keys()) {
                    if (isSensitiveKey(k)) continue
                    val e = s.getJSONObject(k)
                    val pref = when (val t = e.getString("t")) {
                        BackupPref.STRING -> BackupPref(t, e.getString("v"))
                        BackupPref.INT -> BackupPref(t, e.getInt("v"))
                        BackupPref.LONG -> BackupPref(t, e.getLong("v"))
                        BackupPref.BOOL -> BackupPref(t, e.getBoolean("v"))
                        BackupPref.FLOAT -> BackupPref(t, e.getDouble("v").toFloat())
                        BackupPref.DOUBLE -> BackupPref(t, e.getDouble("v"))
                        BackupPref.STRING_SET -> e.getJSONArray("v").let { a -> BackupPref(t, (0 until a.length()).map { a.getString(it) }.toSet()) }
                        else -> null
                    } ?: continue
                    settings[k] = pref
                }
            }
            val radio = o.optJSONObject("radio")
            val countries = radio?.optJSONArray("countries")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val stations = radio?.optJSONArray("stations").objects().map { r ->
                RadioStationEntity(
                    id = r.getString("id"), name = r.optString("name"), countryCode = r.optString("country"), city = r.optStringOrNull("city"),
                    genre = r.optStringOrNull("genre"), category = r.optString("category", "MUSIC"), streamUrl = r.getString("stream"),
                    logoUrl = r.optStringOrNull("logo"), websiteUrl = r.optStringOrNull("website"), codec = r.optStringOrNull("codec"),
                    bitrate = if (r.isNull("bitrate")) null else r.optInt("bitrate"), hls = r.optBoolean("hls"),
                    favorite = r.optBoolean("favorite"), favoritedAt = r.optLongOrNull("favoritedAt"),
                    lastPlayedAt = r.optLongOrNull("lastPlayedAt"), playCount = r.optInt("playCount"), updatedAt = 0L,
                )
            }.filter { it.streamUrl.startsWith("http://") || it.streamUrl.startsWith("https://") }
            val server = o.optJSONObject("server")
            return BackupData(
                createdAt = o.optLong("createdAt"), appVersion = o.optString("app"), songs = songs, favorites = favorites,
                favoriteGroups = groups, playlists = playlists, history = history, themes = themes, settings = settings,
                radioCountries = countries, radioStations = stations,
                serverUrl = server?.optStringOrNull("url"), serverUser = server?.optStringOrNull("user"),
            )
        } catch (e: BackupFormatException) {
            throw e
        } catch (e: Exception) {
            throw BackupFormatException("This MImic backup is damaged.")
        }
    }

    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).takeIf { has(k) }
    private fun JSONObject.optLongOrNull(k: String): Long? = if (!has(k) || isNull(k)) null else optLong(k)
}

/**
 * Finds the song on THIS phone that a backed-up song refers to:
 *  1. a server/downloaded song → the same Navidrome track id (stable everywhere);
 *  2. a phone song with the same id AND the same title + artist (same phone, same library);
 *  3. otherwise the one song with the same title + artist (normalized) whose length is within 3 s.
 * Ambiguous or missing → null (the entry is skipped, never attached to the wrong song).
 */
object SongMatcher {
    private const val DURATION_TOLERANCE_MS = 3_000L

    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    class Index(library: List<Song>) {
        val byRemote: Map<String, Song> = library.filter { it.remoteId != null }.associateBy { it.remoteId!! }
        val byId: Map<Long, Song> = library.associateBy { it.id }
        val byName: Map<String, List<Song>> = library.groupBy { norm(it.title) + "\u0000" + norm(it.artist) }
    }

    /** Navidrome/download ids are a hash of the server track id with bit 32 set (see remoteStableId). */
    fun isServerId(id: Long) = id >= 0x100000000L

    /** A server song that wasn't loaded when the backup was made: only its (stable) id is known. */
    fun serverOnly(ref: Int, id: Long) = BackupSong(ref, id, "SERVER", null, "", "", "", 0)

    fun match(b: BackupSong, index: Index): Song? {
        b.remoteId?.let { r -> index.byRemote[r]?.let { return it } }
        if (b.title.isBlank()) return index.byId[b.id]?.takeIf { isServerId(b.id) }
        val nameKey = norm(b.title) + "\u0000" + norm(b.artist)
        index.byId[b.id]?.let { s -> if (norm(s.title) + "\u0000" + norm(s.artist) == nameKey) return s }
        if (b.title.isBlank()) return null
        val candidates = index.byName[nameKey].orEmpty().filter {
            b.durationMs <= 0 || it.durationMs <= 0 || kotlin.math.abs(it.durationMs - b.durationMs) <= DURATION_TOLERANCE_MS
        }
        return candidates.singleOrNull()
            // The same recording available from several sources is still one song (one id).
            ?: candidates.map { it.id }.distinct().singleOrNull()?.let { id -> candidates.first { it.id == id } }
    }

    /** Backup ref → song id on this phone, for every ref that matched. */
    fun resolve(songs: List<BackupSong>, library: List<Song>): Map<Int, Long> {
        val index = Index(library)
        return songs.mapNotNull { b -> match(b, index)?.let { b.ref to it.id } }.toMap()
    }

    fun toBackupSong(ref: Int, s: Song) = BackupSong(ref, s.id, s.source.name, s.remoteId, s.title, s.artist, s.album, s.durationMs)
}

/**
 * The restore plan (pure): what to ADD to the current data. Restoring never deletes or replaces a
 * favorite, playlist, history entry, theme or radio station that is already there.
 */
object RestorePlanner {
    data class PlaylistPlan(val name: String, val createdAt: Long, val existingId: Long?, val songIdsToAdd: List<Long>)

    data class Plan(
        val favoritesToAdd: List<Pair<Long, Long>>,
        val playlists: List<PlaylistPlan>,
        val historyToAdd: List<Pair<Long, Long>>,
        val unmatchedSongs: Int,
    )

    /**
     * @param existingPlaylists name → (id, current song ids). A playlist with the same name gets
     *   only the songs it is missing appended; otherwise a new playlist is created.
     */
    fun plan(
        backup: BackupData,
        resolved: Map<Int, Long>,
        existingFavorites: Set<Long>,
        existingPlaylists: Map<String, Pair<Long, List<Long>>>,
        existingHistory: Set<Pair<Long, Long>>,
    ): Plan {
        val usedRefs = (backup.favorites.map { it.first } + backup.playlists.flatMap { it.songRefs } + backup.history.map { it.first }).toSet()
        val favorites = backup.favorites.mapNotNull { (ref, at) -> resolved[ref]?.let { it to at } }
            .filter { it.first !in existingFavorites }.distinctBy { it.first }
        val playlists = backup.playlists.mapNotNull { p ->
            val ids = p.songRefs.mapNotNull { resolved[it] }.distinct()
            val existing = existingPlaylists[p.name.trim()]
            val toAdd = if (existing == null) ids else ids.filter { it !in existing.second }
            if (existing != null && toAdd.isEmpty()) null else PlaylistPlan(p.name.trim(), p.createdAt, existing?.first, toAdd)
        }
        val history = backup.history.mapNotNull { (ref, at) -> resolved[ref]?.let { it to at } }
            .filter { it !in existingHistory }.distinct()
        return Plan(favorites, playlists, history, usedRefs.count { it !in resolved })
    }
}
