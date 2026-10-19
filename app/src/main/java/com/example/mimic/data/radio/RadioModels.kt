package com.example.mimic.data.radio

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Internet radio (MImic 4.2, ported from ENAGELYUCA's radio). A live station; [id] is its Radio
 * Browser UUID for curated and directory stations alike, so a favorite made from either is the
 * same station.
 */
data class RadioStation(
    val id: String,
    val name: String,
    /** ISO 3166-1 alpha-2 ("AL", "GB", "IT"). */
    val countryCode: String,
    val city: String? = null,
    /** Short display genre ("News & Talk", "Pop"). */
    val genre: String? = null,
    val category: RadioCategory = RadioCategory.MUSIC,
    val streamUrl: String,
    /** HTTPS logo, or null → placeholder. */
    val logoUrl: String? = null,
    val websiteUrl: String? = null,
    val codec: String? = null,
    val bitrate: Int? = null,
    val isHls: Boolean = false,
    /** Directory popularity (votes); 0 for unknown. */
    val votes: Int = 0,
    /** In the hand-picked catalog (streams checked when the app was released). */
    val curated: Boolean = false,
) {
    val countryName: String get() = RadioCountries.displayName(countryCode)
    val flag: String get() = RadioCountries.flagOf(countryCode)

    /** "Tirana · News & Talk" — the second line on cards. */
    val subtitle: String get() = listOfNotNull(city?.takeIf { it.isNotBlank() } ?: countryName, genre).joinToString(" · ")

    /** "MP3 · 128 kbps" when known. */
    val qualityLabel: String?
        get() = listOfNotNull(codec?.takeIf { it.isNotBlank() && it != "UNKNOWN" }, bitrate?.takeIf { it > 0 }?.let { "$it kbps" })
            .joinToString(" · ").ifEmpty { null }

    /** Song id the player uses for this station: negative, so it never collides with real songs. */
    val playerId: Long get() = RadioIds.playerId(id)
}

enum class RadioCategory(val key: String, val label: String) {
    POPULAR("popular", "Popular"),
    MUSIC("music", "Music"),
    NEWS("news", "News"),
    ENTERTAINMENT("entertainment", "Entertainment"),
    LOCAL("local", "Local"),
    ;

    companion object {
        fun fromKey(key: String?): RadioCategory = entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: MUSIC
    }
}

/** Filters on a country's station list (the categories plus the user's own lists). */
enum class RadioFilter(val label: String) {
    ALL("All"), POPULAR("Popular"), MUSIC("Music"), NEWS("News"), ENTERTAINMENT("Entertainment"),
    LOCAL("Local"), FAVORITES("Favorites"), RECENT("Recently Played"),
}

enum class RadioSort(val label: String) { POPULAR("Most popular"), NAME("Name") }

data class RadioCountry(val code: String, val name: String, val stationCount: Int? = null) {
    val flag: String get() = RadioCountries.flagOf(code)
}

object RadioCountries {
    const val DEFAULT = "AL"

    /**
     * Every country a user can add: ISO 3166 regions plus Kosovo (XK, used by Radio Browser),
     * named in English. Station counts come from the directory when it can be reached.
     */
    fun catalog(counts: Map<String, Int> = emptyMap()): List<RadioCountry> {
        val codes = (Locale.getISOCountries().toSet() + "XK" + counts.keys).map { it.uppercase(Locale.ROOT) }.filter { it.length == 2 }.toSet()
        return codes.map { RadioCountry(it, displayName(it), counts[it]) }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun displayName(code: String): String {
        val cc = code.uppercase(Locale.ROOT)
        if (cc == "XK") return "Kosovo"
        return runCatching { Locale.Builder().setRegion(cc).build().getDisplayCountry(Locale.ENGLISH) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != cc } ?: cc
    }

    /** Regional-indicator flag emoji ("AL" → 🇦🇱). */
    fun flagOf(code: String): String {
        val cc = code.uppercase(Locale.ROOT)
        if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) return "🌍"
        return String(Character.toChars(0x1F1E6 + (cc[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (cc[1] - 'A')))
    }

    /** Search by English name or ISO code ("uk" also finds the United Kingdom). */
    fun matches(c: RadioCountry, query: String): Boolean {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        if (c.code.lowercase(Locale.ROOT) == q) return true
        if (q == "uk" && c.code == "GB") return true
        if ((q == "usa" || q == "us" || q == "america") && c.code == "US") return true
        return c.name.lowercase(Locale.ROOT).contains(q)
    }
}

/** The ordered list of countries shown on Radio (pure; unit-tested). */
object RadioCountryList {
    fun parse(csv: String?): List<String> =
        csv?.split(',')?.map { it.trim().uppercase(Locale.ROOT) }?.filter { it.length == 2 }?.distinct()
            ?.takeIf { it.isNotEmpty() } ?: listOf(RadioCountries.DEFAULT)

    fun encode(codes: List<String>): String = codes.joinToString(",")

    /** Adds [code] at the end unless already present (no duplicates). */
    fun add(codes: List<String>, code: String): List<String> {
        val c = code.uppercase(Locale.ROOT)
        return if (c in codes) codes else codes + c
    }

    /** Removes [code]; an empty list falls back to the default (Albania). */
    fun remove(codes: List<String>, code: String): List<String> =
        codes.filterNot { it.equals(code, ignoreCase = true) }.ifEmpty { listOf(RadioCountries.DEFAULT) }

    fun move(codes: List<String>, code: String, up: Boolean): List<String> {
        val i = codes.indexOf(code)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in codes.indices) return codes
        return codes.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** "Restore default": Albania first, the other countries kept. */
    fun restoreDefault(codes: List<String>): List<String> = listOf(RadioCountries.DEFAULT) + codes.filterNot { it == RadioCountries.DEFAULT }
}

object RadioIds {
    private const val PREFIX = "radio:"

    /** Negative, stable 62-bit id for the player / UI (pure). */
    fun playerId(stationId: String): Long {
        val d = java.security.MessageDigest.getInstance("SHA-256").digest("radio|$stationId".toByteArray())
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (d[i].toLong() and 0xFF)
        return -((v ushr 2) or 1L)
    }

    fun isRadio(songId: Long) = songId < 0
    fun remoteId(stationId: String) = PREFIX + stationId
    fun stationIdOf(remoteId: String?): String? = remoteId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)
}

object RadioSearch {
    fun matches(station: RadioStation, query: String): Boolean {
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return true
        val haystack = listOfNotNull(station.name, station.countryName, station.city, station.genre, station.category.label)
            .joinToString(" ").lowercase(Locale.ROOT)
        return words.all { it in haystack }
    }

    /**
     * Merges lists, first occurrence wins (curated before directory). Also drops a station whose
     * stream address (ignoring http/https) was already listed under another id — the directory
     * often lists the same stream twice.
     */
    fun merge(vararg lists: List<RadioStation>): List<RadioStation> {
        val seen = HashSet<String>()
        return lists.flatMap { it }.filter { s -> seen.add("id:" + s.id) && seen.add("url:" + normalizeUrl(s.streamUrl)) }
    }

    fun normalizeUrl(url: String) = url.substringAfter("://").trimEnd('/').lowercase(Locale.ROOT)

    /** A country's list for [filter] and [sort] (pure). */
    fun view(stations: List<RadioStation>, filter: RadioFilter, sort: RadioSort, favorites: Set<String>, recent: List<String>, query: String): List<RadioStation> {
        val base = when (filter) {
            RadioFilter.ALL -> stations
            RadioFilter.POPULAR -> stations.sortedByDescending { if (it.category == RadioCategory.POPULAR) Int.MAX_VALUE else it.votes }.take(25)
            RadioFilter.MUSIC -> stations.filter { it.category == RadioCategory.MUSIC || it.category == RadioCategory.POPULAR }
            RadioFilter.NEWS -> stations.filter { it.category == RadioCategory.NEWS }
            RadioFilter.ENTERTAINMENT -> stations.filter { it.category == RadioCategory.ENTERTAINMENT }
            RadioFilter.LOCAL -> stations.filter { it.category == RadioCategory.LOCAL || !it.city.isNullOrBlank() }
            RadioFilter.FAVORITES -> stations.filter { it.id in favorites }
            RadioFilter.RECENT -> recent.mapNotNull { id -> stations.firstOrNull { it.id == id } }
        }.filter { matches(it, query) }
        return when {
            filter == RadioFilter.RECENT || filter == RadioFilter.POPULAR -> base
            sort == RadioSort.NAME -> base.sortedBy { it.name.lowercase(Locale.ROOT) }
            else -> base.sortedWith(compareByDescending<RadioStation> { it.curated }.thenByDescending { it.votes })
        }
    }
}

object RadioUrls {
    fun isPlayable(url: String): Boolean = url.startsWith("https://") || url.startsWith("http://")

    /** Keeps a known HTTPS URL when the directory reports the same address over HTTP. */
    fun preferSecure(stored: String, fresh: String?): String {
        if (fresh == null) return stored
        return if (stored.startsWith("https://") && fresh.substringAfter("://") == stored.substringAfter("://")) stored else fresh
    }
}

/** Parses the bundled catalog (assets/radio/stations.json). */
object RadioCatalogParser {
    fun parse(json: String): List<RadioStation> {
        val arr = runCatching { JSONObject(json).optJSONArray("stations") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = s.text("id") ?: return@mapNotNull null
            val name = s.text("name") ?: return@mapNotNull null
            val url = s.text("streamUrl")?.takeIf(RadioUrls::isPlayable) ?: return@mapNotNull null
            val country = s.text("countryCode")?.uppercase(Locale.ROOT) ?: return@mapNotNull null
            RadioStation(
                id = id, name = name, countryCode = country, city = s.text("city"), genre = s.text("genre"),
                category = RadioCategory.fromKey(s.text("category")), streamUrl = url,
                logoUrl = s.text("logoUrl")?.takeIf { it.startsWith("https://") }, websiteUrl = s.text("websiteUrl"),
                codec = s.text("codec"), bitrate = s.optInt("bitrate", 0).takeIf { it > 0 },
                isHls = s.optBoolean("hls", false) || url.contains(".m3u8", ignoreCase = true), curated = true,
            )
        }
    }
}

/** Parses Radio Browser (api.radio-browser.info) answers. */
object RadioBrowserParser {
    /** TV streams listed as "stations" are left out of a radio app. */
    private val VIDEO_CODECS = listOf("H.264", "H.265", "HEVC", "VP8", "VP9", "AV1", "THEORA")
    private val NEWS_TAGS = listOf("news", "talk", "politic", "public radio", "information")
    private val ENTERTAINMENT_TAGS = listOf("sport", "comedy", "entertainment", "drama", "kids", "children")
    private val LOCAL_TAGS = listOf("local", "community", "regional", "city")

    fun stations(json: String): List<RadioStation> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return RadioSearch.merge((0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::station) })
    }

    fun station(s: JSONObject): RadioStation? {
        // Only stations whose last automatic stream check succeeded.
        if (s.optInt("lastcheckok", 1) != 1) return null
        val codec = s.text("codec")
        if (codec != null && VIDEO_CODECS.any { codec.uppercase(Locale.ROOT).contains(it) }) return null
        val name = s.text("name")?.trim()?.replace(Regex("\\s+"), " ") ?: return null
        if (name.contains(" TV", ignoreCase = false) && codec == null) return null
        val id = s.text("stationuuid") ?: return null
        val url = (s.text("url_resolved") ?: s.text("url"))?.trim()?.takeIf(RadioUrls::isPlayable) ?: return null
        val tags = s.text("tags").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() && it.length <= 24 }
        return RadioStation(
            id = id,
            name = name,
            countryCode = s.text("countrycode")?.uppercase(Locale.ROOT) ?: "",
            city = s.text("state")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 24 },
            genre = tags.take(2).joinToString(" · ") { tag -> tag.replaceFirstChar { it.titlecase(Locale.ROOT) } }.ifEmpty { null },
            category = categoryOf(tags),
            streamUrl = url,
            logoUrl = s.text("favicon")?.takeIf { it.startsWith("https://") },
            websiteUrl = s.text("homepage"),
            codec = codec?.takeIf { it != "UNKNOWN" },
            bitrate = s.optInt("bitrate", 0).takeIf { it > 0 },
            isHls = s.optInt("hls", 0) == 1 || url.contains(".m3u8", ignoreCase = true),
            votes = s.optInt("votes", 0),
        )
    }

    fun categoryOf(tags: List<String>): RadioCategory {
        val lower = tags.map { it.lowercase(Locale.ROOT) }
        return when {
            lower.any { t -> NEWS_TAGS.any { t.contains(it) } } -> RadioCategory.NEWS
            lower.any { t -> ENTERTAINMENT_TAGS.any { t.contains(it) } } -> RadioCategory.ENTERTAINMENT
            lower.any { t -> LOCAL_TAGS.any { t.contains(it) } } -> RadioCategory.LOCAL
            else -> RadioCategory.MUSIC
        }
    }

    /** `/json/countries` → station count per ISO code. */
    fun countryCounts(json: String): Map<String, Int> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.text("iso_3166_1")?.uppercase(Locale.ROOT) ?: continue
            out[code] = (out[code] ?: 0) + o.optInt("stationcount", 0)
        }
        return out
    }

    /** `/json/url/{uuid}` answer → the URL to play. */
    fun clickUrl(json: String): String? = runCatching { JSONObject(json) }.getOrNull()
        ?.takeIf { it.optBoolean("ok", false) }?.text("url")?.trim()?.takeIf(RadioUrls::isPlayable)
}

private fun JSONObject.text(field: String): String? =
    if (isNull(field)) null else (opt(field) as? String)?.takeIf { it.isNotBlank() }
