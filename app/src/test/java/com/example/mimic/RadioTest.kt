package com.example.mimic

import com.example.mimic.data.radio.RadioBrowserParser
import com.example.mimic.data.radio.RadioCatalogParser
import com.example.mimic.data.radio.RadioCategory
import com.example.mimic.data.radio.RadioCountries
import com.example.mimic.data.radio.RadioCountryList
import com.example.mimic.data.radio.RadioFilter
import com.example.mimic.data.radio.RadioIds
import com.example.mimic.data.radio.RadioSearch
import com.example.mimic.data.radio.RadioSort
import com.example.mimic.data.radio.RadioStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Radio (MImic 4.2): country selection, directory parsing, the bundled catalog, filters. */
class RadioTest {

    // ---- Countries (+ / −) --------------------------------------------------------------------

    @Test fun a_new_configuration_shows_only_albania() {
        assertEquals(listOf("AL"), RadioCountryList.parse(null))
        assertEquals(listOf("AL"), RadioCountryList.parse(""))
        assertEquals(listOf("AL"), RadioCountryList.parse("garbage,???"))
    }

    @Test fun countries_are_added_once_in_order() {
        var c = RadioCountryList.parse(null)
        c = RadioCountryList.add(c, "it")
        c = RadioCountryList.add(c, "GB")
        c = RadioCountryList.add(c, "IT") // duplicate ignored
        assertEquals(listOf("AL", "IT", "GB"), c)
        assertEquals(c, RadioCountryList.parse(RadioCountryList.encode(c))) // persists as written
    }

    @Test fun removing_keeps_the_others_and_never_leaves_radio_empty() {
        val c = listOf("AL", "IT", "GB", "US")
        assertEquals(listOf("AL", "GB", "US"), RadioCountryList.remove(c, "IT"))
        assertEquals(listOf("IT", "GB", "US"), RadioCountryList.remove(c, "AL")) // Albania is removable…
        assertEquals(listOf("AL"), RadioCountryList.remove(listOf("IT"), "IT")) // …but Radio is never empty
        assertEquals(listOf("AL", "IT"), RadioCountryList.restoreDefault(listOf("IT")))
        assertEquals(listOf("AL", "IT"), RadioCountryList.restoreDefault(listOf("IT", "AL")))
    }

    @Test fun countries_can_be_reordered() {
        val c = listOf("AL", "IT", "GB")
        assertEquals(listOf("AL", "GB", "IT"), RadioCountryList.move(c, "GB", up = true))
        assertEquals(listOf("IT", "AL", "GB"), RadioCountryList.move(c, "AL", up = false))
        assertEquals(c, RadioCountryList.move(c, "AL", up = true)) // already first
    }

    @Test fun the_country_catalog_is_not_a_fixed_short_list() {
        val all = RadioCountries.catalog()
        assertTrue(all.size > 200)
        for (code in listOf("AL", "IT", "GB", "US", "DE", "ES", "FR", "XK", "MK", "GR")) assertTrue(code, all.any { it.code == code })
        assertEquals("Kosovo", RadioCountries.displayName("XK"))
        assertEquals("Albania", RadioCountries.displayName("AL"))
        assertEquals("🇦🇱", RadioCountries.flagOf("AL"))
        assertEquals("🇮🇹", RadioCountries.flagOf("it"))
        val uk = all.first { it.code == "GB" }
        assertTrue(RadioCountries.matches(uk, "uk"))
        assertTrue(RadioCountries.matches(uk, "kingdom"))
        assertTrue(RadioCountries.matches(all.first { it.code == "US" }, "usa"))
        assertFalse(RadioCountries.matches(uk, "italy"))
        // Station counts from the directory are attached when known.
        assertEquals(121, RadioCountries.catalog(mapOf("AL" to 121)).first { it.code == "AL" }.stationCount)
    }

    // ---- Directory ------------------------------------------------------------------------------

    private val directoryJson = """[
      {"stationuuid":"a1","name":"Radio Tirana 1","url_resolved":"https://s.example/rt1","countrycode":"AL","tags":"news,public radio","codec":"MP3","bitrate":128,"votes":50,"lastcheckok":1,"favicon":"https://s.example/l.png"},
      {"stationuuid":"a2","name":"Dead Station","url_resolved":"https://s.example/dead","countrycode":"AL","lastcheckok":0},
      {"stationuuid":"a3","name":"Some TV","url_resolved":"https://s.example/tv","countrycode":"AL","codec":"H.264","lastcheckok":1},
      {"stationuuid":"a4","name":"Club FM","url_resolved":"http://s.example/club","countrycode":"AL","tags":"pop,dance","votes":20,"lastcheckok":1,"favicon":"http://insecure/logo.png"},
      {"stationuuid":"a5","name":"Club FM (copy)","url_resolved":"https://s.example/club","countrycode":"AL","lastcheckok":1},
      {"stationuuid":"a6","name":"Sport Live","url_resolved":"https://s.example/sport","countrycode":"AL","tags":"sport","lastcheckok":1},
      {"stationuuid":"a7","name":"No URL","countrycode":"AL","lastcheckok":1}
    ]"""

    @Test fun only_working_audio_stations_are_listed_once() {
        val list = RadioBrowserParser.stations(directoryJson)
        assertEquals(listOf("a1", "a4", "a6"), list.map { it.id }) // dead, video, duplicate stream and no-URL dropped
        val rt = list.first()
        assertEquals(RadioCategory.NEWS, rt.category)
        assertEquals("https://s.example/l.png", rt.logoUrl)
        assertNull(list[1].logoUrl) // insecure logo not used
        assertEquals(RadioCategory.ENTERTAINMENT, list[2].category)
        assertEquals("MP3 · 128 kbps", rt.qualityLabel)
    }

    @Test fun country_counts_and_click_urls_parse() {
        assertEquals(mapOf("AL" to 121, "IT" to 1885), RadioBrowserParser.countryCounts("""[{"iso_3166_1":"AL","stationcount":121},{"iso_3166_1":"it","stationcount":1885}]"""))
        assertEquals("https://x/y", RadioBrowserParser.clickUrl("""{"ok":true,"url":"https://x/y"}"""))
        assertNull(RadioBrowserParser.clickUrl("""{"ok":false,"url":"https://x/y"}"""))
        assertTrue(RadioBrowserParser.stations("not json").isEmpty())
    }

    // ---- Bundled catalog (verified streams) -------------------------------------------------------

    @Test fun the_bundled_catalog_has_albanian_stations() {
        val f = listOf(File("src/main/assets/radio/stations.json"), File("app/src/main/assets/radio/stations.json")).first { it.isFile }
        val stations = RadioCatalogParser.parse(f.readText())
        val al = stations.filter { it.countryCode == "AL" }
        assertEquals(15, al.size)
        assertTrue(al.any { it.name == "Radio Tirana 1" })
        assertTrue(stations.all { it.curated && (it.streamUrl.startsWith("https://") || it.streamUrl.startsWith("http://")) })
        assertEquals(stations.size, stations.map { it.id }.toSet().size)
    }

    // ---- Player identity & filters ------------------------------------------------------------------

    @Test fun stations_get_stable_negative_player_ids() {
        val id = RadioIds.playerId("963ccae5-0601-11e8-ae97-52543be04c81")
        assertTrue(id < 0)
        assertEquals(id, RadioIds.playerId("963ccae5-0601-11e8-ae97-52543be04c81"))
        assertNotEquals(id, RadioIds.playerId("another"))
        assertEquals("abc", RadioIds.stationIdOf(RadioIds.remoteId("abc")))
        assertNull(RadioIds.stationIdOf("tr-1"))
    }

    private fun st(id: String, cat: RadioCategory, votes: Int, name: String = id, city: String? = null) =
        RadioStation(id, name, "AL", city = city, category = cat, streamUrl = "https://s/$id", votes = votes)

    @Test fun filters_and_sorting_work_on_a_country_list() {
        val list = listOf(st("b", RadioCategory.NEWS, 5), st("a", RadioCategory.MUSIC, 50), st("c", RadioCategory.LOCAL, 1, city = "Saranda"), st("d", RadioCategory.POPULAR, 2))
        assertEquals(listOf("b"), RadioSearch.view(list, RadioFilter.NEWS, RadioSort.POPULAR, emptySet(), emptyList(), "").map { it.id })
        assertEquals(listOf("a", "d"), RadioSearch.view(list, RadioFilter.MUSIC, RadioSort.NAME, emptySet(), emptyList(), "").map { it.id })
        assertEquals(listOf("d", "a", "b", "c"), RadioSearch.view(list, RadioFilter.POPULAR, RadioSort.POPULAR, emptySet(), emptyList(), "").map { it.id })
        assertEquals(listOf("c"), RadioSearch.view(list, RadioFilter.LOCAL, RadioSort.POPULAR, emptySet(), emptyList(), "").map { it.id })
        assertEquals(listOf("a"), RadioSearch.view(list, RadioFilter.FAVORITES, RadioSort.POPULAR, setOf("a"), emptyList(), "").map { it.id })
        assertEquals(listOf("c", "b"), RadioSearch.view(list, RadioFilter.RECENT, RadioSort.POPULAR, emptySet(), listOf("c", "b"), "").map { it.id })
        assertEquals(listOf("c"), RadioSearch.view(list, RadioFilter.ALL, RadioSort.POPULAR, emptySet(), emptyList(), "saranda").map { it.id })
    }
}
