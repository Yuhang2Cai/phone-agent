package com.fandazi.app

import android.app.Application
import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.feature.auth.ApiAuthGateway
import com.fandazi.app.feature.auth.EncryptedSessionStorage
import com.fandazi.app.feature.auth.SessionRepository

class FandaziApplication : Application() {
    lateinit var sessionRepository: SessionRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val apiClient = ApiClient(BuildConfig.API_BASE_URL) { sessionRepository.tokenOrNull() }
        sessionRepository = SessionRepository(ApiAuthGateway(apiClient), EncryptedSessionStorage(this))
    }
}
