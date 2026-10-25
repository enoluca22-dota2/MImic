package com.example.mimic.data.account

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.mimic.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * The optional MImic account (4.4): "Continue with Google" through Android Credential Manager,
 * then Firebase Authentication. It identifies the user for sync between their phones and is
 * completely separate from the YouTube Music connection (Settings → Accounts shows both).
 *
 * Firebase is initialised from build-time values in a gitignored firebase.properties (no
 * google-services plugin, nothing secret in the repository). Without them the account shows
 * [State.NotConfigured] and MImic works exactly as before — guest mode is the default.
 * MImic never sees a Google password: Credential Manager returns a Google ID token, which is
 * exchanged for a Firebase session; Firebase keeps that session in its own private storage.
 */
class MimicAccount(private val context: Context) {

    sealed interface State {
        data object NotConfigured : State
        data object SignedOut : State
        data object Working : State
        data class SignedIn(val uid: String, val name: String?, val email: String?, val photoUrl: String?) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.SignedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    val configured: Boolean = listOf(
        BuildConfig.FIREBASE_API_KEY, BuildConfig.FIREBASE_APP_ID, BuildConfig.FIREBASE_PROJECT_ID, BuildConfig.GOOGLE_WEB_CLIENT_ID,
    ).all { it.isNotBlank() }

    private val auth: FirebaseAuth? by lazy {
        if (!configured) return@lazy null
        runCatching {
            val app = FirebaseApp.getApps(context).firstOrNull { it.name == APP_NAME } ?: FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .build(),
                APP_NAME,
            )
            FirebaseAuth.getInstance(app)
        }.getOrNull()
    }

    /** The Firebase app (for Firestore), once configured. */
    internal fun firebaseApp(): FirebaseApp? = auth?.app

    init {
        refresh()
    }

    /** Restores an existing session (Firebase persists it across restarts). */
    fun refresh() {
        val a = auth
        _state.value = when {
            !configured -> State.NotConfigured
            a == null -> State.Error("Firebase could not start. Check firebase.properties.")
            else -> a.currentUser?.let { State.SignedIn(it.uid, it.displayName, it.email, it.photoUrl?.toString()) } ?: State.SignedOut
        }
    }

    val uid: String? get() = (state.value as? State.SignedIn)?.uid

    /** Shows Google's account chooser (an Activity is required for the system sheet). */
    suspend fun signIn(activity: Activity) {
        val a = auth ?: run { refresh(); return }
        _state.value = State.Working
        try {
            val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
            val result = CredentialManager.create(activity)
                .getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
            val cred = result.credential
            if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                _state.value = State.Error("Google returned an unexpected credential.")
                return
            }
            val idToken = GoogleIdTokenCredential.createFrom(cred.data).idToken
            a.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
            refresh()
        } catch (e: GetCredentialCancellationException) {
            refresh()
        } catch (e: NoCredentialException) {
            _state.value = State.Error("No Google account is available on this phone. Add one in Android Settings → Accounts.")
        } catch (e: Exception) {
            _state.value = State.Error(friendly(e))
        }
    }

    /** Signs out of MImic only. Local playlists, favorites and downloads are kept. */
    suspend fun signOut() {
        runCatching { auth?.signOut() }
        runCatching { CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest()) }
        refresh()
    }

    private fun friendly(e: Exception): String {
        val m = e.message.orEmpty()
        return when {
            "10:" in m || "DEVELOPER_ERROR" in m || "28444" in m ->
                "Google rejected this app's sign-in setup (OAuth client / SHA-1 not registered). See docs/ACCOUNT_SETUP.md."
            "network" in m.lowercase() -> "No connection. Try again when you're online."
            else -> "Sign-in failed: ${m.take(160)}"
        }
    }

    companion object {
        const val APP_NAME = "mimic-account"
    }
}
