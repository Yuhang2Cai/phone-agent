package com.fandazi.app

import android.app.Application
import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.feature.auth.ApiAuthGateway
import com.fandazi.app.feature.auth.EncryptedSessionStorage
import com.fandazi.app.feature.auth.SessionRepository
import com.fandazi.app.feature.agent.AgentRepository
import com.fandazi.app.feature.agent.HttpAgentRepository
import com.fandazi.app.feature.ordering.HttpOrderingRepository
import com.fandazi.app.feature.ordering.OrderingRepository

class FandaziApplication : Application() {
    lateinit var apiClient: ApiClient
        private set
    lateinit var sessionRepository: SessionRepository
        private set
    lateinit var agentRepository: AgentRepository
        private set
    lateinit var orderingRepository: OrderingRepository
        private set

    override fun onCreate() {
        super.onCreate()
        apiClient = ApiClient(
            baseUrl = BuildConfig.API_BASE_URL,
            tokenProvider = { sessionRepository.tokenOrNull() }
        )
        sessionRepository = SessionRepository(ApiAuthGateway(apiClient), EncryptedSessionStorage(this))
        orderingRepository = HttpOrderingRepository(apiClient)
        agentRepository = HttpAgentRepository(
            baseUrl = BuildConfig.API_BASE_URL,
            tokenProvider = { sessionRepository.tokenOrNull() }
        )
    }
}
