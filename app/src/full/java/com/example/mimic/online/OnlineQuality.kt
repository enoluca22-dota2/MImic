package com.example.mimic.online

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import com.example.mimic.online.data.innertube.StreamResolver

/**
 * Online audio settings (Settings → Online music). YouTube Music offers two real tiers: a ~48–64
 * kbps data-saving stream and its best stream (Opus ~160 kbps or AAC ~128 kbps). Nothing it sends
 * is lossless, so MImic never labels it so. Separate choices for Wi-Fi and mobile data.
 */
object OnlineQuality {
    enum class Stream(val label: String, val detail: String, val maxKbps: Int) {
        BEST("Best available", "Opus ~160 kbps or AAC ~128 kbps (lossy)", Int.MAX_VALUE),
        DATA_SAVER("Data saver", "About 64 kbps", 64),
    }

    /** What a download saves. Real containers only — nothing is renamed to look like another format. */
    enum class DownloadFormat(val label: String, val detail: String) {
        AUTO("Automatic", "Best quality YouTube offers (usually Opus in WebM)"),
        M4A("AAC (.m4a)", "Plays everywhere; slightly lower quality than Opus"),
        OPUS("Opus (.webm)", "Best quality on YouTube Music"),
        MP3("MP3 (converted)", "Re-encoded to MP3 320 kbps on the phone — larger, never better than the source"),
    }

    private const val PREFS = "online_music"
    private lateinit var prefs: SharedPreferences
    private var connectivity: ConnectivityManager? = null

    fun install(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        connectivity = context.getSystemService(ConnectivityManager::class.java)
        StreamResolver.maxKbps = { current().maxKbps }
    }

    private fun isMetered(): Boolean = runCatching { connectivity?.isActiveNetworkMetered }.getOrNull() ?: false

    fun current(): Stream = if (isMetered()) mobile else wifi

    var wifi: Stream
        get() = enumOr(prefs.getString("stream_wifi", null), Stream.BEST)
        set(v) = prefs.edit().putString("stream_wifi", v.name).apply()

    var mobile: Stream
        get() = enumOr(prefs.getString("stream_mobile", null), Stream.BEST)
        set(v) = prefs.edit().putString("stream_mobile", v.name).apply()

    var downloadFormat: DownloadFormat
        get() = enumOr(prefs.getString("download_format", null), DownloadFormat.AUTO)
        set(v) = prefs.edit().putString("download_format", v.name).apply()

    /** Downloads wait for an unmetered network (Wi-Fi) unless turned off. */
    var wifiOnlyDownloads: Boolean
        get() = prefs.getBoolean("wifi_only_downloads", false)
        set(v) = prefs.edit().putBoolean("wifi_only_downloads", v).apply()

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
