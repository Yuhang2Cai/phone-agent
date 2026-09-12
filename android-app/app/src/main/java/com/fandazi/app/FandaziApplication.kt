package com.fandazi.app

import android.app.Application
import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.feature.auth.ApiAuthGateway
import com.fandazi.app.feature.auth.EncryptedSessionStorage
import com.fandazi.app.feature.auth.SessionRepository
import com.fandazi.app.feature.agent.AgentRepository
import com.fandazi.app.feature.agent.HttpAgentRepository

class FandaziApplication : Application() {
    lateinit var sessionRepository: SessionRepository
        private set
    lateinit var agentRepository: AgentRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val apiClient = ApiClient(BuildConfig.API_BASE_URL) { sessionRepository.tokenOrNull() }
        sessionRepository = SessionRepository(ApiAuthGateway(apiClient), EncryptedSessionStorage(this))
        agentRepository = HttpAgentRepository(BuildConfig.API_BASE_URL) { sessionRepository.tokenOrNull() }
    }
}
