// 应用入口：初始化运行所需的组件与配置。
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
import com.fandazi.app.feature.ordering.PreferencesCartStateStore
import com.fandazi.app.feature.profile.AutomationSettingsRepository
import com.fandazi.app.feature.profile.HttpAutomationSettingsRepository
import com.fandazi.app.feature.deal.GroupBuyingRepository
import com.fandazi.app.feature.deal.HttpGroupBuyingRepository

class FandaziApplication : Application() {
    /** 首页、搜索及门店公开浏览共享网络仓库，所有业务事实来自同一 Spring 目录。 */
    lateinit var catalogRepository: com.fandazi.app.feature.store.CatalogRepository
        private set
    lateinit var apiClient: ApiClient
        private set
    lateinit var sessionRepository: SessionRepository
        private set
    lateinit var agentRepository: AgentRepository
        private set
    lateinit var orderingRepository: OrderingRepository
        private set
    lateinit var cartStateStore: PreferencesCartStateStore
        private set
    lateinit var automationSettingsRepository: AutomationSettingsRepository
        private set
    lateinit var groupBuyingRepository: GroupBuyingRepository
        private set

// 初始化组件与页面状态。
    override fun onCreate() {
        super.onCreate()
        apiClient = ApiClient(
            baseUrl = BuildConfig.API_BASE_URL,
            tokenProvider = { sessionRepository.tokenOrNull() }
        )
        sessionRepository = SessionRepository(ApiAuthGateway(apiClient), EncryptedSessionStorage(this))
        orderingRepository = HttpOrderingRepository(apiClient)
        cartStateStore = PreferencesCartStateStore(this)
        automationSettingsRepository = HttpAutomationSettingsRepository(apiClient)
        groupBuyingRepository = HttpGroupBuyingRepository(apiClient)
        // 复用同一 ApiClient，保留游客公开读取和已登录用户的统一认证处理。
        catalogRepository = com.fandazi.app.feature.store.HttpCatalogRepository(apiClient)
        agentRepository = HttpAgentRepository(
            baseUrl = BuildConfig.API_BASE_URL,
            tokenProvider = { sessionRepository.tokenOrNull() }
        )
    }
}
