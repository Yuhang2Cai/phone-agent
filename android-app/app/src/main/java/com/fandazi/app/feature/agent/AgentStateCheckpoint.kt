// 聊天薄恢复索引：仅持有草稿、归属、会话、已应用游标和滚动锚点，不保存正文或执行授权。
package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle

/** 状态变化时仅写变化的小字段；完整消息与卡片只存在于ViewModel内存或受权限保护的后端。 */
class AgentStateCheckpoint(private val saved: SavedStateHandle) {
    init {
        // 升级后的旧Bundle可能仍含大量Serializable列表；明确丢弃旧恢复格式并重新读取服务端事实。
        LEGACY_PRIVATE_KEYS.forEach { saved.remove<Any>(it) }
    }

    /** 读取薄字符串索引；不从旧列表推断任何当前业务资格。 */
    fun string(key: String): String? = saved[key]

    /** 仅字段变化时更新，避免每token重写相同草稿和面板模式。 */
    fun save(state: AgentUiState) {
        putChanged("agent_sheet_mode", state.sheetMode.name)
        putChanged("agent_composer", state.composerText)
        putChanged("agent_last_event_id", state.lastEventId ?: 0L)
        putChanged("agent_scroll_key", state.scrollAnchorKey)
        putChanged("agent_scroll_offset", state.scrollAnchorOffset)
    }

    /** 保守提示实际超预算草稿；冻结2048字是测试夹具，不能据此截断用户输入。 */
    fun draftExceedsBudget(draft: String): Boolean = draft.length.toLong()*2L+2048L > AGENT_BUDGET_BYTES

    /** 保留null语义并跳过未变化值，写入次数随实际薄字段变化增长而非历史长度增长。 */
    private fun <T> putChanged(key: String, value: T) {
        if (saved.get<T>(key) != value) saved[key] = value
    }

    companion object {
        /** Task15.0冻结Agent预算；整Activity预算由真实设备保存状态另测。 */
        const val AGENT_BUDGET_BYTES = 32768L
        /** 旧格式全部敏感/大对象键，移除后不得再次写回；session等薄字段保留。 */
        private val LEGACY_PRIVATE_KEYS = listOf("agent_messages", "agent_events", "agent_candidate_cart",
            "agent_confirmation", "agent_authorization", "agent_created_order_id", "agent_viewed_card",
            "agent_pending_confirmation_card", "agent_pending_after_sale_cards")
    }
}
