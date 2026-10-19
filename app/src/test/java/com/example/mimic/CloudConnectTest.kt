package com.example.mimic

import com.example.mimic.data.cloud.AppSignature
import com.example.mimic.data.cloud.CloudConnectState
import com.example.mimic.data.cloud.CloudException
import com.example.mimic.data.cloud.CloudHttp
import com.example.mimic.data.cloud.GoogleAuthErrors
import com.example.mimic.data.cloud.onedrive.OneDriveAuth
import com.example.mimic.data.db.CloudAccountEntity
import com.example.mimic.data.cloud.CloudSyncState
import com.example.mimic.ui.sources.SourceStatus
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MImic 3.3 sign-in plumbing that can be checked without a real provider: how Google status codes
 * and cancellations become user-visible states, what the Music Sources card shows, signing
 * fingerprints, MSAL audiences, and that bearer tokens never follow a redirect to another host.
 * These are mocks — they do NOT prove a real Google/Microsoft sign-in works.
 */
class CloudConnectTest {

    private val pkg = "com.example.mimic"
    private val sha1 = "78:0E:4B:F7:03:93:19:BB:F0:99:D5:F7:A2:3B:C6:9C:00:5A:5F:F9"

    @Test fun developer_error_names_package_and_actual_sha1() {
        val e = GoogleAuthErrors.toException(GoogleAuthErrors.DEVELOPER_ERROR, pkg, sha1)
        assertTrue(e is CloudException.NotConfigured)
        assertTrue(e.message!!.contains(pkg))
        assertTrue(e.message!!.contains(sha1))
        assertEquals(CloudConnectState.Kind.NOT_CONFIGURED, CloudConnectState.failed(e).kind)
    }

    @Test fun cancellation_is_not_shown_as_an_error() {
        for (code in listOf(GoogleAuthErrors.CANCELED, GoogleAuthErrors.SIGN_IN_CANCELLED)) {
            val f = CloudConnectState.failed(GoogleAuthErrors.toException(code, pkg, sha1))
            assertEquals(CloudConnectState.Kind.CANCELLED, f.kind)
        }
        // RESULT_CANCELED without a status: cancelled, but the message still points at the setup.
        val none = GoogleAuthErrors.noResult(pkg, sha1)
        assertTrue(none is CloudException.Cancelled)
        assertTrue(none.message!!.contains(sha1))
    }

    @Test fun network_and_other_failures_map_to_offline_and_auth() {
        assertEquals(CloudConnectState.Kind.OFFLINE, CloudConnectState.failed(GoogleAuthErrors.toException(GoogleAuthErrors.NETWORK_ERROR, pkg, null)).kind)
        assertEquals(CloudConnectState.Kind.OFFLINE, CloudConnectState.failed(GoogleAuthErrors.toException(GoogleAuthErrors.TIMEOUT, pkg, null)).kind)
        assertEquals(CloudConnectState.Kind.NOT_CONFIGURED, CloudConnectState.failed(GoogleAuthErrors.toException(GoogleAuthErrors.SIGN_IN_FAILED, pkg, null)).kind)
        assertEquals(CloudConnectState.Kind.AUTH, CloudConnectState.failed(GoogleAuthErrors.toException(999, pkg, null)).kind)
        assertEquals(CloudConnectState.Kind.AUTH, CloudConnectState.failed(IllegalStateException()).kind)
    }

    @Test fun internal_error_8_is_retried_through_the_alternate_route() {
        val e8 = GoogleAuthErrors.toException(GoogleAuthErrors.INTERNAL_ERROR, pkg, sha1, "Unknown")
        assertTrue(e8 is CloudException.ProviderError)
        assertTrue(GoogleAuthErrors.retryWithAlternate(e8))
        assertTrue(e8.message!!.contains("code 8"))
        // Actionable statuses are shown, not retried blindly.
        assertTrue(!GoogleAuthErrors.retryWithAlternate(GoogleAuthErrors.toException(GoogleAuthErrors.DEVELOPER_ERROR, pkg, sha1)))
        assertTrue(!GoogleAuthErrors.retryWithAlternate(GoogleAuthErrors.toException(GoogleAuthErrors.CANCELED, pkg, sha1)))
    }

    @Test fun google_auth_util_reasons_become_concrete_fixes() {
        val unreg = GoogleAuthErrors.fromAuthUtil("UNREGISTERED_ON_API_CONSOLE", pkg, sha1)
        assertTrue(unreg is CloudException.NotConfigured)
        assertTrue(unreg.message!!.contains(sha1) && unreg.message!!.contains(pkg))
        assertTrue(GoogleAuthErrors.fromAuthUtil("ServiceDisabled", pkg, sha1) is CloudException.NotConfigured)
        assertTrue(GoogleAuthErrors.fromAuthUtil("BadAuthentication", pkg, sha1) is CloudException.AuthRequired)
        assertTrue(GoogleAuthErrors.fromAuthUtil("NetworkError", pkg, sha1) is CloudException.Offline)
        assertTrue(GoogleAuthErrors.fromAuthUtil("access_denied", pkg, sha1).message!!.contains("test user"))
        val unknown = GoogleAuthErrors.fromAuthUtil("SomethingNew", pkg, sha1)
        assertTrue(unknown is CloudException.ProviderError && unknown.message!!.contains("SomethingNew"))
        // An unknown reason from the alternate route must not loop back into the alternate route forever:
        // the VM passes allowAlternate=false there, but the classification itself stays retry-eligible.
        assertTrue(GoogleAuthErrors.retryWithAlternate(unknown))
    }

    @Test fun onedrive_client_id_must_look_like_an_entra_application_id() {
        assertTrue(OneDriveAuth.isValidClientId("0f4c2a5e-1b2c-4d3e-8f9a-0b1c2d3e4f5a"))
        assertTrue(OneDriveAuth.isValidClientId(" 0F4C2A5E-1B2C-4D3E-8F9A-0B1C2D3E4F5A "))
        assertTrue(!OneDriveAuth.isValidClientId("my-app"))
        assertTrue(!OneDriveAuth.isValidClientId("eA5L9wOTGbvwmdX3ojvGnABaX/k="))
    }

    private fun acct(folder: String? = "F", lastError: String? = null) =
        CloudAccountEntity("gdrive:1", "gdrive", "1", "Me", "me@x", folder, folder?.let { "Music" }, null, 0, lastError, 0)

    @Test fun card_never_claims_connected_before_auth_and_folder() {
        val connecting = CloudConnectState.Connecting("…")
        val failed = CloudConnectState.Failed("no", CloudConnectState.Kind.AUTH)
        val cancelled = CloudConnectState.Failed("no", CloudConnectState.Kind.CANCELLED)
        assertEquals(SourceStatus.SETUP_REQUIRED, SourceStatus.of(false, null, null, null))
        assertEquals(SourceStatus.DISCONNECTED, SourceStatus.of(true, null, null, null))
        assertEquals(SourceStatus.DISCONNECTED, SourceStatus.of(true, null, cancelled, null))
        assertEquals(SourceStatus.CONNECTING, SourceStatus.of(true, null, connecting, null))
        assertEquals(SourceStatus.AUTH_REQUIRED, SourceStatus.of(true, null, failed, null))
        assertEquals(SourceStatus.NEEDS_FOLDER, SourceStatus.of(true, acct(folder = null), null, null))
        assertEquals(SourceStatus.EXPIRED, SourceStatus.of(true, acct(), null, CloudSyncState.Failed("expired", needsSignIn = true)))
        assertEquals(SourceStatus.ERROR, SourceStatus.of(true, acct(), null, CloudSyncState.Failed("boom", needsSignIn = false)))
        assertEquals(SourceStatus.ERROR, SourceStatus.of(true, acct(lastError = "old failure"), null, null))
        assertEquals(SourceStatus.CONNECTED, SourceStatus.of(true, acct(), null, null))
    }

    @Test fun fingerprints_match_known_digests() {
        // SHA-1("abc") / SHA-256("abc") test vectors.
        val f = AppSignature.fingerprints("abc".toByteArray())
        assertEquals("A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D", f.sha1)
        assertTrue(f.sha256.startsWith("BA:78:16:BF:8F:01:CF:EA"))
        assertEquals("qZk+NkcGgWq6PiVxeFDCbJzQ2J0=", f.base64Sha1)
        // The debug keystore's SHA-1 and the Microsoft signature hash are the same digest.
        val debug = sha1.split(":").map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("eA5L9wOTGbvwmdX3ojvGnABaX/k=", okio.ByteString.of(*debug).base64())
    }

    @Test fun msal_audience_follows_the_registration() {
        fun aud(a: String) = JSONObject(OneDriveAuth.configJson("cid", "h/k=", pkg, a))
            .getJSONArray("authorities").getJSONObject(0).getJSONObject("audience")
        assertEquals("PersonalMicrosoftAccount", aud("consumers").getString("type"))
        assertEquals("AzureADandPersonalMicrosoftAccount", aud("common").getString("type"))
        assertEquals("AzureADMultipleOrgs", aud("organizations").getString("type"))
        assertEquals("consumers", aud("anything-else").getString("tenant_id"))
        val cfg = JSONObject(OneDriveAuth.configJson("cid", "h/k=", pkg))
        assertEquals("msauth://$pkg/h%2Fk%3D", cfg.getString("redirect_uri"))
        assertEquals(false, cfg.has("client_secret"))
    }

    @Test fun bearer_token_is_dropped_when_a_redirect_leaves_the_host() = runBlocking {
        val cdn = MockWebServer().apply { start() }
        val api = MockWebServer().apply { start() }
        try {
            cdn.enqueue(MockResponse().setBody("audio"))
            // Same machine, different origin (other port + 127.0.0.1 vs localhost).
            val elsewhere = cdn.url("/file").newBuilder().host("127.0.0.1").build()
            api.enqueue(MockResponse().setResponseCode(302).setHeader("Location", elsewhere.toString()))
            val body = CloudHttp().get(api.url("/content").newBuilder().host("localhost").build().toString(), { "secret-token" }).use { it.body!!.string() }
            assertEquals("audio", body)
            assertEquals("Bearer secret-token", api.takeRequest().getHeader("Authorization"))
            assertNull(cdn.takeRequest().getHeader("Authorization"))
        } finally {
            cdn.shutdown(); api.shutdown()
        }
    }
}
