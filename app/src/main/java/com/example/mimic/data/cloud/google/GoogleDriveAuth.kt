package com.example.mimic.data.cloud.google

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.mimic.data.cloud.AppSignature
import com.example.mimic.data.cloud.CloudException
import com.example.mimic.data.cloud.GoogleAuthErrors
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
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
 *
 * Authentication vs authorization: MImic needs no Google *sign-in* of its own — the account picker
 * inside the authorization flow chooses the account, and the Drive grant is the only thing kept.
 * The account's e-mail is learned afterwards from Drive's about.get and stored with the account,
 * so later silent token requests name the same account.
 *
 * 3.3 fix: the consent result used to be dropped whenever the activity returned anything other
 * than RESULT_OK — exactly what happens after the account chooser when Google rejects the OAuth
 * client — so "pick account → nothing happens". The result is now always parsed and every
 * outcome surfaces as a message (see [resultFromIntent], [GoogleAuthErrors]).
 */
class GoogleDriveAuth(private val context: Context) {

    private val client get() = Identity.getAuthorizationClient(context)

    private fun request(email: String?): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(SCOPE)))
        .apply { if (email != null) setAccount(Account(email, "com.google")) }
        .build()

    /**
     * Start authorization for [email] (null = let the user pick an account); either a token right
     * away or Google's account chooser / consent UI to launch.
     */
    suspend fun authorize(email: String? = null): Step {
        val r = try {
            withTimeout(30_000) { client.authorize(request(email)).await() }
        } catch (e: ApiException) {
            throw map(e)
        } catch (e: TimeoutCancellationException) {
            throw CloudException.Offline("Google Play services didn't answer — check your connection and try again")
        }
        val pending = r.pendingIntent
        return when {
            r.hasResolution() && pending != null -> Step.NeedsConsent(pending)
            !r.accessToken.isNullOrBlank() && grants(r) -> Step.Authorized(r.accessToken!!)
            else -> throw CloudException.AuthRequired("Google didn't grant read access to Drive")
        }
    }

    /**
     * Finish after the chooser/consent UI returned, whatever [resultCode] it returned with: Play
     * services puts the failure status into [data] even for RESULT_CANCELED.
     */
    fun resultFromIntent(resultCode: Int, data: Intent?): String {
        val r: AuthorizationResult = try {
            if (data == null) throw GoogleAuthErrors.noResult(context.packageName, sha1())
            client.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            throw map(e)
        }
        val token = r.accessToken
        if (token.isNullOrBlank()) {
            Log.w(TAG, "consent returned resultCode=$resultCode without a token")
            throw if (resultCode == android.app.Activity.RESULT_OK) CloudException.AuthRequired("Google didn't grant Drive access")
            else GoogleAuthErrors.noResult(context.packageName, sha1())
        }
        if (!grants(r)) throw CloudException.AuthRequired("Drive access wasn't granted — tick the Drive permission on Google's screen")
        return token
    }

    /** Some Play services versions leave grantedScopes empty; only an explicit list without Drive is a refusal. */
    private fun grants(r: AuthorizationResult) = r.grantedScopes.isEmpty() || SCOPE in r.grantedScopes

    private fun sha1(): String? = AppSignature.of(context)?.sha1

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

    private fun map(e: ApiException): CloudException {
        // Status code only — never tokens or account data.
        Log.w(TAG, "authorization failed: status ${e.statusCode} (${CommonStatusCodes.getStatusCodeString(e.statusCode)})")
        return GoogleAuthErrors.toException(e.statusCode, context.packageName, sha1())
    }

    sealed interface Step {
        data class Authorized(val token: String) : Step
        class NeedsConsent(val intent: PendingIntent) : Step
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private const val TAG = "MImicGoogleAuth"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        val e = t.exception
        if (e != null) cont.resumeWithException(e) else if (t.isCanceled) cont.cancel() else cont.resume(t.result)
    }
}
