package com.example.mimic.cast

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.example.mimic.data.art.TrackArtwork
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A tiny HTTP server on the phone's Wi-Fi address that lets a Cast receiver (TV) fetch the songs
 * being cast (MImic 4.1). A TV can't open the phone's content:// URIs or private files, and must
 * never receive the Navidrome stream URL (it carries the login token) — so the TV gets
 * `http://<phone-ip>:<port>/m/<random token>` instead:
 *
 *  - only items registered for the current cast session are served, each under an unguessable
 *    128-bit token; everything else is 404;
 *  - Navidrome songs are fetched by the PHONE with its credentials and relayed (Range passed
 *    through), so no token or password reaches the TV or the network beyond the phone;
 *  - the server runs only while casting and stops (clearing all tokens) when the session ends.
 * Audio crosses the local network unencrypted (Cast receivers fetch over plain HTTP from a LAN
 * host); nothing is reachable from outside the LAN.
 */
class LanMediaServer(private val context: Context, private val http: OkHttpClient) {

    sealed interface Source {
        data class ContentUri(val uri: Uri) : Source
        data class LocalFile(val file: File) : Source
        /** Fetched by the phone (the URL may contain credentials; it is never sent to the TV). */
        data class Relay(val url: String) : Source
        /** Artwork rendered on the phone (per-track key, file or http cover). */
        data class Artwork(val key: String) : Source
    }

    private data class Entry(val source: Source, val mime: String)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile var baseUrl: String? = null
        private set

    /** Starts (if needed) on the Wi-Fi address; null when the phone has no LAN IPv4 address. */
    @Synchronized
    fun start(): String? {
        baseUrl?.let { return it }
        val ip = lanAddress() ?: return null
        val s = ServerSocket(0, 16, ip)
        socket = s
        baseUrl = "http://${ip.hostAddress}:${s.localPort}"
        pool.execute { acceptLoop(s) }
        return baseUrl
    }

    /** Stops serving and forgets every token. */
    @Synchronized
    fun stop() {
        entries.clear()
        runCatching { socket?.close() }
        socket = null
        baseUrl = null
    }

    /** Registers [source] and returns the URL the TV should load, or null if not started. */
    fun publish(source: Source, mime: String): String? {
        val base = baseUrl ?: return null
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        entries[token] = Entry(source, mime)
        return "$base/m/$token"
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val client = runCatching { s.accept() }.getOrNull() ?: break
            pool.execute { runCatching { handle(client) }.onFailure { Log.d(TAG, "request failed: ${it.javaClass.simpleName}") } }
        }
    }

    private fun handle(client: Socket) = client.use { sock ->
        sock.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val path = parts.getOrNull(1).orEmpty()
        val out = sock.getOutputStream()
        val token = path.removePrefix("/m/").substringBefore('?')
        val entry = if (path.startsWith("/m/")) entries[token] else null
        if (entry == null || (method != "GET" && method != "HEAD")) return writeStatus(out, 404, "Not Found")
        val range = parseRange(headers["range"])
        when (val src = entry.source) {
            is Source.Relay -> relay(out, method, src.url, headers["range"], entry.mime)
            is Source.Artwork -> {
                val bytes = artworkBytes(src.key) ?: return writeStatus(out, 404, "Not Found")
                serveBytes(out, method, bytes, "image/jpeg")
            }
            is Source.ContentUri -> {
                val length = context.contentResolver.openAssetFileDescriptor(src.uri, "r")?.use { it.length } ?: -1L
                serveStream(out, method, entry.mime, length, range) { context.contentResolver.openInputStream(src.uri) }
            }
            is Source.LocalFile -> serveStream(out, method, entry.mime, src.file.length(), range) { src.file.inputStream() }
        }
    }

    private fun serveStream(out: OutputStream, method: String, mime: String, length: Long, range: LongRange?, open: () -> InputStream?) {
        if (length <= 0) {
            // Unknown length: stream it whole, no ranges.
            val input = open() ?: return writeStatus(out, 404, "Not Found")
            writeHead(out, 200, "OK", mime, null, null, null)
            if (method == "GET") input.use { it.copyTo(out) }
            out.flush(); return
        }
        val r = range?.let { LongRange(it.first, minOf(if (it.last < 0) length - 1 else it.last, length - 1)) }
        if (r != null && r.first >= length) return writeStatus(out, 416, "Range Not Satisfiable")
        val start = r?.first ?: 0L
        val end = r?.last ?: (length - 1)
        writeHead(out, if (r != null) 206 else 200, if (r != null) "Partial Content" else "OK", mime, end - start + 1, if (r != null) "bytes $start-$end/$length" else null, "bytes")
        if (method == "GET") {
            val input = open() ?: return
            input.use { inp ->
                var skip = start
                while (skip > 0) { val n = inp.skip(skip); if (n <= 0) break; skip -= n }
                var remaining = end - start + 1
                val buf = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        }
        out.flush()
    }

    private fun relay(out: OutputStream, method: String, url: String, range: String?, mime: String) {
        val req = Request.Builder().url(url).apply { if (range != null) header("Range", range) }.build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return writeStatus(out, if (resp.code == 416) 416 else 502, "Upstream ${resp.code}")
            val body = resp.body ?: return writeStatus(out, 502, "Upstream empty")
            val len = body.contentLength().takeIf { it >= 0 }
            writeHead(out, resp.code, if (resp.code == 206) "Partial Content" else "OK", resp.header("Content-Type") ?: mime, len, resp.header("Content-Range"), "bytes")
            if (method == "GET") body.byteStream().use { it.copyTo(out) }
            out.flush()
        }
    }

    private fun serveBytes(out: OutputStream, method: String, bytes: ByteArray, mime: String) {
        writeHead(out, 200, "OK", mime, bytes.size.toLong(), null, null)
        if (method == "GET") out.write(bytes)
        out.flush()
    }

    private fun artworkBytes(key: String): ByteArray? = runCatching {
        when {
            key.startsWith(TrackArtwork.SCHEME) -> runBlocking { TrackArtwork.load(context, key, 720) }?.let(::jpeg)
            key.startsWith("file:") -> Uri.parse(key).path?.let { File(it).takeIf(File::isFile)?.readBytes() }
            key.startsWith("http") -> http.newCall(Request.Builder().url(key).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
            else -> null
        }
    }.getOrNull()

    private fun jpeg(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()

    private fun writeStatus(out: OutputStream, code: Int, text: String) {
        out.write("HTTP/1.1 $code $text\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun writeHead(out: OutputStream, code: Int, text: String, mime: String, length: Long?, contentRange: String?, acceptRanges: String?) {
        val sb = StringBuilder("HTTP/1.1 $code $text\r\nContent-Type: $mime\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n")
        if (length != null) sb.append("Content-Length: $length\r\n")
        if (contentRange != null) sb.append("Content-Range: $contentRange\r\n")
        if (acceptRanges != null) sb.append("Accept-Ranges: $acceptRanges\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    companion object {
        private const val TAG = "MImicCastServer"

        /** "bytes=a-b" / "bytes=a-" → range (last = -1 for open-ended); null if absent/invalid (pure). */
        fun parseRange(header: String?): LongRange? {
            val h = header?.trim()?.lowercase() ?: return null
            if (!h.startsWith("bytes=") || h.contains(',')) return null
            val spec = h.removePrefix("bytes=")
            val a = spec.substringBefore('-').toLongOrNull() ?: return null
            val b = spec.substringAfter('-', "").let { if (it.isEmpty()) -1L else it.toLongOrNull() ?: return null }
            if (b != -1L && b < a) return null
            return LongRange(a, b)
        }

        /** The phone's Wi-Fi / Ethernet IPv4 address (site-local only — never a public one). */
        fun lanAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual && !it.name.startsWith("tun") && !it.name.startsWith("rmnet") }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }.getOrNull()
    }
}
