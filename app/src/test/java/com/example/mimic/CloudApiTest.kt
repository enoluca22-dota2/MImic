package com.example.mimic

import com.example.mimic.data.cloud.CloudChanges
import com.example.mimic.data.cloud.CloudException
import com.example.mimic.data.cloud.CloudHttp
import com.example.mimic.data.cloud.CloudTokens
import com.example.mimic.data.cloud.google.GoogleDriveMusicSource
import com.example.mimic.data.cloud.onedrive.OneDriveMusicSource
import com.example.mimic.data.db.CloudAccountEntity
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Provider API handling against a mock HTTP server (NOT the real providers): paging, delta
 * tokens, 410 resync, Retry-After throttling, token refresh on 401, OneDrive 302 resolution.
 */
class CloudApiTest {

    private lateinit var server: MockWebServer
    private val sleeps = ArrayList<Long>()
    private val http by lazy { CloudHttp(sleep = { sleeps += it }) }
    private val tokenCalls = ArrayList<Boolean>()
    private val tokens = object : CloudTokens {
        override fun token(account: CloudAccountEntity, forceRefresh: Boolean): String {
            tokenCalls += forceRefresh
            return if (forceRefresh) "fresh" else "stale"
        }
    }

    private fun account(provider: String, token: String? = null) = CloudAccountEntity(
        "$provider:x", provider, "x", "X", "x@y", "ROOT", "Music", token, 0, null, 0,
        apiBase = server.url("/api").toString(),
    )

    @Before fun start() { server = MockWebServer(); server.start() }
    @After fun stop() { server.shutdown() }

    private fun route(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = handler(request) }
    }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test fun retry_after_is_respected_then_the_call_succeeds() = runBlocking {
        val n = AtomicInteger()
        route { if (n.incrementAndGet() <= 2) json("{}", 429).setHeader("Retry-After", "7") else json("""{"ok":true}""") }
        val body = http.getJson(server.url("/x").toString()) { "t" }
        assertTrue(body.getBoolean("ok"))
        assertEquals(listOf(7000L, 7000L), sleeps)
    }

    @Test fun drive_403_rate_limit_is_retried_but_other_403s_are_not() = runBlocking {
        val n = AtomicInteger()
        route { if (n.incrementAndGet() == 1) json("""{"error":{"errors":[{"reason":"userRateLimitExceeded"}],"message":"slow down"}}""", 403) else json("""{"ok":true}""") }
        assertTrue(http.getJson(server.url("/x").toString()) { "t" }.getBoolean("ok"))
        assertEquals(1, sleeps.size)
        route { json("""{"error":{"message":"The user does not have sufficient permissions"}}""", 403) }
        try {
            http.getJson(server.url("/y").toString()) { "t" }
            fail()
        } catch (e: CloudException.Http) {
            assertEquals(403, e.code)
        }
        assertEquals(1, sleeps.size)
    }

    @Test fun long_retry_after_fails_fast_as_throttled_instead_of_hanging() = runBlocking {
        route { json("{}", 429).setHeader("Retry-After", "3600") }
        try {
            http.getJson(server.url("/x").toString()) { "t" }
            fail()
        } catch (e: CloudException.Throttled) {
            assertEquals(3600L, e.retryAfterSec)
        }
        assertTrue(sleeps.isEmpty())
    }

    @Test fun server_errors_back_off_exponentially_and_then_report() = runBlocking {
        route { json("{}", 503) }
        try {
            http.getJson(server.url("/x").toString()) { "t" }
            fail()
        } catch (e: CloudException.Http) {
            assertEquals(503, e.code)
        }
        assertEquals(3, sleeps.size)
        assertTrue(sleeps[1] > sleeps[0] && sleeps[2] > sleeps[1])
    }

    @Test fun expired_token_is_refreshed_once_then_auth_required() = runBlocking {
        route { r -> if (r.getHeader("Authorization") == "Bearer fresh") json("""{"ok":1}""") else json("{}", 401) }
        val src = OneDriveMusicSource(http, tokens)
        // listFolders -> 401 with the stale token -> silent refresh -> success
        route { r -> if (r.getHeader("Authorization") == "Bearer fresh") json("""{"value":[]}""") else json("{}", 401) }
        assertTrue(src.listFolders(account("onedrive"), null).isEmpty())
        assertEquals(listOf(false, true), tokenCalls)
        route { json("{}", 401) }
        try {
            src.listFolders(account("onedrive"), null)
            fail()
        } catch (e: CloudException.AuthRequired) { /* user must sign in again */ }
    }

    @Test fun onedrive_delta_pages_are_followed_and_the_delta_link_kept() = runBlocking {
        route { r ->
            val p = r.path!!
            when {
                p.contains("/items/ROOT/delta") && !p.contains("page=2") -> json(
                    """{"value":[{"id":"F","name":"A","folder":{"childCount":1},"parentReference":{"id":"ROOT"}}],
                       "@odata.nextLink":"${server.url("/api/me/drive/items/ROOT/delta?page=2")}"}""",
                )
                p.contains("page=2") -> json(
                    """{"value":[{"id":"S","name":"x.mp3","size":5,"cTag":"c1","parentReference":{"id":"F"},
                        "file":{"mimeType":"audio/mpeg","hashes":{"sha1Hash":"ABC"}},
                        "audio":{"title":"T","artist":"Ar","album":"Al","track":3,"duration":1234}}],
                       "@odata.deltaLink":"${server.url("/api/me/drive/items/ROOT/delta?token=9")}"}""",
                )
                else -> json("{}", 404)
            }
        }
        val c = OneDriveMusicSource(http, tokens).changes(account("onedrive")) as CloudChanges.Full
        assertEquals(listOf("F", "S"), c.items.map { it.id })
        assertTrue(c.newToken!!.endsWith("token=9"))
        val s = c.items[1]
        assertEquals("sha1:abc", s.hash)
        assertEquals("T", s.audio!!.title)
        assertEquals(3, s.audio!!.trackNo)
    }

    @Test fun expired_delta_token_falls_back_to_a_full_listing() = runBlocking {
        val oldLink = server.url("/api/me/drive/items/ROOT/delta?token=old").toString()
        route { r ->
            if (r.path!!.contains("token=old")) json("""{"error":{"code":"resyncRequired","message":"gone"}}""", 410)
            else json("""{"value":[{"id":"S","name":"a.mp3","file":{},"parentReference":{"id":"ROOT"}}],"@odata.deltaLink":"${server.url("/api/new")}"}""")
        }
        val c = OneDriveMusicSource(http, tokens).changes(account("onedrive", oldLink))
        assertTrue(c is CloudChanges.Full)
        assertEquals(listOf("S"), (c as CloudChanges.Full).items.map { it.id })
    }

    @Test fun incremental_delta_reports_deletions() = runBlocking {
        val link = server.url("/api/me/drive/items/ROOT/delta?token=1").toString()
        route { json("""{"value":[{"id":"D","deleted":{"state":"deleted"}},{"id":"N","name":"n.mp3","file":{},"parentReference":{"id":"ROOT"}}],"@odata.deltaLink":"$link-2"}""") }
        val c = OneDriveMusicSource(http, tokens).changes(account("onedrive", link)) as CloudChanges.Incremental
        assertEquals(listOf("D"), c.removedIds)
        assertEquals(listOf("N"), c.upserts.map { it.id })
    }

    @Test fun onedrive_stream_resolves_the_302_location_without_following_it() = runBlocking {
        route { r ->
            if (r.path!!.endsWith("/content")) MockResponse().setResponseCode(302).setHeader("Location", "https://download.example/abc?tempauth=SECRET")
            else json("{}", 500)
        }
        val t = OneDriveMusicSource(http, tokens).resolveStream(account("onedrive"), "S", false)
        assertEquals("https://download.example/abc?tempauth=SECRET", t.url)
        assertTrue(t.headers.isEmpty()) // the temp URL needs no bearer token
        assertFalse(t.toString().contains("SECRET")) // never printable in logs
        assertEquals(1, server.requestCount) // not followed, no Range sent to Graph
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test fun drive_lists_the_folder_tree_with_paging_and_takes_the_start_token_first() = runBlocking {
        val order = ArrayList<String>()
        route { r ->
            val p = r.requestUrl!!
            order += p.encodedPath
            when {
                p.encodedPath.endsWith("/changes/startPageToken") -> json("""{"startPageToken":"100"}""")
                p.encodedPath.endsWith("/files") && p.queryParameter("q")!!.contains("'ROOT'") && p.queryParameter("pageToken") == null ->
                    json("""{"files":[{"id":"F","name":"A","mimeType":"application/vnd.google-apps.folder","parents":["ROOT"]}],"nextPageToken":"p2"}""")
                p.encodedPath.endsWith("/files") && p.queryParameter("q")!!.contains("'ROOT'") ->
                    json("""{"files":[{"id":"S","name":"s.mp3","mimeType":"audio/mpeg","parents":["ROOT"],"size":"42","md5Checksum":"FF"}]}""")
                p.encodedPath.endsWith("/files") && p.queryParameter("q")!!.contains("'F'") ->
                    json("""{"files":[{"id":"S2","name":"t.flac","mimeType":"audio/flac","parents":["F"],"size":"7"}]}""")
                else -> json("{}", 404)
            }
        }
        val c = GoogleDriveMusicSource(http, tokens).changes(account("gdrive")) as CloudChanges.Full
        assertEquals(setOf("F", "S", "S2"), c.items.map { it.id }.toSet())
        assertEquals("100", c.newToken)
        assertTrue(order.first().endsWith("startPageToken"))
        assertEquals("md5:ff", c.items.first { it.id == "S" }.hash)
        assertEquals(42L, c.items.first { it.id == "S" }.size)
    }

    @Test fun drive_changes_mark_trashed_and_removed_files() = runBlocking {
        route { json("""{"changes":[{"fileId":"A","removed":true},{"fileId":"B","file":{"id":"B","name":"b.mp3","trashed":true,"parents":["ROOT"]}},
            {"fileId":"C","file":{"id":"C","name":"c.mp3","mimeType":"audio/mpeg","parents":["ROOT"]}}],"newStartPageToken":"7"}""") }
        val c = GoogleDriveMusicSource(http, tokens).changes(account("gdrive", "5")) as CloudChanges.Incremental
        assertEquals(listOf("A", "B"), c.removedIds)
        assertEquals(listOf("C"), c.upserts.map { it.id })
        assertEquals("7", c.newToken)
        assertTrue(c.listNewFolders)
    }

    @Test fun drive_stream_uses_a_bearer_header_never_a_url_token() {
        val t = GoogleDriveMusicSource(http, tokens).resolveStream(account("gdrive"), "FILE", false)
        assertTrue(t.url.endsWith("/files/FILE?alt=media"))
        assertFalse(t.url.contains("token", ignoreCase = true))
        assertEquals("Bearer stale", t.headers["Authorization"])
    }

    @Test fun retry_after_http_date_is_understood() {
        val now = 1_700_000_000_000L
        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }.format(java.util.Date(now + 30_000))
        assertEquals(30L, CloudHttp.retryAfterSeconds(date, now))
        assertEquals(5L, CloudHttp.retryAfterSeconds("5"))
        assertEquals(null, CloudHttp.retryAfterSeconds("soon"))
    }
}
