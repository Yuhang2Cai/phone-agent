package com.fandazi.app.feature.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EncryptedSessionStorage(context: Context) : SessionStorage {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        PREFERENCES_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    private val gson = Gson()

    override fun read(): PersistedSession? = runCatching {
        preferences.getString(SESSION_KEY, null)?.let { gson.fromJson(it, PersistedSession::class.java) }
    }.getOrNull()

    override suspend fun write(value: PersistedSession): Boolean = withContext(Dispatchers.IO) {
        runCatching { preferences.edit().putString(SESSION_KEY, gson.toJson(value)).commit() }.getOrDefault(false)
    }

    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        runCatching { preferences.edit().remove(SESSION_KEY).commit() }.getOrDefault(false)
    }

    private companion object {
        const val PREFERENCES_NAME = "fandazi_secure_session"
        const val SESSION_KEY = "session"
    }
}
