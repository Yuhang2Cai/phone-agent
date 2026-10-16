// AG04 目标页渲染器：把纯公开展示投影绑定到确认稿 XML，所有恢复动作在 ViewModel 再次校验。
package com.fandazi.app.feature.agent

import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.fandazi.app.R
import java.text.NumberFormat
import java.util.Locale

/** 一个聊天 View 持有一个目标控制器；打开页面本身只切换展示，不提交恢复或业务动作。 */
class AgentGoalPanelController(
    private val root: View,
    private val model: AgentViewModel,
    private val taskPanel: AgentTaskPanelController,
    private val openResource: (AgentGoalResourceSummary) -> Unit
) {
    private val panel: View = root.findViewById(R.id.agent_goal_panel_scroll)
    private val messages: View = root.findViewById(R.id.agent_chat_scroll)
    private val items: LinearLayout = root.findViewById(R.id.agent_goal_items)
    private val actions: LinearLayout = root.findViewById(R.id.agent_goal_actions)
    private val subtitle: TextView = root.findViewById(R.id.agent_chat_subtitle)
    private val composer: View = root.findViewById(R.id.agent_chat_composer)
    private val input: EditText = root.findViewById(R.id.agent_chat_input)
    private val voice: View = root.findViewById(R.id.agent_chat_voice)
    private val send: View = root.findViewById(R.id.agent_chat_send)
    private var open = false
    private var lastGoals: AgentGoalUiState? = null

    /** 菜单显式打开目标状态；没有真实投影时不显示空壳或设计样例。 */
    fun show(): Boolean {
        if (AgentGoalPresentation.from(model.uiState.value.goalState) == null) return false
        open = true;taskPanel.setSuppressed(true);render(model.uiState.value, true);return true
    }

    /** 系统返回或页面动作关闭目标页；只恢复聊天展示，不触发任务恢复。 */
    fun close(): Boolean {
        if (!open) return false
        open = false;panel.isVisible = false;messages.isVisible = true;subtitle.text = "帮你发现更好的餐厅"
        restoreComposer();taskPanel.setSuppressed(false);return true
    }

    /** 状态更新只在目标页已打开时重绑；投影失效立即返回聊天，避免残留旧账号摘要。 */
    fun render(state: AgentUiState, force: Boolean = false) {
        if (!open) { panel.isVisible = false;return }
        val presentation = AgentGoalPresentation.from(state.goalState)
        if (presentation == null) { close();return }
        if (!force && state.goalState == lastGoals) return
        lastGoals = state.goalState
        taskPanel.setSuppressed(true);panel.isVisible = true;messages.isVisible = false
        subtitle.text = "查看目标状态和下一步"
        root.findViewById<TextView>(R.id.agent_goal_title).text = presentation.title
        root.findViewById<TextView>(R.id.agent_goal_status).text = presentation.status
        root.findViewById<TextView>(R.id.agent_goal_description).text = presentation.description
        bindCards(presentation.cards);bindActions(state, presentation)
        val notice = root.findViewById<TextView>(R.id.agent_goal_notice)
        notice.isVisible = !presentation.notice.isNullOrBlank();notice.text = presentation.notice.orEmpty()
        bindComposer(presentation.screen, state.goalState)
    }

    /** 卡片展示公开标题、状态、金额和条件；资源 ID 只传给显式导航回调，不渲染为文本。 */
    private fun bindCards(cards: List<AgentGoalPresentationCard>) {
        items.removeAllViews()
        for (card in cards) {
            val item = LayoutInflater.from(root.context).inflate(R.layout.item_agent_goal, items, false)
            item.findViewById<TextView>(R.id.agent_goal_item_title).text = card.title
            item.findViewById<TextView>(R.id.agent_goal_item_status).text = card.status
            item.findViewById<TextView>(R.id.agent_goal_item_description).text = card.description
            val resource = item.findViewById<TextView>(R.id.agent_goal_item_resource)
            val summary = listOfNotNull(card.resource?.status, card.resource?.amountCents?.let(::money)).joinToString(" · ")
            resource.isVisible = summary.isNotBlank();resource.text = summary
            val completed = item.findViewById<TextView>(R.id.agent_goal_item_completed)
            completed.isVisible = card.completedConditions.isNotEmpty();completed.text = card.completedConditions.joinToString(" · ", "已满足：")
            val missing = item.findViewById<TextView>(R.id.agent_goal_item_missing)
            missing.isVisible = card.missingConditions.isNotEmpty();missing.text = card.missingConditions.joinToString(" · ", "待补充：")
            items.addView(item)
        }
    }

    /** 动作两列排列；服务端白名单、当前任务和未知请求状态在每次绑定与点击时都复核。 */
    private fun bindActions(state: AgentUiState, presentation: AgentGoalPresentationData) {
        actions.removeAllViews()
        presentation.actions.chunked(2).forEach { group ->
            val row = LinearLayout(root.context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            }
            group.forEachIndexed { index, action ->
                val button = TextView(root.context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (index > 0) marginStart = dp(8) }
                    gravity = android.view.Gravity.CENTER;text = action.label;textSize = 14f
                    val serverAction = action.id in RECOVERY_ACTIONS
                    val current = state.taskState.directory?.current
                    val eligible = action.enabled && (!serverAction || AgentGoalActionPolicy.canRecover(state.goalState, current, action.id))
                    isEnabled = eligible;alpha = if (eligible) 1f else .4f
                    val primary = index == 0 && eligible
                    setBackgroundResource(if (primary) R.drawable.agent_task_primary else R.drawable.agent_task_secondary)
                    setTextColor(ContextCompat.getColor(root.context, if (primary) R.color.surface_card else R.color.group_buy_green))
                    if (eligible) setOnClickListener {
                        when (action.id) {
                            "return_chat" -> close()
                            "view_resource" -> action.resource?.let(openResource)
                            in RECOVERY_ACTIONS -> if (state.goalState.unresolvedRequestId != null) model.recheckGoalState() else model.recoverGoal(action.id)
                        }
                    }
                }
                row.addView(button)
            }
            actions.addView(row)
        }
    }

    /** 未知写或恢复请求未定位时输入区只读；其他状态保留原聊天输入，不自动填入恢复文案。 */
    private fun bindComposer(screen: AgentGoalScreen, goals: AgentGoalUiState) {
        composer.isVisible = true
        val readonly = screen == AgentGoalScreen.UNKNOWN || goals.unresolvedRequestId != null || goals.submittingRequestId != null
        input.isEnabled = !readonly;voice.isEnabled = !readonly;send.isEnabled = !readonly
        input.alpha = if (readonly) .55f else 1f;voice.alpha = if (readonly) .4f else 1f;send.alpha = if (readonly) .4f else 1f
        if (readonly) input.hint = "正在只读核查原请求，请勿重复提交"
    }

    /** 返回聊天恢复原输入能力和静态提示；草稿正文由原控制器持有，不在这里清空或改写。 */
    private fun restoreComposer() {
        input.isEnabled = true;voice.isEnabled = true;send.isEnabled = true
        input.alpha = 1f;voice.alpha = 1f;send.alpha = 1f;input.hint = "有任何美食需求都可以问我…"
    }

    /** 金额来自公开分值字段；本地仅做货币格式化，不推断折扣或成交状态。 */
    private fun money(cents: Long): String = NumberFormat.getCurrencyInstance(Locale.CHINA).format(cents / 100.0)
    private fun dp(value: Int): Int = (value * root.resources.displayMetrics.density).toInt()

    private companion object {
        val RECOVERY_ACTIONS = setOf("retry_reads", "reprepare_remaining", "replan_remaining", "recheck_original", "drop_remaining")
    }
}
