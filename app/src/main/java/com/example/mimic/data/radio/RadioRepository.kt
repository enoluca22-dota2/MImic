package com.example.mimic.data.radio

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.example.mimic.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * A radio station the user saved (favorite) or played (recently played). Directory/catalog data
 * is copied here so both lists work offline and survive a station leaving the directory. Radio is
 * never part of the music Library, Downloads or song play history.
 */
@Entity(tableName = "radio_stations", indices = [Index("favorite"), Index("lastPlayedAt")])
data class RadioStationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val countryCode: String,
    val city: String?,
    val genre: String?,
    val category: String,
    val streamUrl: String,
    val logoUrl: String?,
    val websiteUrl: String?,
    val codec: String?,
    val bitrate: Int?,
    @ColumnInfo(defaultValue = "0") val hls: Boolean = false,
    @ColumnInfo(defaultValue = "0") val favorite: Boolean = false,
    val favoritedAt: Long? = null,
    val lastPlayedAt: Long? = null,
    @ColumnInfo(defaultValue = "0") val playCount: Int = 0,
    val updatedAt: Long,
)

@Dao
interface RadioDao {
    @Query("SELECT * FROM radio_stations WHERE id = :id")
    suspend fun get(id: String): RadioStationEntity?

    @Upsert
    suspend fun upsert(entity: RadioStationEntity)

    @Query("SELECT * FROM radio_stations WHERE favorite = 1 ORDER BY favoritedAt DESC, name")
    fun observeFavorites(): Flow<List<RadioStationEntity>>

    @Query("SELECT * FROM radio_stations WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RadioStationEntity>>

    @Query("UPDATE radio_stations SET favorite = :favorite, favoritedAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, at: Long)

    @Query("UPDATE radio_stations SET lastPlayedAt = :at, playCount = playCount + 1, updatedAt = :at WHERE id = :id")
    suspend fun recordPlay(id: String, at: Long)

    /** Keeps only the newest [limit] in Recently played. */
    @Query("UPDATE radio_stations SET lastPlayedAt = NULL WHERE lastPlayedAt IS NOT NULL AND id NOT IN (SELECT id FROM radio_stations WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit)")
    suspend fun trimRecent(limit: Int)

    @Query("DELETE FROM radio_stations WHERE favorite = 0 AND lastPlayedAt IS NULL")
    suspend fun deleteUnused()

    /** Favorites + recently played (what a backup keeps). */
    @Query("SELECT * FROM radio_stations WHERE favorite = 1 OR lastPlayedAt IS NOT NULL")
    suspend fun saved(): List<RadioStationEntity>
}

private val Context.radioPrefs by preferencesDataStore(name = "radio_prefs")

/** Which countries Radio shows, in order. A fresh install shows only Albania. */
class RadioCountryStore(private val context: Context) {
    private val key = stringPreferencesKey("countries")

    val countries: Flow<List<String>> = context.radioPrefs.data.map { RadioCountryList.parse(it[key]) }

    private suspend fun update(f: (List<String>) -> List<String>) {
        context.radioPrefs.edit { it[key] = RadioCountryList.encode(f(RadioCountryList.parse(it[key]))) }
    }

    suspend fun add(code: String) = update { RadioCountryList.add(it, code) }
    suspend fun remove(code: String) = update { RadioCountryList.remove(it, code) }
    suspend fun move(code: String, up: Boolean) = update { RadioCountryList.move(it, code, up) }
    suspend fun restoreDefault() = update { RadioCountryList.restoreDefault(it) }
    suspend fun current(): List<String> = countries.first()

    /** Adds [codes] after the current countries (restore from backup; nothing is removed). */
    suspend fun mergeIn(codes: List<String>) = update { cur -> codes.fold(cur) { acc, c -> RadioCountryList.add(acc, c) } }
}

/**
 * Radio Browser (https://www.radio-browser.info): a free, public, community-maintained station
 * directory that re-checks streams regularly. Only stations whose last check succeeded are used.
 * Mirrors are tried in turn; nothing is ever invented — no answer means an error the UI shows.
 */
class RadioBrowserDirectory(private val http: OkHttpClient = defaultClient()) {

    suspend fun byCountry(countryCode: String, limit: Int = 150): List<RadioStation> =
        RadioBrowserParser.stations(get("/json/stations/bycountrycodeexact/${enc(countryCode)}?hidebroken=true&order=votes&reverse=true&limit=$limit"))

    suspend fun search(query: String, countryCode: String?, limit: Int = 60): List<RadioStation> {
        val cc = countryCode?.let { "&countrycode=${enc(it)}" }.orEmpty()
        return RadioBrowserParser.stations(get("/json/stations/search?name=${enc(query.trim())}$cc&hidebroken=true&order=votes&reverse=true&limit=$limit"))
    }

    suspend fun countryCounts(): Map<String, Int> = RadioBrowserParser.countryCounts(get("/json/countries"))

    /** The stream URL to play now (also counts a "click" for the directory's ranking), or null. */
    suspend fun currentStreamUrl(stationId: String): String? =
        runCatching { RadioBrowserParser.clickUrl(get("/json/url/${enc(stationId)}")) }.getOrNull()

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (host in MIRRORS) {
            try {
                val req = Request.Builder().url("https://$host$path").header("User-Agent", USER_AGENT).header("Accept", "application/json").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code} from $host")
                    return@withContext r.body?.string() ?: throw IOException("empty answer from $host")
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Radio directory unavailable", last)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    companion object {
        /** Radio Browser asks clients to identify themselves. */
        val USER_AGENT = "MImic/${BuildConfig.VERSION_NAME} (Android)"
        val MIRRORS = listOf("de1.api.radio-browser.info", "de2.api.radio-browser.info", "all.api.radio-browser.info")

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).build()
    }
}

/**
 * Stations for Radio and the player: the bundled catalog (instant, offline, streams checked at
 * release), the live directory (browse/search, fresh stream URLs) and saved/recent stations.
 */
class RadioRepository(
    private val context: Context,
    private val dao: RadioDao,
    val countries: RadioCountryStore,
    private val directory: RadioBrowserDirectory = RadioBrowserDirectory(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val catalog: List<RadioStation> by lazy {
        runCatching { context.assets.open("radio/stations.json").use { RadioCatalogParser.parse(it.readBytes().decodeToString()) } }
            .onFailure { Log.e(TAG, "Couldn't read the station catalog", it) }
            .getOrDefault(emptyList())
    }

    val favorites: Flow<List<RadioStation>> = dao.observeFavorites().map { rows -> rows.map { it.toStation() } }
    val recent: Flow<List<RadioStation>> = dao.observeRecent(RECENT_LIMIT).map { rows -> rows.map { it.toStation() } }

    /** Bundled stations of a country (works offline). */
    fun featured(countryCode: String): List<RadioStation> = catalog.filter { it.countryCode.equals(countryCode, ignoreCase = true) }

    /**
     * A country's stations: bundled first, then the directory's (deduplicated). Cached for 30 min;
     * [refresh] bypasses the cache. Throws only if the directory fails AND nothing is bundled.
     */
    suspend fun countryStations(countryCode: String, refresh: Boolean = false): Result<List<RadioStation>> {
        val featured = featured(countryCode)
        return runCatching { cached("country:$countryCode", refresh) { directory.byCountry(countryCode) } }
            .map { RadioSearch.merge(featured, it) }
            .recoverCatching { e -> if (featured.isNotEmpty()) featured else throw e }
    }

    suspend fun search(query: String, countryCode: String?): List<RadioStation> = directory.search(query, countryCode)

    /** Countries with directory station counts (empty map if the directory can't be reached). */
    suspend fun countryCounts(): Map<String, Int> =
        countsCache ?: runCatching { directory.countryCounts() }.getOrDefault(emptyMap()).also { if (it.isNotEmpty()) countsCache = it }

    @Volatile private var countsCache: Map<String, Int>? = null

    suspend fun setFavorite(station: RadioStation, favorite: Boolean) {
        remember(station)
        dao.setFavorite(station.id, favorite, now())
        if (!favorite) dao.deleteUnused()
    }

    suspend fun recordPlayed(station: RadioStation) {
        remember(station)
        dao.recordPlay(station.id, now())
        dao.trimRecent(RECENT_LIMIT)
        dao.deleteUnused()
    }

    /**
     * The stream to play: the directory's current URL (stations move their streams), falling
     * back to the known one if the directory is slow or unreachable.
     */
    suspend fun resolveStreamUrl(station: RadioStation): String {
        val fresh = withTimeoutOrNull(FRESH_URL_TIMEOUT_MS) { directory.currentStreamUrl(station.id) }
        return RadioUrls.preferSecure(station.streamUrl, fresh)
    }

    private val lock = Mutex()

    private suspend fun remember(station: RadioStation) = lock.withLock {
        val existing = dao.get(station.id)
        dao.upsert(
            RadioStationEntity(
                id = station.id, name = station.name, countryCode = station.countryCode, city = station.city,
                genre = station.genre, category = station.category.key, streamUrl = station.streamUrl,
                logoUrl = station.logoUrl, websiteUrl = station.websiteUrl, codec = station.codec, bitrate = station.bitrate,
                hls = station.isHls, favorite = existing?.favorite ?: false, favoritedAt = existing?.favoritedAt,
                lastPlayedAt = existing?.lastPlayedAt, playCount = existing?.playCount ?: 0, updatedAt = now(),
            ),
        )
    }

    private val cacheLock = Mutex()
    private val cache = HashMap<String, Pair<Long, List<RadioStation>>>()

    private suspend fun cached(key: String, refresh: Boolean, load: suspend () -> List<RadioStation>): List<RadioStation> {
        if (!refresh) cacheLock.withLock { cache[key]?.takeIf { now() - it.first < CACHE_MS }?.let { return it.second } }
        val fresh = load()
        cacheLock.withLock { cache[key] = now() to fresh }
        return fresh
    }

    companion object {
        private const val TAG = "MImicRadio"
        const val RECENT_LIMIT = 20
        private const val CACHE_MS = 30 * 60 * 1000L
        private const val FRESH_URL_TIMEOUT_MS = 3_500L

        fun RadioStationEntity.toStation() = RadioStation(
            id = id, name = name, countryCode = countryCode, city = city, genre = genre,
            category = RadioCategory.fromKey(category), streamUrl = streamUrl, logoUrl = logoUrl,
            websiteUrl = websiteUrl, codec = codec, bitrate = bitrate, isHls = hls,
        )
    }
}
