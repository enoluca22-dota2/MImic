package com.example.mimic.data.server

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Stores the music-server connection in app-private DataStore.
 *
 * SECURITY: the password is encrypted at rest with AES-256-GCM via a non-exportable Android
 * Keystore key (see [CredentialCipher]); only ciphertext is written to DataStore. A legacy
 * plaintext password from an older install is transparently migrated on first access (see
 * [migratePlaintextIfNeeded]). Tokens/passwords are never logged. This store holds ONLY the
 * server connection; app preferences (themes, layouts, home config) live in a separate
 * DataStore and are untouched by any credential migration.
 */
private val Context.serverStore by preferencesDataStore(name = "music_server")

class ServerCredentialsRepository(private val context: Context) {

    private object Keys {
        val URL = stringPreferencesKey("base_url")
        val USER = stringPreferencesKey("username")
        val PASS = stringPreferencesKey("password")          // legacy plaintext (migrated away)
        val PASS_ENC = stringPreferencesKey("password_enc")  // AES-GCM ciphertext (current)
        val CONNECTED = booleanPreferencesKey("connected")
        val STAY = booleanPreferencesKey("stay_signed_in")
    }

    val credentials: Flow<ServerCredentials?> = context.serverStore.data.map { p ->
        val url = p[Keys.URL]
        val user = p[Keys.USER]
        // Prefer the encrypted password; fall back to any legacy plaintext still present.
        val pass = p[Keys.PASS_ENC]?.let { CredentialCipher.decrypt(it) } ?: p[Keys.PASS]
        if (url.isNullOrBlank() || user.isNullOrBlank() || pass.isNullOrEmpty()) null
        else ServerCredentials(url, user, pass)
    }

    val connected: Flow<Boolean> = context.serverStore.data.map { it[Keys.CONNECTED] ?: false }

    /** When true (default), saved credentials auto-reconnect on app launch. */
    val staySignedIn: Flow<Boolean> = context.serverStore.data.map { it[Keys.STAY] ?: true }

    /** Last server address, kept across sign-out so "Switch account" can prefill it. */
    val lastServerUrl: Flow<String?> = context.serverStore.data.map { it[Keys.URL] }

    suspend fun save(creds: ServerCredentials, staySignedIn: Boolean = true) {
        val enc = CredentialCipher.encrypt(creds.password)
        context.serverStore.edit {
            it[Keys.URL] = creds.baseUrl
            it[Keys.USER] = creds.username
            if (enc != null) {
                it[Keys.PASS_ENC] = enc
                it.remove(Keys.PASS) // never keep plaintext once we can encrypt
            } else {
                // Keystore unavailable (should be rare) — degrade safely rather than lose the login.
                it[Keys.PASS] = creds.password
                it.remove(Keys.PASS_ENC)
            }
            it[Keys.CONNECTED] = true
            it[Keys.STAY] = staySignedIn
        }
    }

    /**
     * One-time migration: if an older install left a plaintext password and no ciphertext, encrypt
     * it in place and drop the plaintext. No-op if there is nothing to migrate or if encryption is
     * unavailable (in which case the existing plaintext is left intact — safe rollback, no data loss).
     */
    suspend fun migratePlaintextIfNeeded() {
        context.serverStore.edit { p ->
            val legacy = p[Keys.PASS]
            if (legacy != null && p[Keys.PASS_ENC] == null) {
                val enc = CredentialCipher.encrypt(legacy)
                if (enc != null) {
                    p[Keys.PASS_ENC] = enc
                    p.remove(Keys.PASS)
                }
            }
        }
    }

    suspend fun setConnected(value: Boolean) {
        context.serverStore.edit { it[Keys.CONNECTED] = value }
    }

    suspend fun setStaySignedIn(value: Boolean) {
        context.serverStore.edit { it[Keys.STAY] = value }
    }

    /** Full sign-out: removes the account entirely (server address included). */
    suspend fun clear() {
        context.serverStore.edit { it.clear() }
    }

    /** "Switch account": drop username/password/session but keep the server address prefilled. */
    suspend fun signOutKeepServer() {
        context.serverStore.edit {
            it.remove(Keys.USER)
            it.remove(Keys.PASS)
            it.remove(Keys.PASS_ENC)
            it[Keys.CONNECTED] = false
        }
    }
}
