// 未确认请求薄引用：只保存动作、公开目标、版本和原幂等键；不保存令牌、余额或进度。
package com.fandazi.app.feature.activity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.security.MessageDigest

/** 存储函数由宿主注入，写入必须同步成功后才能发包，避免进程被杀后丢失原键。 */
class ActivityPendingStore(private val read: () -> String?, private val write: (String?) -> Boolean) {
    /** 读取同一认证身份的薄引用；其他身份不得获得旧请求。 */
    fun load(owner: String): String? {
        val raw = read() ?: return null
        // 损坏数据不猜测目标，也不能继续新写覆盖；由显式本地错误提醒重新检查。
        val record = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrElse { throw IllegalStateException("活动请求引用损坏") }
        if (record.get("owner")?.asString != digest(owner)) { check(write(null)) { "活动引用清除失败" }; return null }
        val action = record.get("action").asString
        val payload = record.getAsJsonObject("payload")
        ActivityRequestPolicy.resolve(action, payload.toString())
        return JsonObject().apply {addProperty("action", action);add("payload", payload)}.toString()
    }
    /** 保存经过白名单验证的写请求；已有未知请求不能被另一逻辑写覆盖。 */
    fun save(owner: String, action: String, payload: String) {
        require(action == "accept" || action == "claim")
        ActivityRequestPolicy.resolve(action, payload)
        val value = JsonParser.parseString(payload).asJsonObject
        val existing = load(owner)?.let {JsonParser.parseString(it).asJsonObject}
        // JSON 对象等价比较不依赖字段顺序；相同键但目标/版本不同也必须拒绝。
        check(existing == null || (existing.get("action").asString == action && existing.get("payload") == value)) { "先核查原活动请求" }
        val record = JsonObject().apply {addProperty("owner", digest(owner));addProperty("action", action);add("payload", value)}
        check(write(record.toString())) { "活动请求引用保存失败" }
    }
    /** 只有原请求已经获得确定结果才能按原键清除，不允许任意清空其他请求。 */
    fun clear(owner: String, requestId: String): Boolean {
        val pending = load(owner)?.let {JsonParser.parseString(it).asJsonObject} ?: return true
        if (pending.getAsJsonObject("payload").get("requestId").asString != requestId) return false
        check(write(null)) { "活动请求引用清除失败" };return true
    }
    /** 磁盘只存身份摘要以隔离同账号新会话，不落用户名或认证令牌。 */
    private fun digest(owner: String): String = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it)}
}
