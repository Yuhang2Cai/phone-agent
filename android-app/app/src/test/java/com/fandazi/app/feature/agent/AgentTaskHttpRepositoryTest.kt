// AG03真实HTTP契约测试：固定只读URL和2.2正文，失败不回退旧/messages。
package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiResult
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** 使用真实OkHttp和生产任务解析器；夹具不包含账号、授权或真实交易。 */
class AgentTaskHttpRepositoryTest {
    private val command = """{"schemaVersion":"2.2","commandId":"command","action":"pause","state":"prepared","reconcileStatus":"retrying","reasonCode":null,"focusVersion":2,"affectedTaskIds":["task"]}"""
    private fun repository(server: MockWebServer): HttpAgentTaskRepository {
        val client = OkHttpClient()
        return HttpAgentTaskRepository(server.url("/api/").toString(), { request ->
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) ApiResult.Success(response.body!!.string())
                else ApiResult.Failure(com.fandazi.app.core.network.ApiError.Http(response.code, "合成失败"))
            }
        }, { AgentMessageResult(AgentStatus.COMPLETED, "答复", "task_state", null, emptyList()) })
    }
    /** 原文空白保持字节意义；不会夹带旧mode/clientMessageKey或猜测候选列表。 */
    @Test fun explicitInputKeepsOriginalAndNeverUsesLegacyMessageRoute() = runBlocking {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody("{}"))
            val result = repository(server).input("session", "  先查询，再回来  ", "request")
            assertTrue(result is ApiResult.Success)
            val sent = server.takeRequest();assertEquals("/api/agent/sessions/session/task-inputs", sent.path)
            val body = JsonParser.parseString(sent.body.readUtf8()).asJsonObject
            assertEquals(setOf("schemaVersion", "requestId", "text"), body.keySet())
            assertEquals("2.2", body["schemaVersion"].asString);assertEquals("  先查询，再回来  ", body["text"].asString)
        }
    }
    /** 控制POST失败后只按原ID做GET，不重新发送原动作或业务正文。 */
    @Test fun lostControlIsReadBySameRequestIdWithoutPostRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setResponseCode(503));server.enqueue(MockResponse().setBody(command))
            val repo = repository(server)
            assertTrue(repo.control("session", AgentTaskControl("same-request", "pause", "task", 3, 2)) is ApiResult.Failure)
            val result = repo.byRequest("session", "same-request")
            assertTrue(result is ApiResult.Success)
            assertEquals("POST", server.takeRequest().method)
            val read = server.takeRequest();assertEquals("GET", read.method);assertEquals("/api/agent/sessions/session/task-commands/by-request/same-request", read.path)
            assertEquals(2, server.requestCount)
        }
    }
    /** 非整数版本、未知生命周期和私有额外字段不得宽松解析成有效目录。 */
    @Test fun invalidDirectoryCannotBecomeEmptySuccess() = runBlocking {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody("""{"schemaVersion":"2.2","focusVersion":1.5,"throughTaskEventId":0,"currentTask":null,"pausedTasks":[]}"""))
            assertTrue(repository(server).directory("session") is ApiResult.Failure)
        }
    }
    /** 空目录必须真实解析成功；不是把网络失败解释成没有任务。 */
    @Test fun emptyDirectoryReadsExactWatermark() = runBlocking {
        MockWebServer().use { server ->
            server.start();server.enqueue(MockResponse().setBody("""{"schemaVersion":"2.2","focusVersion":2,"throughTaskEventId":9,"currentTask":null,"pausedTasks":[]}"""))
            val result = repository(server).directory("session")
            assertTrue(result is ApiResult.Success);assertEquals(9L, (result as ApiResult.Success).value.watermark)
            assertEquals("GET", server.takeRequest().method)
        }
    }
}
