package com.example.mimic.data.cloud.google

import android.accounts.Account
import android.accounts.AccountManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.mimic.data.cloud.AppSignature
import com.example.mimic.data.cloud.CloudException
import com.example.mimic.data.cloud.GoogleAuthErrors
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.GooglePlayServicesAvailabilityException
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
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
 *
 * 4.0: a real phone then reported INTERNAL_ERROR (8) from AuthorizationClient — a status that
 * carries no reason. MImic therefore has a second, older-but-supported route through the same
 * OAuth client: Android's own account chooser + GoogleAuthUtil ([alternateToken]). It is tried
 * automatically after an internal error and reports the concrete reason Google gives
 * (UNREGISTERED_ON_API_CONSOLE, ServiceDisabled, …). Silent background tokens also use
 * GoogleAuthUtil ([token]), so a connected account keeps working whichever route connected it —
 * both are grants to the same package + signing certificate.
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

    /**
     * Blocking token for [email]; Play services caches and renews it. [stale] is cleared first when
     * an API call was rejected with 401. Never on the main thread.
     */
    fun token(email: String, forceRefresh: Boolean, stale: String? = null): String {
        if (forceRefresh && stale != null) runCatching { GoogleAuthUtil.clearToken(context, stale) }
        return when (val step = alternateToken(email)) {
            is AltStep.Token -> step.token
            is AltStep.NeedsConsent -> throw CloudException.AuthRequired("Google Drive access expired — reconnect the account")
        }
    }

    // ---- Alternate route: Android account chooser + GoogleAuthUtil ----

    /** Android's system account picker limited to Google accounts (needs no permission). */
    fun chooseAccountIntent(): Intent =
        AccountManager.newChooseAccountIntent(null, null, arrayOf(ACCOUNT_TYPE), null, null, null, null)

    /** Blocking: a Drive token for [email], or the consent screen Google wants shown first. */
    fun alternateToken(email: String): AltStep = try {
        AltStep.Token(GoogleAuthUtil.getToken(context, Account(email, ACCOUNT_TYPE), "oauth2:$SCOPE"))
    } catch (e: GooglePlayServicesAvailabilityException) {
        Log.w(TAG, "alternate route: Play services unavailable (${e.connectionStatusCode})")
        throw CloudException.NotConfigured("Google Play services needs an update or isn't available (status ${e.connectionStatusCode}). Update it from the Play Store and try again.")
    } catch (e: UserRecoverableAuthException) {
        val intent = e.intent ?: throw CloudException.AuthRequired("Google needs you to approve access, but gave no screen to show")
        AltStep.NeedsConsent(intent)
    } catch (e: GoogleAuthException) {
        // Google's reason string, e.g. UNREGISTERED_ON_API_CONSOLE — not a credential.
        Log.w(TAG, "alternate route failed: ${e.message}")
        throw GoogleAuthErrors.fromAuthUtil(e.message, context.packageName, sha1())
    } catch (e: IOException) {
        throw CloudException.Offline("Google couldn't be reached — check your connection and try again")
    }

    sealed interface AltStep {
        data class Token(val token: String) : AltStep
        class NeedsConsent(val intent: Intent) : AltStep
    }

    /** Disconnect: revoke MImic's Drive grant for this account (Google-side) where supported. */
    suspend fun revoke(email: String) {
        runCatching {
            client.revokeAccess(RevokeAccessRequest.builder().setScopes(listOf(Scope(SCOPE))).setAccount(Account(email, "com.google")).build()).await()
        }
    }

    private fun map(e: ApiException): CloudException {
        // Status code + Google's status text only — never tokens or account data.
        val detail = e.status.statusMessage?.takeIf { it.isNotBlank() }
        Log.w(TAG, "authorization failed: status ${e.statusCode} (${CommonStatusCodes.getStatusCodeString(e.statusCode)}) ${detail.orEmpty()}")
        return GoogleAuthErrors.toException(e.statusCode, context.packageName, sha1(), detail)
    }

    sealed interface Step {
        data class Authorized(val token: String) : Step
        class NeedsConsent(val intent: PendingIntent) : Step
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        private const val TAG = "MImicGoogleAuth"
        private const val ACCOUNT_TYPE = "com.google"
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { t ->
        val e = t.exception
        if (e != null) cont.resumeWithException(e) else if (t.isCanceled) cont.cancel() else cont.resume(t.result)
    }
}
