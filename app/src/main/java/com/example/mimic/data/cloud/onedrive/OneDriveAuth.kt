package com.example.mimic.data.cloud.onedrive

import android.app.Activity
import android.content.Context
import com.example.mimic.BuildConfig
import com.example.mimic.data.cloud.AppSignature
import com.example.mimic.data.cloud.CloudException
import com.example.mimic.data.cloud.CloudIdentity
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalClientException
import com.microsoft.identity.client.exception.MsalException
import com.microsoft.identity.client.exception.MsalUiRequiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Microsoft sign-in via MSAL for Android (public client, no secret). Sign-in happens in the
 * system browser / Custom Tab — MImic never sees the password. MSAL keeps the tokens in its own
 * encrypted cache and refreshes them silently ([token]); MImic stores none.
 *
 * Permission: delegated `Files.Read` (read the user's own files) — MSAL adds `offline_access`,
 * `openid` and `profile` itself so the session survives restarts.
 */
class OneDriveAuth(private val context: Context) {

    val clientId: String = BuildConfig.MSAL_CLIENT_ID
    val isConfigured: Boolean get() = clientId.isNotBlank()

    @Volatile private var app: ISingleAccountPublicClientApplication? = null

    /** Blocking; never call on the main thread. */
    private fun app(): ISingleAccountPublicClientApplication {
        app?.let { return it }
        if (!isConfigured) throw CloudException.NotConfigured(NOT_CONFIGURED)
        signatureProblem()?.let { throw CloudException.NotConfigured(it) }
        synchronized(this) {
            app?.let { return it }
            val config = File(context.noBackupFilesDir, "msal_config.json")
            config.writeText(configJson(clientId, BuildConfig.MSAL_SIGNATURE_HASH, context.packageName, BuildConfig.MSAL_AUDIENCE))
            return PublicClientApplication.createSingleAccountPublicClientApplication(context, config).also { app = it }
        }
    }

    /**
     * The browser redirect only comes back to an APK whose signing certificate matches the hash
     * baked into the redirect URI (and registered in Entra). A mismatch otherwise fails late and
     * obscurely inside MSAL, so it is checked up front with the real hash in the message.
     */
    fun signatureProblem(): String? {
        val actual = AppSignature.of(context)?.base64Sha1 ?: return null
        return if (actual == BuildConfig.MSAL_SIGNATURE_HASH) null else
            "This APK is signed with signature hash $actual, but the build expects ${BuildConfig.MSAL_SIGNATURE_HASH}. " +
                "Set msal.signatureHash=$actual in cloud.properties and add it as an Android redirect in the Entra app registration."
    }

    /** Interactive sign-in in the browser. Replaces any previous OneDrive session (single-account). */
    suspend fun signIn(activity: Activity): CloudIdentity {
        val app = withContext(Dispatchers.IO) {
            app().also { a -> if (a.currentAccount?.currentAccount != null) a.signOut() }
        }
        val result = suspendCancellableCoroutine<IAuthenticationResult> { cont ->
            app.signIn(
                SignInParameters.builder()
                    .withActivity(activity)
                    .withScopes(SCOPES)
                    .withCallback(object : AuthenticationCallback {
                        override fun onSuccess(r: IAuthenticationResult) = cont.resume(r)
                        override fun onError(e: MsalException) = cont.resumeWithException(map(e))
                        override fun onCancel() = cont.resumeWithException(CloudException.AuthRequired("Sign-in was cancelled"))
                    })
                    .build(),
            )
        }
        val acct = result.account
        val name = (acct.claims?.get("name") as? String)?.takeIf { it.isNotBlank() }
        return CloudIdentity(accountId = acct.id, displayName = name ?: acct.username, email = acct.username)
    }

    /** Blocking access token from MSAL's cache (refreshed silently when needed). */
    fun token(forceRefresh: Boolean): String {
        val a = app()
        val account = a.currentAccount?.currentAccount ?: throw CloudException.AuthRequired("Sign in to OneDrive again")
        return try {
            a.acquireTokenSilent(
                AcquireTokenSilentParameters.Builder()
                    .forAccount(account)
                    .fromAuthority(account.authority)
                    .withScopes(SCOPES)
                    .forceRefresh(forceRefresh)
                    .build(),
            ).accessToken
        } catch (e: MsalException) {
            throw map(e)
        }
    }

    /** The signed-in account's id, or null (used to detect a session lost after reinstall). */
    fun currentAccountId(): String? = runCatching { app().currentAccount?.currentAccount?.id }.getOrNull()

    /** Removes MSAL's cached tokens and account from this device. */
    suspend fun signOut() = withContext(Dispatchers.IO) {
        if (isConfigured) runCatching { app().signOut() }
        Unit
    }

    private fun map(e: MsalException): CloudException = when {
        e is MsalUiRequiredException -> CloudException.AuthRequired("Sign in to OneDrive again")
        e is MsalClientException && e.errorCode == MsalClientException.DEVICE_NETWORK_NOT_AVAILABLE -> CloudException.Offline()
        e.errorCode == "io_error" || e.errorCode == "device_network_not_available" -> CloudException.Offline()
        else -> CloudException.AuthRequired("Microsoft sign-in failed: ${e.errorCode}")
    }

    companion object {
        val SCOPES = listOf("Files.Read")
        const val NOT_CONFIGURED =
            "OneDrive sign-in isn't set up in this build yet: it needs a Microsoft app registration (see docs/CLOUD_SETUP.md)."

        /**
         * MSAL configuration: browser sign-in, no broker, no secret. [audience] comes from
         * cloud.properties `msal.audience` and must match the registration's "supported account
         * types": consumers (personal accounts, default), common (personal + work/school) or
         * organizations (work/school only).
         */
        fun configJson(clientId: String, signatureHash: String, packageName: String, audience: String = "consumers"): String = JSONObject()
            .put("client_id", clientId)
            .put("authorization_user_agent", "DEFAULT")
            .put("redirect_uri", "msauth://$packageName/" + URLEncoder.encode(signatureHash, "UTF-8"))
            .put("account_mode", "SINGLE")
            .put("broker_redirect_uri_registered", false)
            .put(
                "authorities",
                JSONArray().put(
                    JSONObject().put("type", "AAD").put("default", true).put("audience", audienceJson(audience)),
                ),
            )
            .toString()

        fun audienceJson(audience: String): JSONObject = when (audience.trim().lowercase()) {
            "common" -> JSONObject().put("type", "AzureADandPersonalMicrosoftAccount").put("tenant_id", "common")
            "organizations" -> JSONObject().put("type", "AzureADMultipleOrgs").put("tenant_id", "organizations")
            else -> JSONObject().put("type", "PersonalMicrosoftAccount").put("tenant_id", "consumers")
        }
    }
}
