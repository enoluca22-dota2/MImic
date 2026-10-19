package com.example.mimic.downloader.diagnostics

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.example.mimic.BuildConfig
import com.example.mimic.downloader.data.provider.ProviderException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet6Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** One timed step of an analysis ("parse link", "wait for engine", "run yt-dlp", …). */
data class DiagStage(val name: String, val startOffsetMs: Long, val durationMs: Long, val outcome: String)

/** One recorded link analysis (or failed download). Every text field is already sanitized. */
data class DiagAttempt(
    val kind: String,
    val startedAt: Long,
    val finishedAt: Long,
    val link: String,
    val parse: String,
    val network: String,
    val stages: List<DiagStage>,
    val category: DiagCategory,
    val shownMessage: String?,
    val errorType: String?,
    val exitInfo: String?,
    val facts: YtDlpFacts,
    val formats: String?,
    val log: List<String>,
) {
    val durationMs: Long get() = finishedAt - startedAt
}

/**
 * Collects what happens during one link analysis. It travels in the coroutine context (see
 * [DownloadDiagnostics.trace]) so the analyzer and the yt-dlp provider can add to it without
 * changing their signatures; when diagnostics are off there is no trace and nothing is recorded.
 */
class AnalysisTrace(rawInput: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AnalysisTrace>

    val startedAt = System.currentTimeMillis()
    val link: String = DiagnosticText.link(rawInput.trim().substringBefore(' ').substringBefore('\n'))
    @Volatile var parse: String = "not parsed"
    @Volatile var formats: String? = null
    @Volatile var stderr: String? = null
    @Volatile var exitInfo: String? = null
    private val stages = mutableListOf<DiagStage>()

    @Synchronized fun stage(name: String, startedAtMs: Long, outcome: String) {
        val now = System.currentTimeMillis()
        stages += DiagStage(name, startedAtMs - startedAt, now - startedAtMs, outcome)
    }

    @Synchronized fun stages(): List<DiagStage> = stages.toList()
}

/**
 * Settings → Advanced → Download Diagnostics (MImic 4.3.1). Records the last [MAX_ATTEMPTS] link
 * analyses on the phone only (app-private storage, never sent anywhere) and builds a readable TXT
 * report the user can share with Android's share sheet. No cookies, headers, tokens, passwords,
 * IP addresses or full links are stored: everything passes through [DiagnosticText.clean].
 */
class DownloadDiagnostics(private val context: Context) {

    private val prefs = context.getSharedPreferences("download_diagnostics", Context.MODE_PRIVATE)
    private val file = File(File(context.filesDir, "diagnostics"), "attempts.json")
    private val lock = Any()

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _attempts = MutableStateFlow(load())
    val attempts: StateFlow<List<DiagAttempt>> = _attempts.asStateFlow()

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    /** A new trace for one analysis, or null while recording is off. */
    fun trace(rawInput: String): AnalysisTrace? = if (_enabled.value) AnalysisTrace(rawInput) else null

    fun finish(trace: AnalysisTrace, error: Throwable?) {
        val pe = error as? ProviderException
        // yt-dlp's own text only: a ProviderException's message is the on-screen text, listed separately.
        val raw = if (error is ProviderException) error.detail else error?.message
        val stderr = trace.stderr ?: raw
        record(
            DiagAttempt(
                kind = "Analyze link",
                startedAt = trace.startedAt,
                finishedAt = System.currentTimeMillis(),
                link = trace.link,
                parse = trace.parse,
                network = networkSummary(),
                stages = trace.stages(),
                category = DiagCategory.of(error, DiagnosticText.withoutDebug(stderr)),
                shownMessage = error?.message?.let(DiagnosticText::clean),
                errorType = error?.javaClass?.simpleName,
                exitInfo = trace.exitInfo,
                facts = DiagnosticText.facts(stderr),
                formats = trace.formats,
                log = DiagnosticText.tail(stderr),
            ),
        )
    }

    /** A queued download that failed for good (after its automatic retries). */
    fun downloadFailed(sourceUrl: String, error: ProviderException) {
        if (!_enabled.value) return
        val now = System.currentTimeMillis()
        record(
            DiagAttempt(
                kind = "Download", startedAt = now, finishedAt = now, link = DiagnosticText.link(sourceUrl), parse = "",
                network = networkSummary(), stages = emptyList(), category = DiagCategory.of(error, error.detail),
                shownMessage = error.message?.let(DiagnosticText::clean), errorType = error.javaClass.simpleName, exitInfo = null,
                facts = DiagnosticText.facts(error.detail), formats = null, log = DiagnosticText.tail(error.detail, 30),
            ),
        )
    }

    fun clear() = synchronized(lock) {
        _attempts.value = emptyList()
        runCatching { file.delete() }
        Unit
    }

    private fun record(a: DiagAttempt) = synchronized(lock) {
        val list = (listOf(a) + _attempts.value).take(MAX_ATTEMPTS)
        _attempts.value = list
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(JSONArray(list.map(::toJson)).toString())
        }.onFailure { Log.w(TAG, "Couldn't save diagnostics", it) }
        Unit
    }

    // ---- Report ---------------------------------------------------------------------------------

    /** The full TXT report (environment now + every recorded attempt, newest first). */
    fun report(ytDlpVersion: String?, lastUpdateCheck: Long, engineState: String): String = buildString {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val zone = TimeZone.getDefault().getDisplayName(false, TimeZone.SHORT, Locale.US)
        appendLine("MImic — Download Diagnostics report")
        appendLine("Created: ${time.format(Date())} ($zone)")
        appendLine("Links are shortened to the video id; IP addresses, cookies, headers, tokens and passwords are removed.")
        appendLine()
        appendLine("DEVICE")
        appendLine("  Model: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("  Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            (if (Build.VERSION.SDK_INT >= 23) ", security patch ${Build.VERSION.SECURITY_PATCH}" else ""))
        appendLine("  ABIs: ${Build.SUPPORTED_ABIS.joinToString()} · memory page size: ${pageSizeKb()} KB")
        appendLine("  Language/region: ${Locale.getDefault().toLanguageTag()} · time zone: ${TimeZone.getDefault().id}")
        appendLine("  Battery saver: ${if (powerSave()) "on" else "off"}")
        appendLine()
        appendLine("APP")
        appendLine("  MImic ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}), ${BuildConfig.FLAVOR} edition")
        appendLine()
        appendLine("DOWNLOAD ENGINE")
        appendLine("  Engine: $engineState")
        appendLine("  yt-dlp: ${ytDlpVersion ?: "the version bundled with this MImic (not updated yet)"}")
        appendLine("  Last yt-dlp update check: ${if (lastUpdateCheck > 0) time.format(Date(lastUpdateCheck)) else "never"}")
        _attempts.value.firstOrNull { it.facts.environment.isNotEmpty() }?.let { a ->
            appendLine("  Details from the newest yt-dlp run:")
            a.facts.environment.forEach { appendLine("    $it") }
        }
        appendLine()
        appendLine("NETWORK (now)")
        appendLine("  ${networkSummary()}")
        appendLine()
        val list = _attempts.value
        appendLine("ATTEMPTS (${list.size}, newest first; MImic keeps the last $MAX_ATTEMPTS)")
        if (list.isEmpty()) appendLine("  None recorded yet. Analyze a YouTube link, then share the report again.")
        list.forEachIndexed { i, a ->
            appendLine()
            val outcome = when (a.category) {
                DiagCategory.OK -> "OK"
                DiagCategory.CANCELLED -> "STOPPED"
                else -> "FAILED"
            }
            appendLine("#${i + 1} ${a.kind} — $outcome: ${a.category.label}")
            appendLine("  When: ${time.format(Date(a.startedAt))} → ${time.format(Date(a.finishedAt))} (${a.durationMs} ms)")
            appendLine("  Link: ${a.link}")
            if (a.parse.isNotBlank()) appendLine("  Link recognized as: ${a.parse}")
            appendLine("  Network: ${a.network}")
            if (a.stages.isNotEmpty()) {
                appendLine("  Stages:")
                a.stages.forEach { s -> appendLine("    +${s.startOffsetMs} ms  ${s.name.padEnd(18)} ${s.durationMs} ms  ${s.outcome}") }
            }
            appendLine("  Meaning: ${a.category.meaning}")
            a.shownMessage?.let { appendLine("  Message shown: $it") }
            a.errorType?.let { appendLine("  Error type: $it") }
            a.exitInfo?.let { appendLine("  yt-dlp exit: $it") }
            a.formats?.let { appendLine("  Formats: $it") }
            a.facts.version?.let { appendLine("  yt-dlp version used: $it") }
            if (a.facts.clients.isNotEmpty()) appendLine("  YouTube clients tried: ${a.facts.clients.joinToString()}")
            if (a.facts.playability.isNotEmpty()) appendLine("  YouTube's answer per client: ${a.facts.playability.joinToString()}")
            if (a.facts.httpStatuses.isNotEmpty()) appendLine("  HTTP status codes: ${a.facts.httpStatuses.joinToString()}")
            a.facts.lastStep?.let { appendLine("  Last step reached: $it") }
            if (a.facts.errors.isNotEmpty()) {
                appendLine("  yt-dlp errors:")
                a.facts.errors.forEach { appendLine("    $it") }
            }
            if (a.facts.warnings.isNotEmpty()) {
                appendLine("  yt-dlp warnings:")
                a.facts.warnings.forEach { appendLine("    $it") }
            }
            if (a.log.isNotEmpty()) {
                appendLine("  yt-dlp log (last ${a.log.size} lines, cleaned):")
                a.log.forEach { appendLine("    $it") }
            }
        }
    }

    /** Writes [text] to an app-private cache file for the share sheet (res/xml/file_paths.xml). */
    fun writeReportFile(text: String): File {
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date())
        return File(dir, "MImic-download-diagnostics-$stamp.txt").apply { writeText(text) }
    }

    // ---- Environment --------------------------------------------------------------------------

    /** Transport, VPN, Private DNS, IPv6 — the network differences that matter for YouTube's checks. */
    fun networkSummary(): String = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val net = cm.activeNetwork ?: return "offline"
        val caps = cm.getNetworkCapabilities(net)
        val lp = cm.getLinkProperties(net)
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("Wi-Fi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("mobile data")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("Ethernet")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("Bluetooth")
        }.ifEmpty { listOf("other") }
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val metered = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
        val privateDns = if (Build.VERSION.SDK_INT >= 28 && lp != null) {
            when {
                lp.privateDnsServerName != null -> "on (custom provider)"
                lp.isPrivateDnsActive -> "on (automatic)"
                else -> "off"
            }
        } else "unknown"
        val ipv6 = lp?.linkAddresses.orEmpty().any { la ->
            val a = la.address
            a is Inet6Address && !a.isLinkLocalAddress && !a.isSiteLocalAddress && !a.isLoopbackAddress
        }
        val dataSaver = when (cm.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "on"
            else -> "off"
        }
        "${transports.joinToString(" + ")} · VPN ${if (vpn) "ON" else "off"} · Private DNS $privateDns · IPv6 ${if (ipv6) "yes" else "no"} · " +
            "${if (metered) "metered" else "unmetered"} · internet check ${if (validated) "passed" else "NOT passed"} · data saver $dataSaver"
    }.getOrElse { "unknown (${it.javaClass.simpleName})" }

    private fun pageSizeKb(): Long = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) / 1024 }.getOrDefault(-1)

    private fun powerSave(): Boolean = runCatching { context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)

    // ---- Storage --------------------------------------------------------------------------------

    private fun toJson(a: DiagAttempt) = JSONObject().apply {
        put("kind", a.kind); put("start", a.startedAt); put("end", a.finishedAt); put("link", a.link); put("parse", a.parse)
        put("network", a.network); put("category", a.category.name); put("shown", a.shownMessage); put("type", a.errorType)
        put("exit", a.exitInfo); put("formats", a.formats)
        put("stages", JSONArray(a.stages.map { JSONObject().put("n", it.name).put("o", it.startOffsetMs).put("d", it.durationMs).put("r", it.outcome) }))
        put("version", a.facts.version); put("env", JSONArray(a.facts.environment)); put("clients", JSONArray(a.facts.clients)); put("play", JSONArray(a.facts.playability))
        put("http", JSONArray(a.facts.httpStatuses)); put("errors", JSONArray(a.facts.errors)); put("warnings", JSONArray(a.facts.warnings))
        put("last", a.facts.lastStep); put("log", JSONArray(a.log))
    }

    private fun load(): List<DiagAttempt> = runCatching {
        if (!file.exists()) return emptyList()
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun strs(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            fun opt(k: String) = if (o.isNull(k)) null else o.optString(k)
            DiagAttempt(
                kind = o.optString("kind"), startedAt = o.optLong("start"), finishedAt = o.optLong("end"), link = o.optString("link"),
                parse = o.optString("parse"), network = o.optString("network"),
                stages = o.optJSONArray("stages")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { DiagStage(it.optString("n"), it.optLong("o"), it.optLong("d"), it.optString("r")) } }.orEmpty(),
                category = runCatching { DiagCategory.valueOf(o.optString("category")) }.getOrDefault(DiagCategory.UNKNOWN),
                shownMessage = opt("shown"), errorType = opt("type"), exitInfo = opt("exit"),
                facts = YtDlpFacts(
                    version = opt("version"), environment = strs("env"), clients = strs("clients"), playability = strs("play"),
                    httpStatuses = o.optJSONArray("http")?.let { a -> (0 until a.length()).map { a.getInt(it) } }.orEmpty(),
                    errors = strs("errors"), warnings = strs("warnings"), lastStep = opt("last"),
                ),
                formats = opt("formats"), log = strs("log"),
            )
        }
    }.getOrElse { Log.w(TAG, "Diagnostics file unreadable; starting empty", it); emptyList() }

    companion object {
        private const val TAG = "DownloadDiagnostics"
        private const val KEY_ENABLED = "enabled"
        const val MAX_ATTEMPTS = 15

        /** A public, long-standing video used by the "Run a test" button. */
        const val TEST_URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    }
}
