package com.example.mimic.data.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first

/** Copies a DataStore's values to/from typed backup entries (sensitive-looking keys are never copied). */
object PrefsBackup {
    suspend fun snapshot(store: DataStore<Preferences>, exclude: Set<String> = emptySet()): Map<String, BackupPref> =
        store.data.first().asMap().mapNotNull { (key, value) ->
            val name = key.name
            if (name in exclude || BackupCodec.isSensitiveKey(name)) return@mapNotNull null
            val pref = when (value) {
                is String -> BackupPref(BackupPref.STRING, value)
                is Int -> BackupPref(BackupPref.INT, value)
                is Long -> BackupPref(BackupPref.LONG, value)
                is Boolean -> BackupPref(BackupPref.BOOL, value)
                is Float -> BackupPref(BackupPref.FLOAT, value)
                is Double -> BackupPref(BackupPref.DOUBLE, value)
                is Set<*> -> BackupPref(BackupPref.STRING_SET, value.filterIsInstance<String>().toSet())
                else -> null
            }
            pref?.let { name to it }
        }.toMap()

    /** Writes [values] over the same keys; keys not in the backup are left as they are. */
    @Suppress("UNCHECKED_CAST")
    suspend fun restore(store: DataStore<Preferences>, values: Map<String, BackupPref>, exclude: Set<String> = emptySet()) {
        store.edit { p ->
            for ((name, pref) in values) {
                if (name in exclude || BackupCodec.isSensitiveKey(name)) continue
                when (pref.type) {
                    BackupPref.STRING -> p[stringPreferencesKey(name)] = pref.value as String
                    BackupPref.INT -> p[intPreferencesKey(name)] = pref.value as Int
                    BackupPref.LONG -> p[longPreferencesKey(name)] = pref.value as Long
                    BackupPref.BOOL -> p[booleanPreferencesKey(name)] = pref.value as Boolean
                    BackupPref.FLOAT -> p[floatPreferencesKey(name)] = pref.value as Float
                    BackupPref.DOUBLE -> p[doublePreferencesKey(name)] = pref.value as Double
                    BackupPref.STRING_SET -> p[stringSetPreferencesKey(name)] = pref.value as Set<String>
                }
            }
        }
    }
}
