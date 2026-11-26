// 活动桥接策略：将有限业务动作转换为原生 API 请求；不接受任意地址或客户端身份。
package com.fandazi.app.feature.activity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import java.util.UUID

/** 网络执行所需的受控请求；body 仅用于写操作，headers 只允许原幂等键。 */
data class ActivityRequest(val method: String, val path: String, val body: String, val headers: Map<String, String>)

/** 原生网络白名单：所有字段先验证，再构造相对 API 路径，绝不执行 JS 提供的任意地址。 */
object ActivityRequestPolicy {
    /** 输入动作和 JSON，返回受控请求；非法动作、额外字段或格式错误均在发包前拒绝。 */
    fun resolve(action: String, payload: String): ActivityRequest {
        require(payload.length <= 4096) { "活动请求过长" }
        val value = runCatching { JsonParser.parseString(payload).asJsonObject }
            .getOrElse { throw IllegalArgumentException("活动请求格式错误") }
        val allowed = when (action) {
            "tasks" -> setOf("requestId")
            "account" -> emptySet()
            "accept" -> setOf("id", "version", "requestId")
            "detail" -> setOf("id")
            "claim" -> setOf("id", "requestId")
            "transactions" -> setOf("cursor", "limit")
            else -> throw IllegalArgumentException("不支持的活动动作")
        }
        // 拒绝额外字段，尤其是用户ID、金额和完成次数，防止未来消费者误把它们当成权威输入。
        require(value.keySet().all { it in allowed }) { "活动请求包含不允许的字段" }
        val path = when (action) {
            "tasks" -> "activities/tasks" + if (value.has("requestId")) "?requestId=${uuid(value, "requestId")}" else ""
            "account" -> "coins/account"
            "accept" -> "activities/tasks/${uuid(value, "id")}/accept"
            "detail" -> "activities/task-instances/${uuid(value, "id")}"
            "claim" -> "activities/task-instances/${uuid(value, "id")}/claim"
            else -> {
                val limit = if (value.has("limit")) integer(value, "limit", 100) else 20
                val cursor = if (value.has("cursor")) text(value, "cursor").also { require(it.length <= 256) } else null
                "coins/transactions?limit=$limit" + (cursor?.let { "&cursor=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            }
        }
        // 写操作只携带版本和调用方保留的原请求键；这里不生成新键，也不自动重试。
        val writing = action == "accept" || action == "claim"
        val headers = if (writing) mapOf("Idempotency-Key" to uuid(value, "requestId")) else emptyMap()
        val body = if (action == "accept") JsonObject().apply { addProperty("expectedRuleVersion", integer(value, "version", Int.MAX_VALUE)) }.toString() else "{}"
        return ActivityRequest(if (writing) "POST" else "GET", path, body, headers)
    }

    /** 字符串必须是真正的JSON字符串，不把数字、null或对象宽松转换成标识。 */
    private fun text(value: JsonObject, name: String): String {
        val field = value.get(name)
        require(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isString) { "缺少或无效的$name" }
        return field.asString
    }

    /** 公开ID和幂等键限定为完整UUID，阻止路径穿越与查询字符串注入。 */
    private fun uuid(value: JsonObject, name: String): String {
        val raw = text(value, name)
        val parsed = runCatching { UUID.fromString(raw).toString() }.getOrNull()
        require(raw.length == 36 && parsed == raw.lowercase()) { "无效的$name" }
        return requireNotNull(parsed)
    }

    /** 版本及分页大小必须是范围内整数，拒绝浮点截断、溢出和隐式字符串转换。 */
    private fun integer(value: JsonObject, name: String, maximum: Int): Int {
        val field = value.get(name)
        require(field != null && field.isJsonPrimitive && field.asJsonPrimitive.isNumber) { "无效的$name" }
        val number = runCatching { field.asBigDecimal.intValueExact() }.getOrElse { throw IllegalArgumentException("无效的$name") }
        require(number in 1..maximum) { "无效的$name" }
        return number
    }
}
