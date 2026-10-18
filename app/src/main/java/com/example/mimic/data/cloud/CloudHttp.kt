package com.example.mimic.data.cloud

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Shared HTTP plumbing for the cloud APIs: one OkHttp client, Retry-After aware throttling,
 * exponential backoff with jitter for transient failures, and one silent token refresh on 401.
 * Nothing here logs URLs or headers — OneDrive download URLs and bearer tokens are credentials.
 */
class CloudHttp(
    val client: OkHttpClient = defaultClient(),
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val maxAttempts: Int = 4,
    /** Longest Retry-After MImic will wait inline; beyond it the sync fails with Throttled. */
    private val maxInlineWaitSec: Long = 120,
) {
    /** A client that does NOT follow redirects (OneDrive /content returns a 302 to resolve). */
    val noRedirects: OkHttpClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /**
     * Run an authorized GET. [token] supplies a bearer token (force = true after a 401). Returns
     * the successful response (caller closes it); throws [CloudException] otherwise.
     */
    suspend fun get(
        url: String,
        token: suspend (force: Boolean) -> String?,
        http: OkHttpClient = client,
        accept: (Int) -> Boolean = { it in 200..299 },
    ): Response {
        var refreshed = false
        var attempt = 0
        while (true) {
            attempt++
            val bearer = token(refreshed)
            val req = Request.Builder().url(url).apply { if (bearer != null) header("Authorization", "Bearer $bearer") }.build()
            val resp = try {
                http.newCall(req).execute()
            } catch (e: IOException) {
                if (attempt >= maxAttempts) throw classify(e)
                sleep(backoffMs(attempt))
                continue
            }
            val code = resp.code
            when {
                accept(code) -> return resp
                code == 401 && !refreshed -> { resp.close(); refreshed = true; attempt--; continue }
                code == 401 -> { resp.close(); throw CloudException.AuthRequired() }
                code == 429 || code == 503 || (code >= 500 && code != 501) || (code == 403 && isRateLimit403(resp)) -> {
                    val retryAfter = retryAfterSeconds(resp.header("Retry-After"))
                    resp.close()
                    if (attempt >= maxAttempts) throw if (code == 429) CloudException.Throttled(retryAfter ?: 60) else CloudException.Http(code, "The provider is unavailable (HTTP $code). Try again later.")
                    if (retryAfter != null && retryAfter > maxInlineWaitSec) throw CloudException.Throttled(retryAfter)
                    sleep(if (retryAfter != null) retryAfter * 1000 else backoffMs(attempt))
                }
                else -> {
                    val msg = errorMessage(resp)
                    resp.close()
                    throw CloudException.Http(code, msg)
                }
            }
        }
    }

    suspend fun getJson(url: String, token: suspend (Boolean) -> String?): JSONObject =
        get(url, token).use { r -> JSONObject(r.body?.string() ?: throw CloudException.Http(r.code, "Empty response")) }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        fun backoffMs(attempt: Int): Long = (500L shl (attempt - 1).coerceAtMost(5)) + Random.nextLong(0, 250)

        /** Retry-After as seconds: either delta-seconds or an HTTP date. */
        fun retryAfterSeconds(value: String?, now: Long = System.currentTimeMillis()): Long? {
            if (value.isNullOrBlank()) return null
            value.trim().toLongOrNull()?.let { return it.coerceAtLeast(0) }
            return runCatching {
                val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                ((fmt.parse(value.trim())!!.time - now) / 1000).coerceAtLeast(0)
            }.getOrNull()
        }

        fun classify(e: IOException): CloudException = when (e) {
            is UnknownHostException, is ConnectException -> CloudException.Offline()
            is SocketTimeoutException -> CloudException.Offline("The connection timed out")
            else -> CloudException.Offline("Network error (${e.javaClass.simpleName})")
        }

        /** Google Drive reports per-user rate limits as 403 rateLimitExceeded / userRateLimitExceeded. */
        private fun isRateLimit403(resp: Response): Boolean {
            val body = runCatching { resp.peekBody(16_384).string() }.getOrNull().orEmpty()
            return body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded")
        }

        /** Provider error text from a Graph / Drive error body, never the request URL. */
        private fun errorMessage(resp: Response): String {
            val body = runCatching { resp.peekBody(16_384).string() }.getOrNull().orEmpty()
            val msg = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
            return if (!msg.isNullOrBlank()) "${msg.take(200)} (HTTP ${resp.code})" else "HTTP ${resp.code}"
        }
    }
}
