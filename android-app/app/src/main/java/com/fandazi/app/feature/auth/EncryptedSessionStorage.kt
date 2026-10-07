// 加密存储会话凭据，避免明文落盘。
package com.fandazi.app.feature.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
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

    init {
        if (initializationError == null) {
            Log.d(TAG, "storage initialized: preferences available")
        } else {
            Log.e(TAG, "storage initialization failed: ${initializationError::class.java.simpleName}: ${initializationError.message}", initializationError)
        }
    }

// 读取当前数据。
    override fun read(): Result<PersistedSession?> {
        val currentPreferences = preferences
            ?: run {
                Log.e(TAG, "read session skipped: preferences unavailable")
                return Result.failure(initializationError ?: IllegalStateException("Encrypted session storage unavailable"))
            }
        return try {
            val session = currentPreferences.getString(SESSION_KEY, null)?.let {
                gson.fromJson(it, PersistedSession::class.java)
            }
            Log.d(TAG, "read session completed: present=${session != null}")
            Result.success(session)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "read session failed: ${exception::class.java.simpleName}: ${exception.message}", exception)
            Result.failure(exception)
        }
    }

// 处理 `write` 相关逻辑。
    override suspend fun write(value: PersistedSession): Boolean = withContext(Dispatchers.IO) {
        try {
            preferences?.edit()?.putString(SESSION_KEY, gson.toJson(value))?.commit() == true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            false
        }
    }

// 清除当前数据。
    override suspend fun clear(): Boolean = withContext(Dispatchers.IO) {
        try {
            preferences?.edit()?.remove(SESSION_KEY)?.commit() == true
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            false
        }
    }

// 读取guest。
    override fun readGuest(): Result<Boolean> {
        val currentPreferences = preferences
            ?: return Result.failure(initializationError ?: IllegalStateException("Encrypted session storage unavailable"))
        return runCatching { currentPreferences.getBoolean(GUEST_KEY, false) }
    }

// 处理 `writeGuest` 相关逻辑。
    override suspend fun writeGuest(): Boolean = withContext(Dispatchers.IO) {
        if (preferences == null) {
            Log.e(TAG, "write guest skipped: preferences unavailable")
            return@withContext false
        }
        try {
            val committed = preferences.edit().putBoolean(GUEST_KEY, true).remove(SESSION_KEY).commit()
            Log.d(TAG, "write guest completed: committed=$committed, stored=${preferences.getBoolean(GUEST_KEY, false)}")
            committed
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Log.e(TAG, "write guest failed: ${exception::class.java.simpleName}: ${exception.message}", exception)
            false
        }
    }

// 清除guest。
    override suspend fun clearGuest(): Boolean = withContext(Dispatchers.IO) {
        try { preferences?.edit()?.remove(GUEST_KEY)?.commit() == true }
        catch (exception: CancellationException) { throw exception }
        catch (exception: Exception) { false }
    }

    private companion object {
        const val TAG = "FandaziSession"
        const val PREFERENCES_NAME = "fandazi_secure_session"
        const val SESSION_KEY = "session"
        const val GUEST_KEY = "guest"
    }
}
