package com.fandazi.app.feature.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class EncryptedSessionStorage(context: Context) : SessionStorage {
    private val initialization = runCatching {
        EncryptedSharedPreferences.create(
                context,
                PREFERENCES_NAME,
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    private val preferences: SharedPreferences? = initialization.getOrNull()
    override val initializationError: Throwable? = initialization.exceptionOrNull()
    private val gson = Gson()

    override fun read(): Result<PersistedSession?> {
        val currentPreferences = preferences
            ?: return Result.failure(initializationError ?: IllegalStateException("Encrypted session storage unavailable"))
        return try {
            Result.success(currentPreferences.getString(SESSION_KEY, null)?.let {
                gson.fromJson(it, PersistedSession::class.java)
            })
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Result.failure(exception)
        }
    }

    override suspend fun write(value: PersistedSession): Boolean = withContext(Dispatchers.IO) {
        try {
            preferences?.edit()?.putString(SESSION_KEY, gson.toJson(value))?.commit() == true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            false
        }
    }

    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        try {
            preferences?.edit()?.remove(SESSION_KEY)?.commit() == true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            false
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "fandazi_secure_session"
        const val SESSION_KEY = "session"
    }
}
