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
    }

    val credentials: Flow<ServerCredentials?> = context.serverStore.data.map { p ->
        val url = p[Keys.URL]
        val user = p[Keys.USER]
        val pass = p[Keys.PASS]
        if (url.isNullOrBlank() || user.isNullOrBlank() || pass == null) null
        else ServerCredentials(url, user, pass)
    }

    val connected: Flow<Boolean> = context.serverStore.data.map { it[Keys.CONNECTED] ?: false }

    suspend fun save(creds: ServerCredentials) {
        context.serverStore.edit {
            it[Keys.URL] = creds.baseUrl
            it[Keys.USER] = creds.username
            it[Keys.PASS] = creds.password
            it[Keys.CONNECTED] = true
        }
    }

    suspend fun setConnected(value: Boolean) {
        context.serverStore.edit { it[Keys.CONNECTED] = value }
    }

    suspend fun clear() {
        context.serverStore.edit { it.clear() }
    }
}
