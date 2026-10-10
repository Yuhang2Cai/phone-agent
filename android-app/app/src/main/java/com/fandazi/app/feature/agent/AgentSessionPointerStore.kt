// 智能助手会话指针契约：仅保存恢复所需的会话标识与是否尝试发送，不保存正文、授权或业务结果。
package com.fandazi.app.feature.agent

/** 已认证会话的薄恢复指针；具体历史仍须从服务端按当前身份只读加载。 */
data class AgentSessionPointer(val sessionId: String, val hasAttempted: Boolean)

/** 持久层接口由 Android 实现，测试可注入内存替身以模拟进程终止和重新创建。 */
interface AgentSessionPointerStore {
    /** 仅在账号和认证会话身份都匹配时返回指针，否则视作新会话。 */
    fun read(owner: String, authIdentity: String): AgentSessionPointer?
    /** 发送前同步落盘，避免服务器已受理请求而客户端丢失恢复入口。 */
    fun write(owner: String, authIdentity: String, value: AgentSessionPointer)
    /** 退出或换身份时删除旧账号的本地指针。 */
    fun clear()
}

/** 未提供 Android 持久层的纯逻辑测试保持原有内存行为。 */
object NoopAgentSessionPointerStore : AgentSessionPointerStore {
    override fun read(owner: String, authIdentity: String): AgentSessionPointer? = null
    override fun write(owner: String, authIdentity: String, value: AgentSessionPointer) = Unit
    override fun clear() = Unit
}
