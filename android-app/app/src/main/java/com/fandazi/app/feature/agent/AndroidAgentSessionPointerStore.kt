// Android 会话指针持久层：应用被系统终止后仍能找到同一认证身份的服务端聊天历史。
package com.fandazi.app.feature.agent

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

/** 仅保存随机会话 ID 和已尝试发送标记；账号与认证会话身份先散列，不在偏好文件写入凭据或正文。 */
class AndroidAgentSessionPointerStore(context: Context) : AgentSessionPointerStore {
    private val preferences = context.applicationContext.getSharedPreferences("agent_session_pointer", Context.MODE_PRIVATE)

    /** 身份不匹配或磁盘内容损坏时返回空，由调用方创建新会话而非沿用其他账号的历史。 */
    override fun read(owner: String, authIdentity: String): AgentSessionPointer? = runCatching {
        if (preferences.getString(KEY_IDENTITY_DIGEST, null) != digest(owner, authIdentity)) return@runCatching null
        val sessionId = preferences.getString(KEY_SESSION_ID, null) ?: return@runCatching null
        UUID.fromString(sessionId)
        AgentSessionPointer(sessionId, preferences.getBoolean(KEY_HAS_ATTEMPTED, false))
    }.onFailure { Log.w(TAG, "会话指针读取失败：${it.javaClass.simpleName}") }.getOrNull()

    /** 在发包前同步提交薄指针，防止进程恰在服务器受理请求后退出而失去恢复入口。 */
    override fun write(owner: String, authIdentity: String, value: AgentSessionPointer) {
        runCatching {
            val committed = preferences.edit()
                .putString(KEY_IDENTITY_DIGEST, digest(owner, authIdentity))
                .putString(KEY_SESSION_ID, value.sessionId)
                .putBoolean(KEY_HAS_ATTEMPTED, value.hasAttempted)
                .commit()
            if (!committed) Log.w(TAG, "会话指针持久化未提交")
        }.onFailure { Log.w(TAG, "会话指针持久化失败：${it.javaClass.simpleName}") }
    }

    /** 退出认证边界时一次清掉全部恢复入口，不能仅清内存中的 SavedState。 */
    override fun clear() {
        runCatching {
            if (!preferences.edit().clear().commit()) Log.w(TAG, "会话指针清除未提交")
        }.onFailure { Log.w(TAG, "会话指针清除失败：${it.javaClass.simpleName}") }
    }

    /** 散列只作身份等值比较，不保存用户名、认证会话 ID 或 JWT 原文。 */
    private fun digest(owner: String, authIdentity: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$owner\u0000$authIdentity".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "AgentSessionPointer"
        const val KEY_IDENTITY_DIGEST = "identity_digest"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_HAS_ATTEMPTED = "has_attempted"
    }
}
