package com.example.mimic.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.example.mimic.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

/**
 * Settings → App Updates (MImic 4.0, adapted from ENAGELYUCA's updater): check GitHub Releases →
 * download → verify → hand to Android's installer. Never installs silently — Android always shows
 * its own confirmation, and the user approves it.
 *
 * Trust model: the release JSON, notes and checksum file all come from GitHub and are treated as
 * untrusted. What makes an APK trustworthy is that it is signed with the certificate pinned in
 * this build ([UpdateConfig.trustedCertSha256]) — the same one as the installed app. SHA256SUMS
 * only guards against corrupted downloads.
 */
class UpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data object NotConfigured : State
        data object Checking : State
        data class UpToDate(val current: String, val checkedAt: Long) : State
        data class Available(val release: ReleaseInfo, val current: String) : State
        data class Downloading(val release: ReleaseInfo, val downloadedBytes: Long, val totalBytes: Long?) : State {
            val fraction: Float? get() = totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toDouble() / it).toFloat().coerceIn(0f, 1f) }
        }
        data class Verifying(val release: ReleaseInfo) : State
        /** Verified; waiting for the installer (or for "Install unknown apps" to be allowed). */
        data class ReadyToInstall(val release: ReleaseInfo, val apk: File, val needsPermission: Boolean) : State
        /** [apk] is set when a verified download exists and only the install step failed. */
        data class Failed(val message: String, val release: ReleaseInfo?, val apk: File? = null) : State
    }

    private val _state = MutableStateFlow<State>(if (UpdateConfig.isConfigured) State.Idle else State.NotConfigured)
    val state: StateFlow<State> = _state.asStateFlow()

    private val prefs = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private var job: Job? = null

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    var autoCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO, value).apply()
            UpdateCheckWorker.schedule(context, value)
        }

    var channel: UpdateChannel
        // Default: a beta build follows the Beta channel (otherwise it would never see the next
        // beta); a stable build follows Stable. The user's explicit choice always wins.
        get() = runCatching { UpdateChannel.valueOf(prefs.getString(KEY_CHANNEL, null) ?: defaultChannel.name) }.getOrDefault(defaultChannel)
        set(value) {
            prefs.edit().putString(KEY_CHANNEL, value.name).apply()
            if (state.value is State.Available || state.value is State.UpToDate) _state.value = State.Idle
        }

    private val defaultChannel: UpdateChannel
        get() = if (AppVersion.parse(currentVersion)?.isPreRelease == true) UpdateChannel.BETA else UpdateChannel.STABLE

    val lastCheckedAt: Long get() = prefs.getLong(KEY_LAST_CHECK, 0L)

    /** Only HTTPS to GitHub's own hosts — every redirect hop included. */
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addNetworkInterceptor(Interceptor { chain ->
            val url = chain.request().url
            if (!url.isHttps || !UpdateConfig.isTrustedHost(url.host)) throw UntrustedHost(url.host)
            chain.proceed(chain.request())
        })
        .build()

    // ---- Check -------------------------------------------------------------------------------

    /** Settings → "Check for Updates". Always allowed. */
    fun checkNow() {
        if (!UpdateConfig.isConfigured) {
            _state.value = State.NotConfigured
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = State.Checking
            _state.value = try {
                val release = fetchLatest()
                markChecked()
                if (release != null && isNewer(release)) State.Available(release, currentVersion)
                else State.UpToDate(currentVersion, System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed: ${e.javaClass.simpleName}")
                State.Failed(describe(e), null)
            }
        }
    }

    /** Background check (WorkManager, at most daily): the newer release, or null. Never downloads. */
    suspend fun checkInBackground(): ReleaseInfo? {
        if (!UpdateConfig.isConfigured || !autoCheckEnabled) return null
        val release = fetchLatest()
        markChecked()
        return release?.takeIf { isNewer(it) && prefs.getString(KEY_NOTIFIED, null) != it.tag }
            ?.also { prefs.edit().putString(KEY_NOTIFIED, it.tag).apply() }
    }

    private fun markChecked() = prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()

    private fun isNewer(release: ReleaseInfo): Boolean {
        val current = AppVersion.parse(currentVersion) ?: return true
        // Compare what Android compares, too: the versionCode the release name implies.
        return release.version > current && AppVersion.versionCodeFor(release.version.toString()) > BuildConfig.VERSION_CODE
    }

    private suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(UpdateConfig.releasesApi)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "MImic/$currentVersion (Android)")
            .build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                200 -> ReleaseParser.pickLatest(resp.body?.string().orEmpty(), channel, UpdateConfig.edition, Build.SUPPORTED_ABIS.toList())
                404 -> throw UpdateProblem("No public release repository at ${UpdateConfig.releasesPage}.")
                403, 429 -> throw UpdateProblem("GitHub is limiting requests right now. Try again in a little while.")
                in 500..599 -> throw UpdateProblem("GitHub is unavailable right now (HTTP ${resp.code}). Try again later.")
                else -> throw UpdateProblem("GitHub answered with HTTP ${resp.code}. Try again later.")
            }
        }
    }

    fun dismiss() {
        if (state.value is State.Downloading || state.value is State.Verifying) return
        _state.value = if (UpdateConfig.isConfigured) State.Idle else State.NotConfigured
    }

    // ---- Download + verify --------------------------------------------------------------------

    /** "Download update" (also "Try again" after a failure). */
    fun download() {
        val failed = state.value as? State.Failed
        val release = when (val s = state.value) {
            is State.Available -> s.release
            is State.Failed -> s.release ?: return
            else -> return
        }
        job?.cancel()
        failed?.apk?.takeIf { it.exists() }?.let { apk ->
            job = scope.launch { startInstall(apk, release) }
            return
        }
        job = scope.launch {
            val target = File(updatesDir(), release.apk.name)
            try {
                downloadApk(release, target)
                _state.value = State.Verifying(release)
                verify(release, target)
                startInstall(target, release)
            } catch (e: CancellationException) {
                File(target.path + ".part").delete()
                _state.value = State.Available(release, currentVersion)
                throw e
            } catch (e: VerificationFailed) {
                target.delete()
                Log.w(TAG, "Update verification FAILED at '${e.check}': ${e.technical}")
                _state.value = State.Failed("${e.check}: ${e.message}", release)
            } catch (e: Exception) {
                target.delete()
                Log.w(TAG, "Update download failed: ${e.javaClass.simpleName}")
                _state.value = State.Failed(describe(e), release)
            }
        }
    }

    fun cancelDownload() {
        job?.cancel()
    }

    private suspend fun downloadApk(release: ReleaseInfo, target: File) = withContext(Dispatchers.IO) {
        val part = File(target.path + ".part")
        part.delete()
        target.delete()
        val expectedSize = release.apk.sizeBytes
        if (expectedSize != null && updatesDir().usableSpace < expectedSize * 2) {
            throw UpdateProblem("Not enough free storage to download the update (${expectedSize / 1_048_576} MB needed). Free some space and try again.")
        }
        val req = Request.Builder().url(release.apk.downloadUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "MImic/$currentVersion (Android)")
            .build()
        http.newCall(req).execute().use { resp ->
            when {
                resp.isSuccessful -> Unit
                resp.code == 404 -> throw UpdateProblem("${release.apk.name} is no longer on GitHub. Check for updates again.")
                else -> throw UpdateProblem("GitHub answered with HTTP ${resp.code} for ${release.apk.name}. Try again.")
            }
            val body = resp.body ?: throw IOException("empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: expectedSize
            var downloaded = 0L
            var lastEmit = 0L
            _state.value = State.Downloading(release, 0, total)
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= 200) {
                            lastEmit = now
                            _state.value = State.Downloading(release, downloaded, total)
                        }
                    }
                }
            }
            if (total != null && downloaded != total) {
                throw UpdateProblem("Download incomplete: received $downloaded of $total bytes. Try again.")
            }
            _state.value = State.Downloading(release, downloaded, total ?: downloaded)
        }
        if (!part.renameTo(target)) throw IOException("Couldn't store the update")
    }

    /**
     * Every check an update must pass before it reaches the installer. Each failure names the
     * check; technical detail (hashes, digests — public values) goes to Logcat only.
     */
    private suspend fun verify(release: ReleaseInfo, apk: File) = withContext(Dispatchers.IO) {
        // 1. It is a ZIP (every APK is), not an HTML error page.
        val magic = apk.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        if (!(magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() && magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte())) {
            throw VerificationFailed("Invalid APK", "the downloaded file isn't an APK. Try again.", "magic ${magic.joinToString(" ") { "%02x".format(it) }}")
        }
        // 2. Integrity: SHA-256 listed in the release's SHA256SUMS.txt.
        val actualSha = sha256(apk)
        val sums = http.newCall(Request.Builder().url(release.checksums.downloadUrl).header("User-Agent", "MImic/$currentVersion (Android)").build())
            .execute().use { r ->
                if (!r.isSuccessful) throw UpdateProblem("Couldn't read ${UpdateConfig.CHECKSUM_ASSET} (HTTP ${r.code}). Try again.")
                r.body?.string()?.take(64_000).orEmpty()
            }
        val expected = ReleaseParser.checksumFor(sums, release.apk.name)
            ?: throw VerificationFailed("Checksum missing", "${UpdateConfig.CHECKSUM_ASSET} doesn't list ${release.apk.name}.", "no line for ${release.apk.name}")
        if (!expected.equals(actualSha, ignoreCase = true)) {
            throw VerificationFailed("Checksum mismatch", "the download is corrupted. Try again.", "expected $expected, got $actualSha")
        }
        // 3. Android can parse it.
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.path, flags)
            ?: throw VerificationFailed("APK could not be parsed", "Android can't read the downloaded APK. Try again.", "getPackageArchiveInfo null")
        val installed = pm.getPackageInfo(context.packageName, flags)
        // 4. It is this app.
        if (archive.packageName != context.packageName) {
            throw VerificationFailed("Wrong app", "the APK is ${archive.packageName}, not ${context.packageName}.", "package mismatch")
        }
        // 5. Newer versionCode (what Android compares) — never a downgrade or reinstall.
        if (archive.longVersionCodeCompat() <= installed.longVersionCodeCompat()) {
            throw VerificationFailed(
                "Not newer",
                "the APK's versionCode (${archive.longVersionCodeCompat()}) isn't higher than the installed one (${installed.longVersionCodeCompat()}).",
                "archive ${archive.longVersionCodeCompat()} <= installed ${installed.longVersionCodeCompat()}",
            )
        }
        // 6. Runs on this Android version and CPU.
        val minSdk = archive.applicationInfo?.minSdkVersion ?: 1
        if (minSdk > Build.VERSION.SDK_INT) {
            throw VerificationFailed("Needs newer Android", "this update requires Android API $minSdk; this phone has ${Build.VERSION.SDK_INT}.", "minSdk $minSdk")
        }
        val apkAbis = ZipFile(apk).use { zip ->
            zip.entries().asSequence().mapNotNull { e -> e.name.takeIf { it.startsWith("lib/") }?.split('/')?.getOrNull(1) }.toSet()
        }
        if (apkAbis.isNotEmpty() && apkAbis.none { it in Build.SUPPORTED_ABIS }) {
            throw VerificationFailed("Unsupported CPU", "the APK is built for ${apkAbis.joinToString()}.", "apk $apkAbis device ${Build.SUPPORTED_ABIS.toList()}")
        }
        // 7. Authenticity: signed with the pinned release certificate AND the installed app's.
        val archiveCerts = signatures(archive)
        val installedCerts = signatures(installed)
        if (archiveCerts != setOf(UpdateConfig.trustedCertSha256)) {
            throw VerificationFailed(
                "Untrusted signature",
                "the APK isn't signed with MImic's release certificate. It was not installed.",
                "apk $archiveCerts, trusted ${UpdateConfig.trustedCertSha256}",
            )
        }
        if (archiveCerts != installedCerts) {
            throw VerificationFailed(
                "Signing certificate mismatch",
                "this installed copy is signed with a different key, so Android can't update it in place. " +
                    "Install the release once from ${UpdateConfig.releasesPage} (back up your data first).",
                "apk $archiveCerts, installed $installedCerts",
            )
        }
        Log.i(TAG, "Update ${release.tag} verified (sha256 + package + versionCode + ABI + pinned certificate)")
    }

    // ---- Install ------------------------------------------------------------------------------

    private suspend fun startInstall(apk: File, release: ReleaseInfo) {
        val allowed = canInstall()
        _state.value = State.ReadyToInstall(release, apk, needsPermission = !allowed)
        if (allowed) install(apk, release)
    }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Opens "Install unknown apps" for MImic; the user comes back and taps Install. */
    fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            val ready = state.value as? State.ReadyToInstall
            _state.value = State.Failed(
                "Allow it in Settings → Apps → MImic → Install unknown apps, then tap Try again.",
                ready?.release,
                ready?.apk,
            )
        }
    }

    fun refreshInstallPermission() {
        val ready = state.value as? State.ReadyToInstall ?: return
        if (ready.needsPermission && canInstall()) _state.value = ready.copy(needsPermission = false)
    }

    fun installReady() {
        val ready = state.value as? State.ReadyToInstall ?: return
        if (!canInstall()) {
            _state.value = ready.copy(needsPermission = true)
            return
        }
        scope.launch { install(ready.apk, ready.release) }
    }

    /** PackageInstaller session; Android shows its own confirmation, then replaces the app in place. */
    private suspend fun install(apk: File, release: ReleaseInfo) = withContext(Dispatchers.IO) {
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(callback.intentSender)
            }
            _state.value = State.ReadyToInstall(release, apk, needsPermission = false)
        } catch (e: Exception) {
            // Some ROMs refuse installer sessions; the classic installer screen via our FileProvider works there.
            Log.w(TAG, "PackageInstaller session failed; using the installer screen", e)
            try {
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                _state.value = State.ReadyToInstall(release, apk, needsPermission = false)
            } catch (e2: Exception) {
                _state.value = State.Failed("Android's installer could not be opened. Tap Try again.", release, apk)
            }
        }
    }

    /** From [UpdateInstallReceiver]: the installer ended without installing. The verified APK is kept. */
    internal fun onInstallFailed(message: String?) {
        val ready = state.value as? State.ReadyToInstall
        _state.value = State.Failed(message ?: "The update wasn't installed.", ready?.release, ready?.apk)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun updatesDir() = File(context.cacheDir, "updates").apply { mkdirs() }

    /** Start-up: abandoned/installed update files are useless. */
    fun cleanUpOldDownloads() {
        scope.launch(Dispatchers.IO) { File(context.cacheDir, "updates").deleteRecursively() }
    }

    private fun describe(e: Exception): String = when (e) {
        is UpdateProblem -> e.message!!
        is UntrustedHost -> "The download was redirected to an untrusted site (${e.host}) and was stopped."
        is IOException -> "Couldn't reach GitHub. Check your internet connection and try again."
        else -> "Something went wrong (${e.javaClass.simpleName}). Try again."
    }

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    private fun signatures(info: PackageInfo): Set<String> {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else arrayOf(it.signingCertificateHistory.last()) }
        } else {
            @Suppress("DEPRECATION") info.signatures
        }
        return raw.orEmpty().map { sig -> MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private class UntrustedHost(val host: String) : IOException("untrusted host $host")
    private class UpdateProblem(message: String) : Exception(message)
    private class VerificationFailed(val check: String, message: String, val technical: String) : Exception(message)

    companion object {
        private const val TAG = "MImicUpdate"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_NOTIFIED = "notified_tag"
        private const val KEY_AUTO = "auto_check"
        private const val KEY_CHANNEL = "channel"

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
