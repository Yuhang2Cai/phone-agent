// 应用入口：仅初始化当前团购、会话和新Agent依赖；旧外卖仓库不再随应用启动。
package com.fandazi.app

import android.app.Application
import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.feature.auth.ApiAuthGateway
import com.fandazi.app.feature.auth.EncryptedSessionStorage
import com.fandazi.app.feature.auth.SessionRepository
import com.fandazi.app.feature.agent.AgentRepository
import com.fandazi.app.feature.agent.HttpAgentRepository
import com.fandazi.app.feature.deal.GroupBuyingRepository
import com.fandazi.app.feature.deal.HttpGroupBuyingRepository
import com.fandazi.app.feature.purchase.HttpPurchaseRepository
import com.fandazi.app.feature.purchase.PurchaseRepository
import com.fandazi.app.feature.voucher.HttpVoucherRepository
import com.fandazi.app.feature.voucher.VoucherRepository
import com.fandazi.app.feature.support.AfterSaleRepository
import com.fandazi.app.feature.support.HttpAfterSaleRepository

class FandaziApplication : Application(), com.facebook.react.ReactApplication {
    /** 活动首次进入才创建运行时；其他四个根页面不预热 Hermes 或 JS。 */
    val activityRuntime by lazy { com.fandazi.app.feature.activity.ActivityRuntime(this) }
    /** ReactFragment 使用同一宿主，避免 Tab 切换创建多个运行时。 */
    override val reactHost: com.facebook.react.ReactHost get() = activityRuntime.host
    /** 仅新增公开库懒装配；第一次目录IO调用才打开，不改原加密会话启动策略。 */
    private val publicCatalogDatabase by lazy { com.fandazi.app.core.cache.CatalogDatabase.create(this) }
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
        // 业务HTTP/SSE共享发包前写门禁，认证客户端独立调度；公开GET用于恢复，不建立待发送队列。
        val authenticationClient=okhttp3.OkHttpClient.Builder()
            .addInterceptor(com.fandazi.app.core.network.OfflineWriteInterceptor(canWrite={com.fandazi.app.core.cache.CatalogNetworkAccess.available(this)},connected={com.fandazi.app.core.cache.CatalogNetworkAccess.connected(this)}))
            .addInterceptor { chain->chain.proceed(chain.request()).also {if(it.isSuccessful && (chain.request().method=="GET" || chain.request().url.encodedPath.startsWith("/api/auth/")))com.fandazi.app.core.cache.CatalogNetworkAccess.observedHttpSuccess()} }
            .build()
        // 密码/刷新请求独立使用无续期客户端，业务和SSE才共享身份续期拦截器，避免递归死锁。
        val guardedClient=authenticationClient.newBuilder()
            .addInterceptor(com.fandazi.app.core.network.SessionRenewalInterceptor { rejected -> sessionRepository.renewIfNeeded(rejected) })
            .build()
        apiClient = ApiClient(
            okHttpClient=guardedClient,
            baseUrl = BuildConfig.API_BASE_URL,
            tokenProvider = { sessionRepository.tokenOrNull() },
            // 普通业务请求与Agent保持唯一身份源；回调携带实际请求令牌，迟到旧401由身份仓库拒绝误注销。
            onUnauthorized = { rejectedToken -> sessionRepository.invalidateToken(rejectedToken) }
        )
        // 不共享业务Dispatcher，避免所有普通请求等待续期时刷新被排在同一满队列尾部。
        val authApi=ApiClient(BuildConfig.API_BASE_URL,{null},com.fandazi.app.core.network.isolatedAuthenticationClient(authenticationClient))
        sessionRepository = SessionRepository(ApiAuthGateway(authApi), EncryptedSessionStorage(this))
        memoryRepository = com.fandazi.app.feature.memory.MemoryRepository(apiClient)
        // 公开只读装饰器共享缓存；所有受保护仓库仍直接走原认证客户端。
        val catalogCache=com.fandazi.app.core.cache.CatalogCacheEngine(com.fandazi.app.core.cache.RoomCatalogCacheStore {publicCatalogDatabase},onStorageFailure={android.util.Log.w("CatalogCache","公开缓存存储失败："+it)})
        groupBuyingRepository = com.fandazi.app.feature.deal.CachedGroupBuyingRepository(HttpGroupBuyingRepository(apiClient),catalogCache)
        // 复用同一 ApiClient，保留游客公开读取和已登录用户的统一认证处理。
        catalogRepository = com.fandazi.app.feature.store.CachedCatalogRepository(com.fandazi.app.feature.store.HttpCatalogRepository(apiClient),catalogCache)
        // Task6 只接入真实 Spring 购买接口，不能使用固定套餐或测试仓库作为运行期兜底。
        purchaseRepository = HttpPurchaseRepository(apiClient)
        // 只接入本人真实订单/券读取，不引入延期二维码或模拟核销调用入口。
        voucherRepository = HttpVoucherRepository(apiClient)
        // 售后页面使用统一认证客户端，防止页面自行拼接令牌或维护第二套服务地址。
        afterSaleRepository = HttpAfterSaleRepository(apiClient)
        agentRepository = HttpAgentRepository(
            client=guardedClient,
            baseUrl = BuildConfig.API_BASE_URL,
            tokenProvider = { sessionRepository.tokenOrNull() },
            // HTTP与SSE认证拒绝同步唯一身份源；401对应旧令牌时由仓库拒绝误注销。
            onUnauthorized = { rejectedToken -> sessionRepository.invalidateToken(rejectedToken) },
            taskLifecycleEnabled = BuildConfig.AG03_TASK_LIFECYCLE_ENABLED,
            goalRecoveryEnabled = BuildConfig.AG04_GOAL_RECOVERY_ENABLED
        )
    }
}
