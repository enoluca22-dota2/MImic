package com.example.mimic.data.cloud.google

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.mimic.data.cloud.CloudException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Google Drive authorization with Google Identity Services' AuthorizationClient (the current,
 * supported Android API). Consent happens in Google's own account UI; MImic never sees the
 * password. Google Play services caches and renews the access token, so MImic stores none.
 *
 * Scope: `drive.readonly`. MImic must discover songs the user uploads later through the Drive
 * website, and on Android `drive.file` only covers files the app itself created or that were
 * opened through the (web-only) Google Picker — so `drive.file` cannot see a web-uploaded music
 * folder. `drive.readonly` is a RESTRICTED scope: fine for the developer's own account and test
 * users of an app in "Testing" mode, but a public release needs Google's restricted-scope
 * verification (security assessment). See docs/CLOUD_SETUP.md.
 */
class GoogleDriveAuth(private val context: Context) {

    private val client get() = Identity.getAuthorizationClient(context)

    private fun request(email: String?): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .apply { if (email != null) setAccount(Account(email, "com.google")) }
        .build()

    /** Start authorization; either a token right away or a consent UI to launch. */
    suspend fun authorize(email: String? = null): Step {
        val r = try {
            client.authorize(request(email)).await()
        } catch (e: ApiException) {
            throw map(e)
        }
        return if (r.hasResolution()) Step.NeedsConsent(r.pendingIntent!!) else Step.Authorized(r.accessToken ?: throw CloudException.AuthRequired())
    }

    /** Finish after the consent UI returned. */
    fun resultFromIntent(data: Intent?): String {
        val r: AuthorizationResult = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            throw map(e)
        }
        return r.accessToken ?: throw CloudException.AuthRequired("Google didn't grant Drive access")
    }

    /** Blocking token for [email] (Play services renews it silently); never on the main thread. */
    fun token(email: String, forceRefresh: Boolean, stale: String? = null): String {
        if (forceRefresh && stale != null) runCatching { Tasks.await(client.clearToken(ClearTokenRequest.builder().setToken(stale).build()), 15, TimeUnit.SECONDS) }
        val r = try {
            Tasks.await(client.authorize(request(email)), 30, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw (e.cause as? ApiException)?.let(::map) ?: CloudException.Offline()
        } catch (e: java.util.concurrent.TimeoutException) {
            throw CloudException.Offline("Google sign-in timed out")
        }
        if (r.hasResolution()) throw CloudException.AuthRequired("Google Drive access expired — reconnect the account")
        return r.accessToken ?: throw CloudException.AuthRequired()
    }

    /** Disconnect: revoke MImic's Drive grant for this account (Google-side) where supported. */
    suspend fun revoke(email: String) {
        runCatching {
            client.revokeAccess(RevokeAccessRequest.builder().setScopes(listOf(Scope(SCOPE))).setAccount(Account(email, "com.google")).build()).await()
        }
    }

    private fun map(e: ApiException): CloudException = when (e.statusCode) {
        CommonStatusCodes.NETWORK_ERROR -> CloudException.Offline()
        CommonStatusCodes.DEVELOPER_ERROR -> CloudException.NotConfigured(
            "Google rejected this app's configuration (DEVELOPER_ERROR): register an Android OAuth client for " +
                "com.example.mimic with this APK's SHA-1 (see docs/CLOUD_SETUP.md).",
        )
        CommonStatusCodes.CANCELED -> CloudException.AuthRequired("Google authorization was cancelled")
        else -> CloudException.AuthRequired("Google authorization failed (${CommonStatusCodes.getStatusCodeString(e.statusCode)})")
    }

    sealed interface Step {
        data class Authorized(val token: String) : Step
        class NeedsConsent(val intent: PendingIntent) : Step
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        val e = t.exception
        if (e != null) cont.resumeWithException(e) else if (t.isCanceled) cont.cancel() else cont.resume(t.result)
    }
}
