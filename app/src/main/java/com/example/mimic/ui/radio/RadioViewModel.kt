package com.example.mimic.ui.radio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mimic.MimicApplication
import com.example.mimic.data.radio.RadioCountries
import com.example.mimic.data.radio.RadioCountry
import com.example.mimic.data.radio.RadioStation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What Radio shows for one country. */
sealed interface StationsState {
    data object Loading : StationsState
    /** [note] explains a partial result (e.g. directory offline → bundled stations only). */
    data class Loaded(val stations: List<RadioStation>, val note: String? = null) : StationsState
    data class Error(val message: String) : StationsState
}

/**
 * Radio screens (MImic 4.2): the user's countries (Albania by default), each country's stations
 * (bundled + Radio Browser, cached), favorites and recently played, and the country catalog.
 * Playback itself goes through the app's one Media3 player (PlayerViewModel.playRadio).
 */
class RadioViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as MimicApplication).container.radioRepository

    val countries: StateFlow<List<String>> = repo.countries.countries
        .stateIn(viewModelScope, SharingStarted.Eagerly, listOf(RadioCountries.DEFAULT))
    val favorites: StateFlow<List<RadioStation>> = repo.favorites.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recent: StateFlow<List<RadioStation>> = repo.recent.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val favoriteIds: StateFlow<Set<String>> = repo.favorites.map { l -> l.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _stations = MutableStateFlow<Map<String, StationsState>>(emptyMap())
    val stations: StateFlow<Map<String, StationsState>> = _stations.asStateFlow()
    private val jobs = HashMap<String, Job>()

    /** Loads [code]'s stations once (cached in the repository for 30 min); [refresh] reloads. */
    fun load(code: String, refresh: Boolean = false) {
        if (!refresh && _stations.value[code] is StationsState.Loaded) return
        if (jobs[code]?.isActive == true && !refresh) return
        jobs[code]?.cancel()
        if (_stations.value[code] !is StationsState.Loaded) _stations.update { it + (code to StationsState.Loading) }
        jobs[code] = viewModelScope.launch {
            val featured = repo.featured(code)
            repo.countryStations(code, refresh).fold(
                onSuccess = { list ->
                    val dirOk = list.size > featured.size || featured.isEmpty()
                    _stations.update {
                        it + (code to StationsState.Loaded(list, if (!dirOk && featured.isNotEmpty()) "Showing the built-in stations — the station directory couldn't be reached." else null))
                    }
                },
                onFailure = {
                    _stations.update { s ->
                        s + (code to StationsState.Error("Couldn't load stations for ${RadioCountries.displayName(code)}. Check your internet connection and tap Refresh."))
                    }
                },
            )
        }
    }

    // ---- Country catalog (+) ----

    private val _catalog = MutableStateFlow(RadioCountries.catalog())
    val catalog: StateFlow<List<RadioCountry>> = _catalog.asStateFlow()

    fun loadCatalogCounts() {
        viewModelScope.launch {
            val counts = repo.countryCounts()
            if (counts.isNotEmpty()) _catalog.value = RadioCountries.catalog(counts)
        }
    }

    fun addCountry(code: String) = viewModelScope.launch { repo.countries.add(code) }
    fun removeCountry(code: String) = viewModelScope.launch { repo.countries.remove(code) }
    fun moveCountry(code: String, up: Boolean) = viewModelScope.launch { repo.countries.move(code, up) }
    fun restoreDefault() = viewModelScope.launch { repo.countries.restoreDefault() }

    // ---- Search (directory) ----

    private val _searchResults = MutableStateFlow<List<RadioStation>?>(null)
    val searchResults: StateFlow<List<RadioStation>?> = _searchResults.asStateFlow()
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    fun searchDirectory(query: String, countryCode: String?) {
        if (query.isBlank()) { _searchResults.value = null; return }
        viewModelScope.launch {
            _searching.value = true
            _searchResults.value = runCatching { repo.search(query, countryCode) }.getOrElse { emptyList() }
            _searching.value = false
        }
    }

    fun clearSearch() { _searchResults.value = null }
}
