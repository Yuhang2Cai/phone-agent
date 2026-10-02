// 公开卡片解码模块：HTTP 与 SSE 共用同一递归脱敏边界，结果仅用于显示和显式点击导航。
package com.fandazi.app.feature.agent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 无网络副作用；保留公开字段结构，拒绝任何层级的私有凭证进入持久化聊天状态。 */
internal object AgentPublicCards {
    /** 规范化字段名仅用于脱敏判断，规则与既有仓库完全一致，不扩大业务卡片权限。 */
    fun isPublicField(key: String): Boolean {
        val normalized = key.replace("_", "").lowercase()
        return !normalized.contains("grant") && !normalized.contains("token") &&
            !normalized.contains("secret") && !normalized.startsWith("private") && normalized != "authorization"
    }

    /** 递归处理对象与数组；基本值原样复制，不通过字符串替换破坏业务正文。 */
    fun publicJson(value: JsonElement): JsonElement = when {
        value.isJsonObject -> JsonObject().also { clean -> value.asJsonObject.entrySet()
            .filter { isPublicField(it.key) }.forEach { clean.add(it.key, publicJson(it.value)) } }
        value.isJsonArray -> JsonArray().also { clean -> value.asJsonArray.forEach { clean.add(publicJson(it)) } }
        else -> value.deepCopy()
    }

    /** 空值保持空串，嵌套公开结构使用 JSON 保存；不会把字符串再次套引号。 */
    private fun valueText(value: JsonElement): String = when {
        value.isJsonNull -> ""
        value.isJsonPrimitive -> value.asString
        else -> publicJson(value).toString()
    }

    /** 解析 Spring 已补全的平铺卡；未提供 type 的非法卡不推测业务类型或资源。 */
    fun decode(value: JsonObject): AgentCardPayload? {
        fun text(key: String) = value.get(key)?.takeIf { it.isJsonPrimitive }?.asString
        val type = text("type")?.takeIf(String::isNotBlank) ?: return null
        val fields = value.entrySet().filter { it.key !in setOf("type", "storePublicId", "dealPublicId", "orderPublicId") && isPublicField(it.key) }
            .associate { it.key to valueText(it.value) }
        return AgentCardPayload(type, text("storePublicId"), text("dealPublicId"), text("orderPublicId"), fields)
    }

    /** 从实时事件提取已补全卡；无卡片或异常结构不产生样例兜底，HTTP 仍可回补正常结果。 */
    fun fromEvent(event: AgentEvent): List<AgentCardPayload> {
        val sources = listOfNotNull(event.payload["card"], event.payload["candidates"])
        val cards = sources.flatMap { source -> runCatching {
            val value = JsonParser.parseString(source)
            val values = if (value.isJsonArray) value.asJsonArray.toList() else listOf(value)
            values.mapNotNull { if (it.isJsonObject) decode(it.asJsonObject) else null }
        }.getOrDefault(emptyList()) }
        // 待确认事件可以只携带 summary 而没有 card；摘要已经由 Spring 重查，不要求旧购物车。
        val confirmation = if (event.type == "confirmation_required") event.payload["summary"]?.let { source ->
            runCatching { confirmation(JsonParser.parseString(source).asJsonObject) }.getOrNull()
        } else null
        return cards + listOfNotNull(confirmation)
    }

    /** 根据公开动作生成同一种业务卡片；SSE 与 HTTP 的售后确认不再使用不同身份通道。 */
    fun confirmation(summary: JsonObject): AgentCardPayload? {
        val action = summary.get("action")?.takeIf { it.isJsonPrimitive }?.asString
        val type = when(action) {
            "create_group_buy_order", "pay_mock_order" -> "confirmation"
            "request_refund" -> "after_sale_refund"
            "submit_complaint" -> "after_sale_complaint"
            else -> return null
        }
        val card = summary.deepCopy().apply { addProperty("type", type) }
        return decode(card)
    }
}
