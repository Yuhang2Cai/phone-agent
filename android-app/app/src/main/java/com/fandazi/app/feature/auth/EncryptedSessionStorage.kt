package com.fandazi.app.feature.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson

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

    override fun write(value: PersistedSession) {
        preferences.edit().putString(SESSION_KEY, gson.toJson(value)).commit()
    }

    override fun clear() {
        preferences.edit().remove(SESSION_KEY).commit()
    }

    private companion object {
        const val PREFERENCES_NAME = "fandazi_secure_session"
        const val SESSION_KEY = "session"
    }
}
