// 旧事件进度兼容格式化：保留已提交测试所需的纯展示入口，正式新聊天使用版本化进度，不触发任何工具。
package com.fandazi.app.feature.agent

/** 只根据旧事件生成短文案；不计算业务状态、不读网络，也不代表新协议工作流成功。 */
object AgentProgressPresentation {
    /** 输入旧事件和发送状态；停止发送即移除活动文案，避免完成后继续显示进行中。 */
    fun current(events: List<AgentEvent>, isSending: Boolean): String? {
        // 发送状态由会话持有，不根据末条事件猜测当前仍在执行。
        if (!isSending) return null
        val event = events.lastOrNull() ?: return "✨  正在理解你的需求"
        val source = "${event.type} ${event.toolName.orEmpty()} ${event.summary}".lowercase()
        // 保留旧事件标签的兼容映射，仅用于历史展示回归；新事件身份与完成状态另有严格边界。
        return when {
            "search" in source || "merchant" in source -> "🔍  正在寻找附近好店"
            "dish" in source || "menu" in source -> "🍜  正在查看菜品"
            "cart" in source || "order" in source || "validate" in source -> "📋  正在准备订单"
            else -> "✨  正在理解你的需求"
        }
    }
}
