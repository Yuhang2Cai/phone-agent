// 凭据包兼容回归：用合成JSON检查新增刷新字段穿过协议与加密存储序列化，不保存真实凭据。
package com.fandazi.app.feature.auth
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** 此组指定失败证明旧两字段模型会静默丢刷新能力，不能把服务端实现当端侧交付。 */
class SessionRenewalPackageTest {
    /** 服务端返回完整凭据包，端侧反序列化再序列化必须保留续期字段。 */
    @Test fun responseRetainsRefreshPackage() {
        val wire="""{"token":"synthetic-access","user":{"username":"synthetic","email":"synthetic@example.invalid"},"sessionId":"synthetic-session","refreshToken":"synthetic-refresh","accessExpiresAtEpochMs":123,"refreshExpiresAtEpochMs":456}"""
        val value=JsonParser.parseString(Gson().toJson(Gson().fromJson(wire,AuthResponse::class.java))).asJsonObject
        assertTrue("完整登录响应不得丢失刷新凭据",value.has("refreshToken"))
        assertTrue("完整登录响应必须保留稳定身份",value.has("sessionId"))
    }
    /** 原加密JSON存储也需保存完整包与预先落盘的请求标识，使进程死亡后的丢响应可恢复。 */
    @Test fun persistedSessionRetainsRefreshAndPendingRequest() {
        val wire="""{"token":"synthetic-access","user":{"username":"synthetic","email":"synthetic@example.invalid"},"sessionId":"synthetic-session","refreshToken":"synthetic-refresh","accessExpiresAtEpochMs":123,"refreshExpiresAtEpochMs":456,"pendingRefreshRequestId":"synthetic-request"}"""
        val value=JsonParser.parseString(Gson().toJson(Gson().fromJson(wire,PersistedSession::class.java))).asJsonObject
        assertTrue("持久会话不得丢失刷新凭据",value.has("refreshToken"))
        assertTrue("轮换请求标识必须与旧凭据共同保存",value.has("pendingRefreshRequestId"))
    }
}
