// AG03八屏任务入口渲染：连接正式公开目录与已确认XML，不在客户端生成业务对象或自动选择任务。
package com.fandazi.app.feature.agent

import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.fandazi.app.R

/** 一个聊天View持有一个控制器；动作交给ViewModel实时复核，列表渲染不触发任何写入。 */
class AgentTaskPanelController(private val root: View, private val model: AgentViewModel) {
    private var listOpen = false
    // 自动歧义列表只服务于无焦点窗口；与用户主动查看目录分开，避免插问返回后仍遮挡聊天停点。
    private var listOpenedAutomatically = false
    private var dismissedFocus: Long? = null
    private var lastState: AgentUiState? = null
    /** 目标页占用内容区时暂时抑制任务条；关闭目标页后按同一权威状态恢复。 */
    private var suppressed = false
    private val panel: View = root.findViewById(R.id.agent_task_panel)
    private val list: View = root.findViewById(R.id.agent_task_list_scroll)
    private val messages: View = root.findViewById(R.id.agent_chat_scroll)
    private val items: LinearLayout = root.findViewById(R.id.agent_task_list_items)

    init {
        root.findViewById<View>(R.id.agent_task_list_action).setOnClickListener { showList() }
        root.findViewById<View>(R.id.agent_task_list_back).setOnClickListener { closeList() }
    }

    /** 更多菜单显式打开任务目录，只切换展示，不恢复或取消任何一项。 */
    fun showList() { if (model.uiState.value.taskState.enabled) { listOpen = true;listOpenedAutomatically = false;render(model.uiState.value, true) } }

    /** 系统返回优先关闭列表；返回对话不切任务，不提交原业务文字。 */
    fun closeList(): Boolean {
        if (!listOpen) return false
        dismissedFocus = model.uiState.value.taskState.directory?.focusVersion
        listOpen = false;listOpenedAutomatically = false;render(model.uiState.value, true);return true
    }

    /** 只切换本地展示；进入目标页会关闭任务目录，但不会改变任务焦点或生命周期。 */
    fun setSuppressed(value: Boolean) {
        if (suppressed == value) return
        suppressed = value
        if (value) { listOpen = false;listOpenedAutomatically = false;panel.isVisible = false;list.isVisible = false }
        else render(model.uiState.value, true)
    }

    /** 同一公开状态只绑定一次；多行任务名/停点自然换行，不能按设计样例固定业务高度。 */
    fun render(state: AgentUiState, force: Boolean = false) {
        if (suppressed) { panel.isVisible = false;list.isVisible = false;return }
        val tasks = state.taskState
        if (!force && lastState?.taskState == tasks && lastState?.messages?.lastOrNull()?.text == state.messages.lastOrNull()?.text) return
        lastState = state
        if (!tasks.enabled) { panel.isVisible = false;list.isVisible = false;messages.isVisible = true;listOpen = false;listOpenedAutomatically = false;items.removeAllViews();return }
        val directory = tasks.directory
        // 插问完成与显式返回分别到达时可能短暂无焦点，并自动显示多个暂停候选。
        // 权威目录恢复当前任务后关闭这种自动列表，让H屏展示原停点；手动打开的目录不受后台更新打断。
        if (directory?.current != null && listOpenedAutomatically) { listOpen = false;listOpenedAutomatically = false }
        // 多个暂停对象且没有焦点时仅展开选择列表，不按“最近”替用户恢复；返回对话后同焦点不反复弹回。
        if (!listOpen && directory != null && directory.current == null && directory.paused.size > 1 && dismissedFocus != directory.focusVersion) { listOpen = true;listOpenedAutomatically = true }
        list.isVisible = listOpen;messages.isVisible = !listOpen
        // 新账号首次只读加载本身不是任务交接，不凭loading展示不存在的任务卡；
        // 真实当前/暂停任务、未知请求和提交中的控制仍展示恢复或核查入口，执行资格继续由策略锁定。
        panel.isVisible = !listOpen && (directory?.current != null || directory?.paused?.size == 1 || tasks.notice != null ||
            tasks.unresolvedRequestId != null || tasks.submittingRequestId != null)
        if (listOpen) { bindList(state);return }
        val task = directory?.current ?: directory?.paused?.singleOrNull()
        val command = tasks.commands.values.firstOrNull { !it.terminal && (task == null || task.id in it.affected) }
        // 本进程仍等待首次POST回包时展示已确认C交接屏；失败或冷恢复只有薄指针时才展示D核查屏。
        // 两者均保持业务和生命周期动作禁用，不能用展示状态区别放松执行资格。
        val attention = command?.reconcile == "needs_attention" ||
            (tasks.unresolvedRequestId != null && tasks.submittingRequestId == null) || tasks.requiresReload
        val pending = command != null || tasks.loading || tasks.submittingRequestId != null
        val title = root.findViewById<TextView>(R.id.agent_task_title)
        val status = root.findViewById<TextView>(R.id.agent_task_status)
        val stop = root.findViewById<TextView>(R.id.agent_task_stop)
        title.text = when { pending && !attention -> "任务交接中";task != null -> if (task.lifecycle == "active") "当前任务 · ${task.title}" else task.title;else -> "任务状态" }
        status.text = when { attention -> "需要核查 · 结果尚未确认";pending -> "正在保存原任务停点";task != null -> status(task);else -> "正在只读恢复" }
        stop.text = when {
            attention -> "上次请求结果尚未核实，请先查看最新状态。"
            pending -> "交接完成后再继续处理；不会重复提交原操作。"
            task != null -> explanation(task)
            else -> "正在读取服务端状态，不会重发原请求。"
        }
        val primary = root.findViewById<TextView>(R.id.agent_task_primary_action)
        val cancel = root.findViewById<TextView>(R.id.agent_task_cancel_action)
        val listAction = root.findViewById<TextView>(R.id.agent_task_list_action)
        primary.setOnClickListener(null);cancel.setOnClickListener(null)
        primary.isVisible = true;cancel.isVisible = !attention && !pending && task?.lifecycle == "active"
        listAction.isVisible = !attention && !pending
        when {
            attention -> {
                primary.text = root.context.getString(R.string.agent_task_recheck)
                bind(primary, true, false) { model.recheckTaskState() }
            }
            pending -> { primary.text = "处理中";bind(primary, false, false) {} }
            task != null -> {
                val action = if (task.lifecycle == "paused") "resume" else "pause"
                primary.text = root.context.getString(if (action == "resume") R.string.agent_task_resume else R.string.agent_task_pause)
                bind(primary, AgentTaskControlPolicy.canControl(tasks, task.id, action), action == "resume") { model.controlTask(action, task.id) }
                bind(cancel, AgentTaskControlPolicy.canControl(tasks, task.id, "cancel"), false) { model.controlTask("cancel", task.id) }
            }
            else -> { primary.isVisible = false;cancel.isVisible = false }
        }
        val notice = root.findViewById<TextView>(R.id.agent_task_notice)
        notice.isVisible = attention || task?.lifecycle == "paused"
        notice.text = if (attention) "核查只读取状态，不会自动提交业务。" else "原确认不可执行，恢复也不会直接提交业务。"
    }

    /** 暂停、歧义和终态共享目录；终态行没有恢复/取消按钮，恢复始终显式指定taskId。 */
    private fun bindList(state: AgentUiState) {
        val tasks = state.taskState;val directory = tasks.directory
        val ambiguous = directory?.current == null && (directory?.paused?.size ?: 0) > 1 && dismissedFocus != directory?.focusVersion
        root.findViewById<TextView>(R.id.agent_task_list_title).text = if (ambiguous) "请选择要继续的任务" else "任务列表 · 已暂停 ${directory?.paused?.size ?: 0} 项"
        // 歧义选择展示实际最近用户原文和明确选择提示；无历史时不虚构用户发言。
        // 普通目录仍保留恢复说明，二者不共用取消入口，防止选择任务时误触终止操作。
        val help = root.findViewById<TextView>(R.id.agent_task_list_help)
        help.text = if (ambiguous) listOfNotNull(state.messages.lastOrNull { it.fromUser }?.text?.let { "你：$it" }, "饭搭子：你想继续下面哪一个任务？").joinToString("\n") else "恢复后回到原停点，待确认操作需要重新确认。最多保留5项暂停任务。"
        help.textSize = if (ambiguous) 14f else 12f
        help.setTextColor(ContextCompat.getColor(root.context, if (ambiguous) R.color.group_buy_text else R.color.group_buy_muted))
        root.findViewById<View>(R.id.agent_task_choice_notice).isVisible = ambiguous
        items.removeAllViews()
        // G屏仅列可继续的暂停候选；终态和当前任务仍在用户主动打开的B目录中展示。
        val ordered = if (ambiguous) directory?.paused.orEmpty() else directory?.paused.orEmpty() + listOfNotNull(directory?.current) + tasks.tasks.values.filter { it.lifecycle in setOf("completed", "cancelled", "failed") }
        for ((index,task) in ordered.distinctBy { it.id }.withIndex()) {
            val item = LayoutInflater.from(root.context).inflate(R.layout.item_agent_task, items, false)
            item.tag = task.id
            item.findViewById<TextView>(R.id.agent_task_item_title).text = task.title
            item.findViewById<TextView>(R.id.agent_task_item_status).text = status(task)
            item.findViewById<TextView>(R.id.agent_task_item_stop).text = explanation(task)
            val actions = item.findViewById<View>(R.id.agent_task_item_actions)
            actions.isVisible = task.lifecycle in setOf("active", "paused")
            val resume = item.findViewById<TextView>(R.id.agent_task_item_resume)
            resume.isVisible = task.lifecycle == "paused"
            // 每个候选都必须显式点击；第一项主色只是视觉层级，不自动恢复或预先填槽。
            if (ambiguous) resume.text = "恢复${task.title}任务"
            bind(resume, AgentTaskControlPolicy.canControl(tasks, task.id, "resume"), !ambiguous || index == 0) {
                model.controlTask("resume", task.id);closeList()
            }
            val cancel = item.findViewById<TextView>(R.id.agent_task_item_cancel)
            cancel.isVisible = !ambiguous
            bind(cancel, !ambiguous && AgentTaskControlPolicy.canControl(tasks, task.id, "cancel"), false) { model.controlTask("cancel", task.id) }
            items.addView(item)
        }
        if (ordered.isEmpty()) {
            val empty = TextView(root.context);empty.setTextColor(ContextCompat.getColor(root.context, R.color.group_buy_muted));empty.text = "暂无可恢复任务";empty.setPadding(0, 16, 0, 16);items.addView(empty)
        }
    }

    /** 服务端停点字段映射为用户能理解的未完成事项；未知字段保持通用提示，不补业务值。 */
    private fun status(task: AgentTaskProjection): String {
        val prefix = when (task.lifecycle) { "paused" -> "已暂停";"completed" -> "已结束";"cancelled" -> "已取消";"failed" -> "已结束 · 未完成";else -> if (task.generation > 1) "已恢复" else "进行中" }
        val stop = when (task.stop.kind) {
            "waiting_input" -> "等待补充${field(task.stop.awaitingField)}"
            "waiting_confirmation" -> if (task.lifecycle == "paused") "原确认失效" else "等待确认"
            "awaiting_followup" -> "等待后续选择"
            "running" -> "正在处理"
            else -> if (task.lifecycle == "active") "等待继续" else "仅供查看"
        }
        return "$prefix · $stop"
    }

    /** 说明恢复只回到停点；终态不暗示重新执行资格，暂停确认一律提示原确认无效。 */
    private fun explanation(task: AgentTaskProjection): String = when {
        task.lifecycle in setOf("completed", "cancelled", "failed") -> "历史任务仅供查看，不可恢复；已产生的业务记录仍保留。"
        task.lifecycle == "paused" -> "已保留当前停点，恢复后继续补充；原确认需重新准备。"
        task.stop.kind == "waiting_input" -> "请补充${field(task.stop.awaitingField)}，我会继续处理。"
        task.stop.kind == "waiting_confirmation" -> "请核对下方新的确认信息；只有明确确认才会提交。"
        task.stop.kind == "awaiting_followup" -> "可补充限制或明确选择对象继续。"
        else -> "任务保留当前进度，不会自动重复提交业务。"
    }

    /** 只映射公开槽位名称，不解析或拼造用户尚未提供的内容。 */
    private fun field(name: String?): String = when (name) {
        "reason" -> "退款原因";"subject" -> "投诉主题";"description" -> "投诉正文";"quantity" -> "购买数量";"voucherId" -> "团购券";"dealId" -> "套餐";"storeId" -> "门店";else -> "原任务所需信息或对象"
    }

    /** 每次绑定先撤旧监听；禁用控件仍保持48dp尺寸，点击时ViewModel还会重查资格。 */
    private fun bind(view: TextView, enabled: Boolean, primary: Boolean, action: () -> Unit) {
        view.setOnClickListener(null);view.isEnabled = enabled;view.alpha = if (enabled) 1f else .4f
        view.setBackgroundResource(if (primary) R.drawable.agent_task_primary else R.drawable.agent_task_secondary)
        view.setTextColor(ContextCompat.getColor(root.context, if (primary) R.color.surface_card else R.color.group_buy_green))
        if (enabled) view.setOnClickListener { action() }
    }
}
