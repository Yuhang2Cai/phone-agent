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
import com.fandazi.app.feature.purchase.HttpPurchaseRepository
import com.fandazi.app.feature.purchase.PurchaseRepository
import com.fandazi.app.feature.voucher.HttpVoucherRepository
import com.fandazi.app.feature.voucher.VoucherRepository
import com.fandazi.app.feature.support.AfterSaleRepository
import com.fandazi.app.feature.support.HttpAfterSaleRepository

class FandaziApplication : Application() {
    /** 记忆属于独立Agent，Android只通过Spring代理读取和管理。 */
    lateinit var memoryRepository: com.fandazi.app.feature.memory.MemoryRepository
        private set
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
    /** Task6 报价与订单共享认证客户端，401 由购买页保留原目标并进入账号密码登录。 */
    lateinit var purchaseRepository: PurchaseRepository
        private set
    /** 本人订单与券只读查询共享认证客户端，不负责售后写入。 */
    lateinit var voucherRepository: VoucherRepository
        private set
    /** Task8 售后读写仓库；复用登录态，所有金额、资格和处理状态由后端返回。 */
    lateinit var afterSaleRepository: AfterSaleRepository
        private set

// 初始化组件与页面状态。
    override fun onCreate() {
        super.onCreate()
        apiClient = ApiClient(
            baseUrl = BuildConfig.API_BASE_URL,
            tokenProvider = { sessionRepository.tokenOrNull() }
        )
        sessionRepository = SessionRepository(ApiAuthGateway(apiClient), EncryptedSessionStorage(this))
        memoryRepository = com.fandazi.app.feature.memory.MemoryRepository(apiClient)
        orderingRepository = HttpOrderingRepository(apiClient)
        cartStateStore = PreferencesCartStateStore(this)
        automationSettingsRepository = HttpAutomationSettingsRepository(apiClient)
        groupBuyingRepository = HttpGroupBuyingRepository(apiClient)
        // 复用同一 ApiClient，保留游客公开读取和已登录用户的统一认证处理。
        catalogRepository = com.fandazi.app.feature.store.HttpCatalogRepository(apiClient)
        // Task6 只接入真实 Spring 购买接口，不能使用固定套餐或测试仓库作为运行期兜底。
        purchaseRepository = HttpPurchaseRepository(apiClient)
        // 只接入本人真实订单/券读取，不引入延期二维码或模拟核销调用入口。
        voucherRepository = HttpVoucherRepository(apiClient)
        // 售后页面使用统一认证客户端，防止页面自行拼接令牌或维护第二套服务地址。
        afterSaleRepository = HttpAfterSaleRepository(apiClient)
        agentRepository = HttpAgentRepository(
            baseUrl = BuildConfig.API_BASE_URL,
            tokenProvider = { sessionRepository.tokenOrNull() }
        )
    }
}
