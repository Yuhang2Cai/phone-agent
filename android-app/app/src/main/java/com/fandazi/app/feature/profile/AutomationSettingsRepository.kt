// 个人资料模块：封装持久化查询与写入。
package com.fandazi.app.feature.profile

import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.math.BigDecimal

data class AutomationPolicy(
    val enabled: Boolean,
    val defaultAddressId: Long,
    val maxAmount: BigDecimal
)

interface AutomationSettingsRepository {
// 加载策略。
    suspend fun loadPolicy(): ApiResult<AutomationPolicy?>
// 保存策略。
    suspend fun savePolicy(policy: AutomationPolicy): ApiResult<AutomationPolicy>
}

class HttpAutomationSettingsRepository(
    private val apiClient: ApiClient,
    private val gson: Gson = Gson()
) : AutomationSettingsRepository {
// 加载策略。
    override suspend fun loadPolicy(): ApiResult<AutomationPolicy?> = when (val result = apiClient.get("automation/policy")) {
        is ApiResult.Success -> {
            val body = result.value.trim()
            if (body.isEmpty() || body == "null") ApiResult.Success(null) else parsePolicy(body)
        }
        is ApiResult.Failure -> result
    }

// 保存策略。
    override suspend fun savePolicy(policy: AutomationPolicy): ApiResult<AutomationPolicy> =
        when (val result = apiClient.putJson("automation/policy", gson.toJson(policy))) {
            is ApiResult.Success -> parsePolicy(result.value)
            is ApiResult.Failure -> result
        }

// 解析策略。
    private fun parsePolicy(body: String): ApiResult<AutomationPolicy> = runCatching {
        val json = JsonParser.parseString(body).asJsonObject
        AutomationPolicy(
            enabled = json.get("enabled").asBoolean,
            defaultAddressId = json.get("defaultAddressId").asLong,
            maxAmount = json.get("maxAmount").asBigDecimal
        )
    }.fold(
        onSuccess = { ApiResult.Success(it) },
        onFailure = { ApiResult.Failure(ApiError.Network(it.message ?: "自动化设置解析失败")) }
    )
}
