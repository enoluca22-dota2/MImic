package com.example.mimic.data.cloud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import com.example.mimic.BuildConfig
import com.example.mimic.data.DownloadRepository
import com.example.mimic.data.SettingsRepository
import com.example.mimic.data.Song
import com.example.mimic.data.cloud.google.GoogleDriveAuth
import com.example.mimic.data.cloud.google.GoogleDriveMusicSource
import com.example.mimic.data.cloud.onedrive.OneDriveAuth
import com.example.mimic.data.cloud.onedrive.OneDriveMusicSource
import com.example.mimic.data.db.CloudAccountEntity
import com.example.mimic.data.db.CloudDao
import com.example.mimic.data.db.CloudItemEntity
import com.example.mimic.data.sync.CloudSyncPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What the Music Sources screen shows per account. */
sealed interface CloudSyncState {
    data object Idle : CloudSyncState
    data class Syncing(val phase: String) : CloudSyncState
    data class Done(val added: Int, val changed: Int, val removed: Int) : CloudSyncState
    data class Failed(val message: String, val needsSignIn: Boolean) : CloudSyncState
}

/** Library numbers for one account. */
data class CloudAccountStats(val songs: Int, val unsupported: Int, val duplicates: Int, val tagsPending: Int)

/**
 * Google Drive + OneDrive music for MImic (3.1): accounts, catalog sync, tag reading, streaming
 * resolution and offline downloads. Application-scoped (one per process) so the player service,
 * the UI and the background sync worker share the same caches and locks.
 *
 * Bandwidth policy: a sync transfers metadata only (incremental after the first); tags are read
 * with small range requests, two files at a time, only on unmetered networks unless the user
 * allows mobile data; audio is transferred only when the user plays or downloads.
 */
class CloudLibraryRepository(
    private val context: Context,
    private val dao: CloudDao,
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
    val oneDriveAuth: OneDriveAuth = OneDriveAuth(context),
    val googleAuth: GoogleDriveAuth = GoogleDriveAuth(context),
    private val http: CloudHttp = CloudHttp(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Shared with the player's data source (one connection pool, no logging interceptors). */
    val httpClient get() = http.client

    // ---- Tokens: provider SDK caches, or the debug-only mock server's fake tokens ----

    private val googleLastToken = ConcurrentHashMap<String, String>()
    private val mockGeneration = ConcurrentHashMap<String, Int>()

    val tokens = object : CloudTokens {
        override fun token(account: CloudAccountEntity, forceRefresh: Boolean): String {
            if (account.apiBase != null) {
                // Test accounts only exist in debug builds, against a local mock API.
                if (!BuildConfig.DEBUG) throw CloudException.AuthRequired()
                val gen = if (forceRefresh) mockGeneration.merge(account.accountKey, 1, Int::plus)!! else mockGeneration.getOrPut(account.accountKey) { 1 }
                return "mock-$gen"
            }
            return when (CloudProviderType.of(account.provider)) {
                CloudProviderType.ONEDRIVE -> {
                    if (oneDriveAuth.currentAccountId() != account.accountId) throw CloudException.AuthRequired("Sign in to OneDrive again")
                    oneDriveAuth.token(forceRefresh)
                }
                CloudProviderType.GOOGLE_DRIVE -> {
                    val email = account.email ?: throw CloudException.AuthRequired()
                    googleAuth.token(email, forceRefresh, googleLastToken[account.accountKey]).also { googleLastToken[account.accountKey] = it }
                }
                null -> throw CloudException.AuthRequired()
            }
        }
    }

    val oneDrive = OneDriveMusicSource(http, tokens)
    val googleDrive = GoogleDriveMusicSource(http, tokens)

    fun source(provider: CloudProviderType): CloudMusicSource = when (provider) {
        CloudProviderType.ONEDRIVE -> oneDrive
        CloudProviderType.GOOGLE_DRIVE -> googleDrive
    }

    private fun source(account: CloudAccountEntity) = source(CloudProviderType.of(account.provider)!!)

    // ---- Accounts and catalog ----

    val accounts: StateFlow<List<CloudAccountEntity>> = dao.accounts()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val catalogs: Flow<Map<String, Pair<CloudCatalog, Int>>> =
        combine(dao.accounts(), dao.allItems()) { accts, items ->
            val byAccount = items.groupBy { it.accountKey }
            accts.associate { a ->
                val rows = byAccount[a.accountKey].orEmpty()
                a.accountKey to (CloudCatalogMapper.map(a, rows) to rows.count(CloudSyncPlanner::needsTagRead))
            }
        }.conflate().flowOn(Dispatchers.Default)

    /** Every cloud song across connected accounts, ready to merge into the unified library. */
    val songs: Flow<List<Song>> = catalogs.map { m -> m.values.flatMap { it.first.songs } }

    val stats: StateFlow<Map<String, CloudAccountStats>> = catalogs
        .map { m -> m.mapValues { (_, v) -> CloudAccountStats(v.first.songs.size, v.first.unsupportedCount, v.first.duplicateCount, v.second) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val _syncState = MutableStateFlow<Map<String, CloudSyncState>>(emptyMap())
    val syncState: StateFlow<Map<String, CloudSyncState>> = _syncState.asStateFlow()

    private fun setState(key: String, s: CloudSyncState) = _syncState.update { it + (key to s) }

    /** Blocking account lookup for the player's loader thread. */
    fun accountBlocking(key: String): CloudAccountEntity? =
        accounts.value.firstOrNull { it.accountKey == key } ?: kotlinx.coroutines.runBlocking { dao.account(key) }

    // ---- Connecting ----

    /** OneDrive: interactive MSAL sign-in in the browser, then the account is stored. */
    suspend fun connectOneDrive(activity: Activity): CloudAccountEntity {
        val id = oneDriveAuth.signIn(activity)
        return saveAccount(CloudProviderType.ONEDRIVE, id, apiBase = null)
    }

    /** Google: first step; may return a consent UI to launch. */
    suspend fun beginGoogle(): GoogleDriveAuth.Step = googleAuth.authorize(null)

    /** Google: with a granted token, identify the account (about.get) and store it. */
    suspend fun finishGoogle(token: String): CloudAccountEntity {
        val probe = CloudAccountEntity("probe", CloudProviderType.GOOGLE_DRIVE.id, "probe", "", null, null, null, null, 0, null, 0)
        val (name, email, permissionId) = GoogleDriveMusicSource(http, object : CloudTokens {
            override fun token(account: CloudAccountEntity, forceRefresh: Boolean) = token
        }).about(probe)
        return saveAccount(CloudProviderType.GOOGLE_DRIVE, CloudIdentity(permissionId, name.ifBlank { email ?: "Google account" }, email), null)
    }

    fun googleTokenFromConsent(data: Intent?): String = googleAuth.resultFromIntent(data)

    /** Debug builds only: connect a test account served by a local mock API (adb reverse). */
    suspend fun connectMock(provider: CloudProviderType, apiBase: String): CloudAccountEntity {
        check(BuildConfig.DEBUG) { "Mock accounts are debug-only" }
        val identity = when (provider) {
            CloudProviderType.GOOGLE_DRIVE -> {
                val probe = CloudAccountEntity("gdrive:mock", provider.id, "mock", "", null, null, null, null, 0, null, 0, apiBase)
                val (name, email, pid) = googleDrive.about(probe)
                CloudIdentity(pid, "$name (mock test server)", email)
            }
            CloudProviderType.ONEDRIVE -> CloudIdentity("mock", "Mock OneDrive (test server)", "mock@example.invalid")
        }
        return saveAccount(provider, identity, apiBase)
    }

    private suspend fun saveAccount(provider: CloudProviderType, id: CloudIdentity, apiBase: String?): CloudAccountEntity {
        val key = CloudRef.accountKeyOf(provider, id.accountId)
        val old = dao.account(key)
        val acct = (old ?: CloudAccountEntity(
            accountKey = key, provider = provider.id, accountId = id.accountId, displayName = id.displayName,
            email = id.email, folderId = null, folderName = null, syncToken = null, lastSyncAt = 0,
            lastError = null, connectedAt = System.currentTimeMillis(), apiBase = apiBase,
        )).copy(displayName = id.displayName, email = id.email, lastError = null, apiBase = apiBase)
        dao.upsertAccount(acct)
        return acct
    }

    suspend fun listFolders(accountKey: String, parentId: String?): List<CloudFolder> {
        val a = dao.account(accountKey) ?: throw CloudException.AuthRequired()
        return source(a).listFolders(a, parentId)
    }

    /** Pick the music folder; the old tree (if any) is dropped and a full listing starts. */
    suspend fun chooseFolder(accountKey: String, folder: CloudFolder) {
        val a = dao.account(accountKey) ?: return
        dao.upsertAccount(a.copy(folderId = folder.id, folderName = folder.name, syncToken = null, lastError = null))
        dao.clearItems(accountKey)
        sync(accountKey)
    }

    /**
     * Disconnect: sign out / revoke where the provider supports it, then forget the account's
     * file list and sync state. Downloads are kept (they're the user's offline copies) unless
     * [removeDownloads]; favorites and history stay keyed to that account's songs and return if
     * it is reconnected. Nothing in the cloud is touched.
     */
    suspend fun disconnect(accountKey: String, removeDownloads: Boolean) {
        val a = dao.account(accountKey) ?: return
        if (a.apiBase == null) when (CloudProviderType.of(a.provider)) {
            CloudProviderType.ONEDRIVE -> oneDriveAuth.signOut()
            CloudProviderType.GOOGLE_DRIVE -> a.email?.let { googleAuth.revoke(it) }
            null -> Unit
        }
        googleLastToken.remove(accountKey)
        dao.removeAccount(accountKey)
        _syncState.update { it - accountKey }
        if (removeDownloads) downloads.removeAllForAccount(accountKey)
    }

    // ---- Sync ----

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val enrichJobs = ConcurrentHashMap<String, Job>()

    /** Sync every account whose last successful sync is older than [maxAgeMs] (0 = all). */
    suspend fun syncAll(maxAgeMs: Long = 0) {
        val now = System.currentTimeMillis()
        for (a in dao.accountsOnce()) {
            if (a.folderId != null && now - a.lastSyncAt >= maxAgeMs) sync(a.accountKey)
        }
    }

    fun syncAllAsync(maxAgeMs: Long = 0) {
        scope.launch { syncAll(maxAgeMs) }
    }

    /**
     * One account: incremental when a token exists, full otherwise. Changes are applied in one
     * Room transaction only after every page arrived, so an interrupted sync applies nothing and
     * the next one resumes from the same token. Returns false on failure (the error is stored).
     */
    suspend fun sync(accountKey: String): Boolean = locks.getOrPut(accountKey) { Mutex() }.withLock {
        val a = dao.account(accountKey) ?: return false
        val root = a.folderId ?: return false
        setState(accountKey, CloudSyncState.Syncing(if (a.syncToken == null) "Listing your music folder…" else "Checking for changes…"))
        try {
            val src = source(a)
            val changes = src.changes(a)
            val existing = dao.itemsOnce(accountKey).associateBy { it.itemId }
            var plan = CloudSyncPlanner.plan(accountKey, root, changes, existing)
            if (plan.newFolders.isNotEmpty()) {
                // Drive: a folder moved into the music folder brings existing children with it.
                val extra = plan.newFolders.flatMap { src.listTree(a, it) }
                val merged = CloudChanges.Incremental((changes as CloudChanges.Incremental).upserts + extra, changes.removedIds, changes.newToken)
                plan = CloudSyncPlanner.plan(accountKey, root, merged, existing)
            }
            if (plan.replaceAll) dao.replaceItems(accountKey, plan.upserts)
            else {
                if (plan.deletes.isNotEmpty()) plan.deletes.chunked(500).forEach { dao.deleteItems(accountKey, it) }
                if (plan.upserts.isNotEmpty()) plan.upserts.chunked(500).forEach { dao.upsertItems(it) }
            }
            dao.markSynced(accountKey, changes.newToken, System.currentTimeMillis())
            setState(accountKey, CloudSyncState.Done(plan.added, plan.changed, plan.removed))
            enrichAsync(accountKey)
            true
        } catch (e: CloudException) {
            dao.setError(accountKey, e.message)
            setState(accountKey, CloudSyncState.Failed(e.message ?: "Sync failed", e is CloudException.AuthRequired))
            false
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val msg = "Sync failed (${e.javaClass.simpleName})"
            dao.setError(accountKey, msg)
            setState(accountKey, CloudSyncState.Failed(msg, false))
            false
        }
    }

    // ---- Metadata (tags + artwork) ----

    private val reader = CloudMetadataReader(File(context.filesDir, "cloud_art"))
    private val tagPermits = Semaphore(2) // bounded concurrency across all accounts

    private fun unmetered(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return !cm.isActiveNetworkMetered
    }

    fun enrichAsync(accountKey: String) {
        if (enrichJobs[accountKey]?.isActive == true) return
        enrichJobs[accountKey] = scope.launch { enrich(accountKey) }
    }

    private suspend fun enrich(accountKey: String) {
        val a = dao.account(accountKey) ?: return
        if (!unmetered() && !settings.settings.first().cellularTagReading) return
        val rows = dao.itemsOnce(accountKey)

        // 1. cover.jpg / folder.png images in album folders (small, one per folder).
        for (img in rows.filter(CloudSyncPlanner::needsFolderCover)) {
            if (img.size > 8L * 1024 * 1024) continue
            val bytes = runCatching { readRange(a, img.itemId, 0, img.size.toInt()) }.getOrNull() ?: continue
            val uri = withContext(Dispatchers.Default) { reader.saveArt(bytes) }
            dao.upsertItems(listOf(img.copy(artUri = uri, tagsVersion = img.contentVersion)))
        }

        // 2. Embedded tags + artwork, two files at a time, saved in small batches.
        val todo = rows.filter(CloudSyncPlanner::needsTagRead)
        if (todo.isNotEmpty()) setState(accountKey, CloudSyncState.Syncing("Reading song details (${todo.size})…"))
        var offline = false
        for (batch in todo.chunked(8)) {
            if (offline) break
            val updated = coroutineScopeAsync(batch) { row ->
                tagPermits.withPermit {
                    try {
                        val r = reader.read(row.size) { off, len -> readRange(a, row.itemId, off, len) }
                        applyTags(row, r.tags, r.artUri)
                    } catch (e: CloudException.Offline) {
                        offline = true; null
                    } catch (e: CloudException.AuthRequired) {
                        offline = true; null
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        // Unreadable / unusual tags: keep folder + file-name metadata for this version.
                        row.copy(tagsVersion = row.contentVersion)
                    }
                }
            }
            if (updated.isNotEmpty()) dao.upsertItems(updated)
        }

        // 3. OneDrive already supplied tags; fetch one thumbnail per album still without art.
        val fresh = dao.itemsOnce(accountKey)
        val catalog = CloudCatalogMapper.map(a, fresh)
        val byRemote = fresh.associateBy { it.itemId }
        val albumsWithoutArt = catalog.songs.filter { it.albumArtUri == null }.groupBy { it.albumKey }
        for ((_, songs) in albumsWithoutArt) {
            val itemId = songs.firstNotNullOfOrNull { s -> CloudRef.parse(s.remoteId!!)?.itemId } ?: continue
            val row = byRemote[itemId] ?: continue
            if (row.tagsVersion?.startsWith(CloudSyncPlanner.FACET) != true) continue // tried already / not OneDrive
            val bytes = runCatching { source(a).thumbnail(a, itemId) }.getOrNull()
            val uri = bytes?.let { withContext(Dispatchers.Default) { reader.saveArt(it) } }
            dao.upsertItems(listOf(row.copy(artUri = uri, tagsVersion = CloudSyncPlanner.FACET_ART + row.contentVersion)))
        }
        if (_syncState.value[accountKey] is CloudSyncState.Syncing) setState(accountKey, CloudSyncState.Idle)
    }

    private suspend fun <T, R : Any> coroutineScopeAsync(items: List<T>, block: suspend (T) -> R?): List<R> =
        kotlinx.coroutines.coroutineScope { items.map { async(Dispatchers.IO) { block(it) } }.awaitAll().filterNotNull() }

    private fun applyTags(row: CloudItemEntity, t: CloudAudioTags, art: String?) = row.copy(
        title = t.title, artist = t.artist, albumArtist = t.albumArtist, album = t.album,
        trackNo = t.trackNo, discNo = t.discNo, durationMs = t.durationMs, genre = t.genre, year = t.year,
        artUri = art, tagsVersion = row.contentVersion,
    )

    // ---- Reading bytes (tags, downloads); the player uses CloudDataSource ----

    /**
     * Open the file (optionally a byte range) at its current location. A rejected location —
     * expired OneDrive URL, revoked token, 401/403/404/410 — is re-resolved once.
     */
    fun open(account: CloudAccountEntity, itemId: String, offset: Long = 0, length: Long = -1): Response {
        val src = source(account)
        var force = false
        repeat(2) { attempt ->
            val target = src.resolveStream(account, itemId, force)
            val req = Request.Builder().url(target.url).apply {
                target.headers.forEach { (k, v) -> header(k, v) }
                if (offset > 0 || length > 0) header("Range", "bytes=$offset-" + (if (length > 0) "${offset + length - 1}" else ""))
            }.build()
            val resp = try {
                http.client.newCall(req).execute()
            } catch (e: java.io.IOException) {
                throw CloudHttp.classify(e)
            }
            if (resp.isSuccessful) return resp
            val code = resp.code
            resp.close()
            if (attempt == 0 && code in REFRESH_CODES) {
                force = true
                (src as? OneDriveMusicSource)?.invalidate(account, itemId)
            } else {
                throw if (code == 401) CloudException.AuthRequired() else CloudException.Http(code, "The cloud file couldn't be read (HTTP $code)")
            }
        }
        throw CloudException.Http(0, "The cloud file couldn't be read")
    }

    private fun readRange(account: CloudAccountEntity, itemId: String, offset: Long, length: Int): ByteArray =
        open(account, itemId, offset, length.toLong()).use { r ->
            val body = r.body ?: throw CloudException.Http(r.code, "Empty response")
            // A server that ignores Range answers 200 with the whole file: read only what we need.
            val stream = body.byteStream()
            if (r.code == 200 && offset > 0) stream.skip(offset)
            val out = ByteArray(length)
            var n = 0
            while (n < length) {
                val k = stream.read(out, n, length - n)
                if (k < 0) break
                n += k
            }
            if (n == length) out else out.copyOf(n)
        }

    /** Network policy for a user-started download. Null = allowed, else the reason it isn't. */
    suspend fun downloadBlockedReason(): String? =
        if (settings.settings.first().wifiOnlyDownloads && !unmetered()) "Downloads are set to Wi-Fi only" else null

    /**
     * Download one cloud song through the shared atomic/verified download path. Never deletes
     * anything in the cloud; a failure leaves no partial file behind.
     */
    suspend fun download(song: Song, onProgress: (Long, Long) -> Unit): Result<Long> = withContext(Dispatchers.IO) {
        val ref = song.remoteId?.let(CloudRef::parse) ?: return@withContext Result.failure(IllegalArgumentException("Not a cloud song"))
        val a = dao.account(ref.accountKey) ?: return@withContext Result.failure(CloudException.AuthRequired("Reconnect this cloud account to download"))
        val row = dao.item(ref.accountKey, ref.itemId) ?: return@withContext Result.failure(CloudException.Http(404, "This file is no longer in your music folder"))
        if (row.size > 0 && downloads.freeBytes() < row.size + MIN_FREE_BYTES) {
            return@withContext Result.failure(java.io.IOException("Not enough free storage on this phone"))
        }
        val resp = try {
            open(a, row.itemId)
        } catch (e: CloudException) {
            return@withContext Result.failure(e)
        }
        resp.use {
            val body = it.body ?: return@withContext Result.failure(CloudException.Http(it.code, "Empty response"))
            val total = if (row.size > 0) row.size else body.contentLength()
            downloads.saveCloudDownload(
                song = song, accountKey = a.accountKey, extension = AudioFormats.extension(row.name),
                body = body.byteStream(), expectedSize = row.size, hash = row.hash,
                onProgress = { done -> onProgress(done, total) },
            )
        }
    }

    companion object {
        private val REFRESH_CODES = setOf(401, 403, 404, 410)
        private const val MIN_FREE_BYTES = 200L * 1024 * 1024
    }
}
