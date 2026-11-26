// 会话绑定请求测试：验证原生桥接发包时的凭据护栏，防止排队中的旧操作借用新账号令牌。
package com.fandazi.app.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** 只替换远端HTTP为真实本机服务器；断言实际是否发包及服务器接收到的授权头。 */
class SessionBoundApiClientTest {
    /** 调用源已经失效时必须在真实网络发送前拒绝，而非等响应回来后仅隐藏UI。 */
    @Test fun changedSessionNeverSendsQueuedWrite() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val api = ApiClient(server.url("/api/").toString(), { "new-account-token" })
            val result = api.postJsonBound("activities/task-instances/id/claim", "{}", emptyMap()) { throw IOException("活动会话已变化") }
            assertTrue("身份护栏拒绝后不能发出业务请求", result is ApiResult.Failure)
            assertEquals(0, server.requestCount)
        }
    }

    /** 发包凭据来自绑定检查的同一快照；不能再次读取另一个账号的默认供应器。 */
    @Test fun boundCredentialIsTheOnlyCredentialSent() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val api = ApiClient(server.url("/api/").toString(), { "different-account-token" })
            assertTrue(api.getBound("activities/tasks") { "bound-session-token" } is ApiResult.Success)
            assertEquals("Bearer bound-session-token", server.takeRequest().getHeader("Authorization"))
        }
    }

    /** 活动绑定请求既要保留供 RN 解析的完整 JSON，也要携带统一客户端解析出的稳定 reasonCode。 */
    @Test fun boundRequestPreservesStructuredBusinessError() = runBlocking {
        MockWebServer().use { server ->
            val body = """{"reasonCode":"CLAIM_EXPIRED","message":"领奖已过期"}"""
            server.enqueue(MockResponse().setResponseCode(409).setBody(body))
            val api = ApiClient(server.url("/api/").toString(), { null })
            val result = api.getBound("activities/tasks") { "bound-token" } as ApiResult.Failure
            // 合并秒杀网络契约后，结构化正文与稳定原因码必须同时存在，不能让任一消费者重新猜中文。
            assertEquals(ApiError.Http(409, body, "CLAIM_EXPIRED"), result.error)
        }
    }

    /** 旧页面仍只接收可展示文案，同时保留秒杀等状态机消费者依赖的稳定 reasonCode。 */
    @Test fun ordinaryRequestKeepsExistingMessageFormat() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"reasonCode":"CLAIM_EXPIRED","message":"领奖已过期"}"""))
            val result = ApiClient(server.url("/api/").toString(), { null }).get("activities/tasks") as ApiResult.Failure
            // 普通页面不能显示整段 JSON，但合并后也不能丢失服务端明确给出的业务原因。
            assertEquals(ApiError.Http(409, "领奖已过期", "CLAIM_EXPIRED"), result.error)
        }
    }
}
