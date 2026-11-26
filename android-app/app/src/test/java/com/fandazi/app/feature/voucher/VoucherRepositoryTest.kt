// Task7 数据边界测试：真实 HTTP 客户端解析合成协议，验证独立身份、付款事实与错误保留；不做 UI 视觉 TDD。
package com.fandazi.app.feature.voucher

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 网络替身只提供测试 JSON，仓库与错误映射真实执行；不能替代本人权限和真实 DB 验证。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VoucherRepositoryTest {
    /** 秒杀券新增状态不能导致整个本人券列表解析失败，中文也不能冒称可使用。 */
    @Test fun seckillVoucherStatesRemainReadOnlyFacts() = runBlocking {
        val server=MockWebServer();server.start()
        try {
            val repository=HttpVoucherRepository(ApiClient(server.url("/api/").toString(),tokenProvider={null}))
            val labels=mapOf("PENDING_PAYMENT" to "待支付","CLOSED_TIMEOUT" to "超时关闭","NOT_YET_VALID" to "未到使用日期","UNAVAILABLE" to "暂不可用")
            labels.forEach {(status,label)->
                server.enqueue(MockResponse().setBody(voucher("v1","o1",false).replace("\"status\":\"UNUSED\"","\"status\":\"$status\"")))
                val result=repository.getVoucher("v1")
                assertTrue("秒杀券状态应被协议接受：$status",result is ApiResult.Success)
                assertEquals(label,VoucherPresentation.voucherStatus(status))
            }
        } finally {server.shutdown()}
    }
    /** 超时关闭订单有明确中文状态，但没有付款事实且不属于可使用分段。 */
    @Test fun timedOutOrderIsNotPaidOrAvailable() {
        assertEquals("超时关闭",VoucherPresentation.orderStatus("CLOSED_TIMEOUT"))
        assertFalse(VoucherPresentation.matches(order("CLOSED_TIMEOUT",null),OrderSegment.AVAILABLE))
        assertTrue(VoucherPresentation.amount(order("CLOSED_TIMEOUT",null)).startsWith("合计"))
    }
    /** 已有申请只读查询必须绑定原券；404/401不造空成功，身份串券也拒绝。 */
    @Test
    fun existingRefundLinkIsReadOnlyAndKeepsResourceIdentity() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"requestId":"r1","voucherId":"v1"}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"requestId":"r2","voucherId":"another-voucher"}"""))
            val repository = HttpVoucherRepository(ApiClient(server.url("/api/").toString(), tokenProvider = { null }))
            assertEquals("r1", (repository.getRefundLink("v1") as ApiResult.Success).value.requestId)
            assertEquals(404, ((repository.getRefundLink("v2") as ApiResult.Failure).error as ApiError.Http).code)
            assertEquals(401, ((repository.getRefundLink("v2") as ApiResult.Failure).error as ApiError.Http).code)
            assertTrue(repository.getRefundLink("v2") is ApiResult.Failure)
            // 四次只允许GET，不能因查进度而调用prepare、submit或核销接口。
            val requests = List(4) { server.takeRequest() }
            assertTrue(requests.all { it.method == "GET" })
            assertEquals(listOf("/api/refunds/by-voucher/v1", "/api/refunds/by-voucher/v2",
                "/api/refunds/by-voucher/v2", "/api/refunds/by-voucher/v2"), requests.map { it.path })
        } finally { server.shutdown() }
    }

    /** 缺后端资格必须成为协议错误，禁止默认 false 隐藏非法输出。 */
    @Test
    fun missingEligibilityFailsInsteadOfInventingPermission() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(voucher("v1", "o1", false).replace(",\"canRefund\":false", "")))
            val result = HttpVoucherRepository(ApiClient(server.url("/api/").toString(), tokenProvider = { null })).getVoucher("v1")
            assertTrue(result is ApiResult.Failure)
        } finally { server.shutdown() }
    }

    /** 不同券身份原样传到请求；401 保留供登录处理，随后同一资源重试可恢复真实解析。 */
    @Test
    fun distinctResourcesAndAuthenticationRetryKeepTheirIdentity() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(voucher("v1", "o1", true)))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody(voucher("v2", "o2", false)))
            val repository = HttpVoucherRepository(ApiClient(server.url("/api/").toString(), tokenProvider = { "test-token" }))
            assertEquals("o1", (repository.getVoucher("v1") as ApiResult.Success).value.orderId)
            assertEquals(401, ((repository.getVoucher("v2") as ApiResult.Failure).error as ApiError.Http).code)
            val recovered = (repository.getVoucher("v2") as ApiResult.Success).value
            assertEquals("v2", recovered.publicId)
            assertFalse(recovered.canRefund)
            assertEquals(listOf("/api/vouchers/v1", "/api/vouchers/v2", "/api/vouchers/v2"),
                List(3) { server.takeRequest().path })
        } finally { server.shutdown() }
    }

    /** 同为过期订单也不能猜是否付过款；显示只依赖 payment 快照，分段只认真实状态。 */
    @Test
    fun paymentLabelsAndSegmentsRespectBackendFacts() {
        val pending = order("PENDING_PAYMENT", null)
        assertTrue(VoucherPresentation.amount(pending).startsWith("待支付"))
        assertTrue(VoucherPresentation.amount(order("EXPIRED", null)).startsWith("合计"))
        assertTrue(VoucherPresentation.amount(order("EXPIRED", 10001)).contains("100.01"))
        assertTrue(VoucherPresentation.matches(order("USED", 10001), OrderSegment.COMPLETED))
        assertFalse(VoucherPresentation.matches(pending, OrderSegment.AVAILABLE))
        assertTrue(VoucherPresentation.matches(order("REFUNDED", 10001), OrderSegment.REFUNDED))
        assertEquals("已退款", VoucherPresentation.voucherStatus("REFUNDED"))
        assertEquals("已过期", VoucherPresentation.voucherStatus("EXPIRED"))
    }

    /** 合成公开响应仅用于字段保留测试，金额/资格明确提供，不指向生产样例。 */
    private fun voucher(id: String, order: String, refundable: Boolean) =
        """{"publicId":"$id","orderId":"$order","dealId":"d","dealTitle":"合成套餐","storeId":"s","storeName":"合成门店","status":"UNUSED","canRefund":$refundable,"expiresAt":"2030-01-01T00:00:00Z","redeemedAt":null,"amountCents":10001,"orderQuantity":1,"orderStatus":"PAID","orderCreatedAt":"2026-10-01T00:00:00Z","dealDescription":"合成说明"}"""

    /** 合成状态输入只验证金额标签，不创建或改变真实业务事实。 */
    private fun order(status: String, paid: Int?) = GroupOrderSummary("o", "d", "s", 1, 10001, status, 0, emptyList(), paidTotalCents = paid)
    /** 分摊金额拒绝小数和负数，整数分事实不能在后台转换时被截断。 */
    @Test fun fractionalAndNegativeMoneyRemainProtocolFailures() = runBlocking {
        val server=MockWebServer().also { it.start() }
        try {
            val repository=HttpVoucherRepository(ApiClient(server.url("/api/").toString(), {null}))
            for(amount in listOf("1.5","-1")) {
                server.enqueue(MockResponse().setBody(voucher("v1","o1",false).replace("\"amountCents\":10001","\"amountCents\":$amount")))
                assertTrue(repository.getVoucher("v1") is ApiResult.Failure)
            }
        } finally { server.shutdown() }
    }

    /** 订单嵌套券必须绑定本订单，实付分摊和集合身份保持原校验。 */
    @Test fun wrongOwnershipAndWrongPaymentAllocationRemainFailures() = runBlocking {
        val server=MockWebServer().also { it.start() }
        try {
            val repository=HttpVoucherRepository(ApiClient(server.url("/api/").toString(), {null}))
            for((owner,paid) in listOf("other" to 10001,"o1" to 10002)) {
                val row="""{"orderId":"o1","dealId":"d","storeId":"s","storeName":"合成门店","dealTitle":"合成套餐","quantity":1,"totalCents":10001,"paidTotalCents":$paid,"status":"PAID","voucherCount":1,"canReview":false,"vouchers":[${voucher("v1",owner,false)}]}"""
                server.enqueue(MockResponse().setBody("[$row]"))
                assertTrue(repository.listOrders() is ApiResult.Failure)
            }
        } finally { server.shutdown() }
    }

}
