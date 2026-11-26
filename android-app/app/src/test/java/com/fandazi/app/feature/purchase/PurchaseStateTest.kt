// Task6 离线状态回归：真实 ViewModel 与解析器，网络使用显式合成边界，不代表在线购买验收。
package com.fandazi.app.feature.purchase

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 防止缺金额仍能确认、异步重复点击产生多单及网络重试更换幂等键。 */
@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseStateTest {
    /** 合成报价仅用于响应解析，资源与授权都不是生产数据。 */
    private val body = """{"quoteId":"test-quote","dealId":"test-deal","quantity":2,"subtotalCents":16000,"discountCents":4700,"totalCents":11300,"expiresAt":"2030-01-01T00:00:00Z","confirmationGrant":"test-grant","dealVersion":1,"dealTitle":"合成套餐","storeName":"合成门店","usageRules":"合成有效期","minQuantity":1,"maxQuantity":2}"""

    @Test fun invalidQuoteNeverDefaultsToZeroMoney() {
        // 缺少金额、非整数金额和不守恒优惠均应拒绝，不补零或在端上重算。
        assertThrows(IllegalArgumentException::class.java) { PurchasePayload.quote(body.replace("\"totalCents\":11300,", "")) }
        assertThrows(ArithmeticException::class.java) { PurchasePayload.quote(body.replace("11300", "11300.5")) }
        assertThrows(IllegalArgumentException::class.java) { PurchasePayload.quote(body.replace("4700", "4701")) }
        assertEquals("¥113", purchaseMoney(PurchasePayload.quote(body).totalCents))
    }

    @Test fun duplicateCreateAndPayClicksOnlySendOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repository(PurchasePayload.quote(body))
            val model = PurchaseViewModel(repo)
            model.quote("test-deal", 2); runCurrent()
            model.createOrder(); model.createOrder(); runCurrent()
            assertEquals(1, repo.createKeys.size)
            model.mockPay(); model.mockPay(); runCurrent()
            assertEquals(1, repo.payKeys.size)
            assertEquals("PAID", model.state.value.order?.status)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun lostResponseRetriesSameKeyButNewQuantityRequiresNewQuote() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repository(PurchasePayload.quote(body), failFirst = true)
            val model = PurchaseViewModel(repo)
            model.quote("test-deal", 2); runCurrent()
            model.createOrder(); runCurrent()
            assertNotNull(model.state.value.quote)
            model.createOrder(); runCurrent()
            assertEquals(repo.createKeys[0], repo.createKeys[1])
            // 新数量只触发重新报价，不沿用已完成订单或金额，提交键也必须更换。
            model.quote("test-deal", 1); runCurrent()
            model.createOrder(); runCurrent()
            assertNotEquals(repo.createKeys[1], repo.createKeys[2])
        } finally { Dispatchers.resetMain() }
    }

    @Test fun authenticationFailureKeepsTyped401AndNoQuote() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repository(PurchasePayload.quote(body), unauthorized = true)
            val model = PurchaseViewModel(repo)
            model.quote("test-deal", 2); runCurrent()
            assertEquals(401, (model.state.value.error as ApiError.Http).code)
            model.createOrder(); runCurrent()
            assertTrue(repo.createKeys.isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    /** 合成网络边界保留调用键，用于验证按钮和重试状态，不模拟数据库完成证据。 */
    private class Repository(val quote: PurchaseQuote, var failFirst: Boolean = false, val unauthorized: Boolean = false) : PurchaseRepository {
        val createKeys = mutableListOf<String>()
        val payKeys = mutableListOf<String>()
        private val order = GroupBuyOrder("test-order", "test-deal", "test-store", 2, 11300, "PENDING_PAYMENT", 2)
        /** 按测试场景返回完整报价或真实错误类型。 */
        override suspend fun createQuote(dealId: String, quantity: Int, sessionId: String): ApiResult<PurchaseQuote> =
            if (unauthorized) ApiResult.Failure(ApiError.Http(401, "请登录")) else ApiResult.Success(quote.copy(quantity = quantity))
        /** 记录实际状态机提交键，首次响应丢失后允许同一键重试。 */
        override suspend fun createOrder(quote: PurchaseQuote, sessionId: String, idempotencyKey: String): ApiResult<GroupBuyOrder> {
            createKeys += idempotencyKey
            if (failFirst) { failFirst = false; return ApiResult.Failure(ApiError.Network("响应丢失")) }
            return ApiResult.Success(order)
        }
        /** 返回合成已支付状态，只用于按钮并发窗口断言。 */
        override suspend fun mockPay(order: GroupBuyOrder, idempotencyKey: String): ApiResult<GroupBuyOrder> { payKeys += idempotencyKey; return ApiResult.Success(order.copy(status = "PAID")) }
        /** 模拟订单回读，不替代真实 HTTP 或归属验收。 */
        override suspend fun getOrder(orderId: String): ApiResult<GroupBuyOrder> = ApiResult.Success(order)
    }
}
