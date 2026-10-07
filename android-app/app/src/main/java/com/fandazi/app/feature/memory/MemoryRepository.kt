package com.fandazi.app.feature.memory

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson

/** Agent记忆管理协议。全部业务内容来自Spring代理，不提供示例数据或离线伪记录。 */
data class MemoryItem(val id: String, val key: String, val value: String, val status: String,
    val source: String, val expiresAt: String, val updatedAt: String, val confidence: Double,
    val evidence: String, val version: Int, val sensitive: Boolean)

/** 记忆页统一认证仓库；用户归属来自共享ApiClient令牌，不发送可篡改userId。 */
class MemoryRepository(private val api: ApiClient) {
    private val gson = Gson()
    private data class Listing(val items: List<MemoryItem>)
    /** 读取本人记录，HTTP/网络失败保持显式失败，不回退空列表掩盖问题。 */
    suspend fun list(): ApiResult<List<MemoryItem>> = when (val result = api.get("/api/agent/memories")) {
        is ApiResult.Success -> try { ApiResult.Success(gson.fromJson(result.value, Listing::class.java).items) }
            catch (_: Exception) { ApiResult.Failure(com.fandazi.app.core.network.ApiError.Network("记忆响应无效")) }
        is ApiResult.Failure -> result
    }
    /** 修改使用所展示版本，服务器检测并发纠正；客户端不猜状态或TTL。 */
    suspend fun edit(item: MemoryItem, value: String) = api.putJson("/api/agent/memories/${item.id}",
        gson.toJson(mapOf("value" to value, "version" to item.version)))
    /** 敏感确认仅在独立确认弹窗由用户明确同意后传true。 */
    suspend fun confirm(item: MemoryItem) = api.postJson("/api/agent/memories/${item.id}/confirm",
        gson.toJson(mapOf("version" to item.version, "sensitive" to item.sensitive)))
    /** 删除和清空的最终状态仍重新从API读取，不能先本地伪造成功。 */
    suspend fun delete(item: MemoryItem) = api.delete("/api/agent/memories/${item.id}")
    /** 清空只有用户同意后调用，不修改订单、聊天历史或其他账号。 */
    suspend fun clear() = api.delete("/api/agent/memories")
}
