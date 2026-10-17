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
 * SECURITY NOTE: the password is stored in app-private storage (not world-readable) but is
 * NOT yet encrypted at rest. Hardening with androidx.security EncryptedSharedPreferences /
 * Keystore is a documented follow-up (see docs). Tokens are never logged.
 */
private val Context.serverStore by preferencesDataStore(name = "music_server")

class ServerCredentialsRepository(private val context: Context) {

    private object Keys {
        val URL = stringPreferencesKey("base_url")
        val USER = stringPreferencesKey("username")
        val PASS = stringPreferencesKey("password")
        val CONNECTED = booleanPreferencesKey("connected")
        val STAY = booleanPreferencesKey("stay_signed_in")
    }

    val credentials: Flow<ServerCredentials?> = context.serverStore.data.map { p ->
        val url = p[Keys.URL]
        val user = p[Keys.USER]
        val pass = p[Keys.PASS]
        if (url.isNullOrBlank() || user.isNullOrBlank() || pass == null) null
        else ServerCredentials(url, user, pass)
    }

    val connected: Flow<Boolean> = context.serverStore.data.map { it[Keys.CONNECTED] ?: false }

    /** When true (default), saved credentials auto-reconnect on app launch. */
    val staySignedIn: Flow<Boolean> = context.serverStore.data.map { it[Keys.STAY] ?: true }

    /** Last server address, kept across sign-out so "Switch account" can prefill it. */
    val lastServerUrl: Flow<String?> = context.serverStore.data.map { it[Keys.URL] }

    suspend fun save(creds: ServerCredentials, staySignedIn: Boolean = true) {
        context.serverStore.edit {
            it[Keys.URL] = creds.baseUrl
            it[Keys.USER] = creds.username
            it[Keys.PASS] = creds.password
            it[Keys.CONNECTED] = true
            it[Keys.STAY] = staySignedIn
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
            it[Keys.CONNECTED] = false
        }
    }
}
