package com.example.mimic.online

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.example.mimic.BuildConfig
import com.example.mimic.data.online.OnlineCollection
import com.example.mimic.data.online.OnlineCollectionKind
import com.example.mimic.data.online.OnlineCollectionPage
import com.example.mimic.data.online.OnlineDownloadState
import com.example.mimic.data.online.OnlineIds
import com.example.mimic.data.online.OnlineSearchFilter
import com.example.mimic.data.online.OnlineSearchResults
import com.example.mimic.data.online.OnlineTrack
import com.example.mimic.online.auth.AuthStore
import com.example.mimic.online.data.Http
import com.example.mimic.online.data.MonotonicClock
import com.example.mimic.online.data.TrackLog
import com.example.mimic.online.data.YtMusicRepository
import com.example.mimic.online.data.innertube.InnerTubeXResolver
import com.example.mimic.online.data.innertube.Innertube
import com.example.mimic.online.data.innertube.StreamResolver
import com.example.mimic.online.data.innertube.potoken.PoTokenGenerator
import com.example.mimic.online.data.innertube.potoken.PoTokenWebView
import com.example.mimic.online.data.model.BrowseItem
import com.example.mimic.online.data.model.BrowseType
import com.example.mimic.online.data.model.SearchFilter
import com.example.mimic.online.data.model.SearchResult
import com.example.mimic.online.data.model.ShelfItem
import com.example.mimic.online.data.model.Song
import com.example.mimic.online.data.model.artworkAt
import com.example.mimic.online.data.model.durationMillis
import com.example.mimic.online.playback.ChunkedDataSource
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Full edition: online music inside MImic — YouTube Music search, album / artist / playlist pages,
 * streaming through MImic's own player, and downloads. The search, page parsing and stream
 * resolution are BitChord's (GPL-3.0), see the `online` package; this object is the only surface
 * the rest of MImic sees, and the standard edition has a stub with the same API.
 *
 * Playback: a queued online song has the URI `mimic-yt://track?v=<id>`. [wrap] resolves it the
 * moment the player opens it (InnerTubeX → NewPipe fallback → a byte probe that proves the URL
 * serves audio), then reads googlevideo in bounded ranges, which is what keeps throughput at line
 * rate instead of playback speed. Every other URI goes to MImic's existing data source unchanged.
 */
object OnlineMusic {
    const val AVAILABLE = true
    private const val TAG = "MImicOnline"

    /** Upper bound for resolving one track on the player's loader thread. */
    private const val RESOLVE_TIMEOUT_MS = 25_000L
    private const val STREAM_CHUNK_BYTES = 2L * 1024 * 1024

    @Volatile private var auth: AuthStore? = null
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        MonotonicClock.nowMs = SystemClock::elapsedRealtime
        TrackLog.echo = { level, tag, message, error ->
            if (BuildConfig.DEBUG) when (level) {
                'D' -> Log.d(tag, message, error)
                'I' -> Log.i(tag, message, error)
                'W' -> Log.w(tag, message, error)
                else -> Log.e(tag, message, error)
            }
        }
        TrackLog.headerLines = {
            listOf("build: ${BuildConfig.VERSION_NAME}", "device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        }
        InnerTubeXResolver.debugLogging = BuildConfig.DEBUG
        OnlineQuality.install(app)
        // A connected YouTube Music account (optional) signs Innertube requests; anonymous otherwise.
        val store = runCatching { AuthStore(app) }.getOrNull()
        auth = store
        Innertube.cookie = store?.cookie?.takeIf { AuthStore.hasApiSid(it) }
        // BotGuard PoTokens minted in a hidden WebView — what lets the web clients past the
        // "confirm you're not a bot" wall without any account (BitChord's approach).
        val prefs = app.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)
        val webViewSupported by lazy { runCatching { CookieManager.getInstance() }.isSuccess }
        val poTokens = PoTokenGenerator(
            createMinter = { PoTokenWebView.getNewPoTokenGenerator(app) },
            available = { webViewSupported },
        )
        InnerTubeXResolver.init(
            filesDir = app.filesDir,
            store = object : InnerTubeXResolver.ConfigStore {
                override fun getString(key: String): String? = prefs.getString(key, null)
                override fun getLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
                override fun putString(key: String, value: String) = prefs.edit().putString(key, value).apply()
                override fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
            },
            poTokenProvider = poTokens.asTokenProvider(),
        )
    }

    // ---- Search & pages ----

    suspend fun search(query: String, filter: OnlineSearchFilter): Result<OnlineSearchResults> {
        val f = when (filter) {
            OnlineSearchFilter.ALL -> SearchFilter.ALL
            OnlineSearchFilter.SONGS -> SearchFilter.SONGS
            OnlineSearchFilter.ALBUMS -> SearchFilter.ALBUMS
            OnlineSearchFilter.ARTISTS -> SearchFilter.ARTISTS
            OnlineSearchFilter.PLAYLISTS -> SearchFilter.PLAYLISTS
        }
        val catalog = YtMusicRepository.searchPage(query, f).map { page ->
            val tracks = ArrayList<OnlineTrack>()
            val collections = ArrayList<OnlineCollection>()
            for (row in page.rows) when (row) {
                is SearchResult.TopTrack -> tracks += row.song.toTrack()
                is SearchResult.Track -> tracks += row.song.toTrack()
                is SearchResult.Browse -> row.item.toCollection()?.let(collections::add)
            }
            OnlineSearchResults(tracks.distinctBy { it.videoId }, collections.distinctBy { it.browseId })
        }
        // YouTube Music's song catalog is region-limited (e.g. not offered in Albania). When it has
        // no tracks for a song search, use YouTube's own results so the user still gets music.
        val wantsTracks = filter == OnlineSearchFilter.ALL || filter == OnlineSearchFilter.SONGS
        val r = catalog.getOrNull()
        if (!wantsTracks || (r != null && r.tracks.isNotEmpty())) return catalog
        val yt = runCatching { YouTubeSearch.search(query) }
        return yt.fold(
            onSuccess = { p -> Result.success(OnlineSearchResults(p.tracks.distinctBy { it.videoId }, r?.collections.orEmpty(), true, p.nextToken)) },
            onFailure = { e -> if (r != null) Result.success(r) else Result.failure(e) },
        )
    }

    /** The next page of tracks for a [OnlineSearchResults.nextToken]. */
    suspend fun searchMore(token: String): Result<OnlineSearchResults> = runCatching {
        val p = YouTubeSearch.more(token)
        OnlineSearchResults(p.tracks, emptyList(), true, p.nextToken)
    }

    suspend fun suggestions(input: String): List<String> =
        YtMusicRepository.searchSuggestions(input).getOrDefault(emptyList())

    suspend fun collection(c: OnlineCollection): Result<OnlineCollectionPage> = when (c.kind) {
        OnlineCollectionKind.ARTIST -> YtMusicRepository.artistPage(c.browseId).map { page ->
            OnlineCollectionPage(
                browseId = c.browseId, kind = c.kind,
                title = page.name ?: c.title,
                subtitle = page.monthlyListenerCount ?: page.subscriberCountText ?: c.subtitle,
                artworkUrl = page.thumbnailUrl.artworkAt(720) ?: c.artworkUrl,
                tracks = page.songs.map { it.toTrack() }.distinctBy { it.videoId }.ifEmpty {
                    // Region without YouTube Music's catalog: the artist's own YouTube results.
                    runCatching { YouTubeSearch.search(page.name ?: c.title).tracks }.getOrDefault(emptyList())
                },
                related = page.sections.flatMap { shelf -> shelf.items.mapNotNull { it.toCollection() } }.distinctBy { it.browseId },
            )
        }
        else -> YtMusicRepository.allSongs(c.browseId).map { songs ->
            // Album rows carry no artwork of their own (the release is billed once in the header),
            // so every track of an album gets the album's cover — never another track's.
            val albumArt = c.artworkUrl
            val tracks = songs.mapIndexed { i, s ->
                val t = s.toTrack()
                if (c.kind == OnlineCollectionKind.ALBUM) {
                    t.copy(
                        artworkUrl = t.artworkUrl ?: albumArt,
                        album = t.album ?: c.title,
                        albumId = t.albumId ?: c.browseId,
                        trackNo = s.trackNumber ?: (i + 1),
                    )
                } else t
            }
            OnlineCollectionPage(c.browseId, c.kind, c.title, c.subtitle, c.artworkUrl, tracks.distinctBy { it.videoId })
        }
    }

    // ---- Playback ----

    /**
     * MImic's player data source with online URIs resolved. Every non-online URI reaches
     * [upstream] exactly as before, so phone files, downloads, Navidrome and radio are unaffected.
     */
    @OptIn(UnstableApi::class)
    fun wrap(upstream: DataSource.Factory): DataSource.Factory {
        val online = ResolvingDataSource.Factory(
            ChunkedDataSource.Factory(OkHttpDataSource.Factory(Http.client), STREAM_CHUNK_BYTES),
        ) { spec ->
            val videoId = OnlineIds.videoIdOfUri(spec.uri.toString()) ?: return@Factory spec
            val url = try {
                runBlocking(TrackLog.about(videoId)) { withTimeout(RESOLVE_TIMEOUT_MS) { StreamResolver.resolve(videoId) } }
            } catch (e: TimeoutCancellationException) {
                OnlineDiagnostics.record(videoId, "timeout", e)
                throw IOException("YouTube Music took too long to answer. Check the connection and try again.", e)
            } catch (e: StreamResolver.PermanentlyUnplayableException) {
                OnlineDiagnostics.record(videoId, "unplayable", e)
                throw e
            } catch (e: IOException) {
                OnlineDiagnostics.record(videoId, "network", e)
                throw e
            } catch (e: Exception) {
                OnlineDiagnostics.record(videoId, "resolve", e)
                throw IOException("Couldn't open this track: ${e.message}", e)
            }
            OnlineDiagnostics.recordSuccess(videoId)
            spec.buildUpon().setUri(Uri.parse(url)).setHttpRequestHeaders(StreamResolver.mediaHeadersFor(url)).build()
        }
        return SchemeRouting(online, upstream)
    }

    /** Sends `mimic-yt://` to the online chain and everything else to MImic's own source. */
    @OptIn(UnstableApi::class)
    private class SchemeRouting(private val online: DataSource.Factory, private val other: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = object : DataSource {
            private val listeners = ArrayList<TransferListener>()
            private var active: DataSource? = null
            override fun addTransferListener(transferListener: TransferListener) { listeners += transferListener }
            override fun open(dataSpec: DataSpec): Long {
                val ds = (if (dataSpec.uri.scheme == OnlineIds.SCHEME) online else other).createDataSource()
                listeners.forEach(ds::addTransferListener)
                active = ds
                return ds.open(dataSpec)
            }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active!!.read(buffer, offset, length)
            override fun getUri(): Uri? = active?.uri
            override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
            override fun close() {
                try { active?.close() } finally { active = null }
            }
        }
    }

    // ---- Downloads (WorkManager; see OnlineDownloadWorker) ----

    fun download(context: Context, track: OnlineTrack) = OnlineDownloadWorker.enqueue(context, track)

    fun cancelDownload(context: Context, videoId: String) = OnlineDownloadWorker.cancel(context, videoId)

    /** videoId → state, for every queued / running / failed online download. */
    fun downloadStates(context: Context): Flow<Map<String, OnlineDownloadState>> = OnlineDownloadWorker.states(context)

    // ---- YouTube Music account (optional, separate from any MImic account) ----

    /** True while a YouTube Music session cookie is stored on this device. */
    val youTubeMusicConnected: Boolean get() = auth?.cookie?.let(AuthStore::hasApiSid) == true

    internal fun onYouTubeMusicSession(cookie: String?) {
        val store = auth ?: return
        if (cookie == null) store.signOut() else store.onNewSession(cookie)
        Innertube.cookie = cookie?.takeIf { AuthStore.hasApiSid(it) }
        StreamResolver.onSessionChanged()
    }

    // ---- Mapping ----

    private fun Song.toTrack() = OnlineTrack(
        videoId = videoId,
        title = title,
        artist = artist,
        album = albumName,
        albumId = albumId,
        artistId = artistId,
        durationMs = durationMillis(),
        artworkUrl = artworkAt(544),
        isVideo = isVideo,
        trackNo = trackNumber ?: 0,
    )

    private fun BrowseItem.toCollection(): OnlineCollection? = OnlineCollection(
        browseId = browseId,
        kind = when (type) {
            BrowseType.ALBUM -> OnlineCollectionKind.ALBUM
            BrowseType.ARTIST -> OnlineCollectionKind.ARTIST
            BrowseType.PLAYLIST -> OnlineCollectionKind.PLAYLIST
            BrowseType.OTHER -> return null
        },
        title = title, subtitle = subtitle, artworkUrl = thumbnailUrl.artworkAt(544),
    )

    private fun ShelfItem.toCollection(): OnlineCollection? {
        val id = browseId ?: return null
        val kind = when {
            id.startsWith("MPREb") -> OnlineCollectionKind.ALBUM
            id.startsWith("UC") -> OnlineCollectionKind.ARTIST
            id.startsWith("VL") || id.startsWith("PL") || id.startsWith("RD") || id.startsWith("OLAK") -> OnlineCollectionKind.PLAYLIST
            else -> return null
        }
        return OnlineCollection(id, kind, title, subtitle, thumbnailUrl.artworkAt(544))
    }
}
