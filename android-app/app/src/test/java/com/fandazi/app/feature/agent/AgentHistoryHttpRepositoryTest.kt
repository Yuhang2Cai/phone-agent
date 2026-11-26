// 实际HTTP恢复契约回归：验证GET游标编码、完整正文、公开负载脱敏及旧发送接口兼容。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import com.google.gson.JsonParser

/** 网络替身仅返回协议夹具，使用生产仓库和真实OkHttp解析，不测试镜像实现。 */
class AgentHistoryHttpRepositoryTest {
    /** 长正文不截断，URL游标完整编码，嵌套旧凭证字段在进入UI前已过滤。 */
    @Test fun historyGetDecodesFullBodyAndKeepsCursorIndependent()=runBlocking {
        MockWebServer().use { server ->
            server.start()
            val body="完整正文".repeat(2000)
            server.enqueue(MockResponse().setBody(com.google.gson.Gson().toJson(mapOf(
                "schemaVersion" to "1.0","sessionId" to "session","messages" to listOf(mapOf(
                    "publicId" to "message","role" to "user","content" to body,"traceId" to "trace","clientMessageKey" to "client-key")),
                "events" to listOf(mapOf("id" to 30,"type" to "progress","summary" to "公开摘要","payload" to mapOf("eventId" to "original-event","sequence" to 999,
                    "nested" to mapOf("confirmation_grant" to "hidden","title" to "安全内容")))),
                "throughEventId" to 30,"nextCursor" to null,"messageHistoryStatus" to "AVAILABLE",
                "currentRun" to mapOf("runId" to "run","traceId" to "trace","status" to "succeeded","readOnly" to true)))))
            val repository=HttpAgentRepository(server.url("/api/").toString(),{ null })
            val result=repository.loadHistory("session","a+b/c=.signed",50) as ApiResult.Success
            assertEquals(body,result.value.messages.single().content)
            assertEquals(30L,result.value.throughEventId)
            assertEquals(30L,result.value.events.single().id)
            assertEquals(999L,result.value.events.single().sequence)
            assertFalse(result.value.events.single().payload.values.any { it.contains("hidden") })
            val request=server.takeRequest()
            assertEquals("GET",request.method)
            assertEquals("a+b/c=.signed",request.requestUrl!!.queryParameter("cursor"))
            assertEquals("50",request.requestUrl!!.queryParameter("limit"))
        }
    }

    /** 展示键只在明确新调用提供；旧三参数发送不新增字段或改变模式/正文。 */
    @Test fun optionalDisplayKeyNeverChangesLegacyMessageContract()=runBlocking {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().setBody("""{"status":"COMPLETED","message":"完成","events":[],"cards":[]}""")) }
            val repository=HttpAgentRepository(server.url("/api/").toString(),{ null })
            repository.sendMessage("session","输入",AgentMode.CONFIRM)
            assertFalse(JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject.has("clientMessageKey"))
            repository.sendMessage("session","输入",AgentMode.CONFIRM,"display-key")
            val request=JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
            assertEquals("display-key",request["clientMessageKey"].asString)
            assertEquals("CONFIRM",request["mode"].asString)
            assertEquals("输入",request["text"].asString)
        }
    }
}
