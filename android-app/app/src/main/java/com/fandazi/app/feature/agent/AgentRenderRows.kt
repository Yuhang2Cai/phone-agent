// 聊天展示投影：主线程把业务状态冻结为四类纯数据，后台差量比较不读取认证或授权对象。
package com.fandazi.app.feature.agent

/** 恰好四类回收池；业务卡种、角色与状态均是数据分支而非新增回收类型。 */
enum class AgentRowType { CARD, MESSAGE, GUIDE, PROGRESS }

/** 共用消息骨架中的样式分支；标题和动作槽由完整绑定互斥配置。 */
enum class AgentMessageStyle { USER, ASSISTANT, ERROR, CONTEXT }

/** 不可变展示行。key只是定位身份；确认资格由主线程快照及点击时ViewModel再次核验。 */
sealed interface AgentRenderRow {
    val key: String
    val type: AgentRowType
    /** 文字、错误和上下文共用消息Holder；retry只是显式重试入口，不自动发送。 */
    data class Message(override val key: String, val body: String, val style: AgentMessageStyle,
        val title: String = "", val retry: Boolean = false) : AgentRenderRow { override val type = AgentRowType.MESSAGE }
    /** fields在投影时复制且封装为不可修改Map，避免Diff线程观察业务对象后续变更。 */
    data class Card(override val key: String, val card: AgentCardPayload, val current: Boolean,
        /** 区分临时忙碌禁用和历史失效，短回复等待期间不能把仍有效的卡误标失效。 */
        val expiredConfirmation: Boolean = false, val taskMode: Boolean = false) : AgentRenderRow { override val type = AgentRowType.CARD }
    /** 登录与空态互斥复用同一欢迎和操作槽。 */
    data class Guide(override val key: String, val login: Boolean) : AgentRenderRow { override val type = AgentRowType.GUIDE }
    /** 步骤在投影阶段去重并转为公开文案，Holder仅更新已有槽位。 */
    data class Progress(override val key: String, val title: String, val steps: List<AgentProgressStep>) : AgentRenderRow { override val type = AgentRowType.PROGRESS }
}

/** 单个步骤的稳定身份、公开标签、界面状态与活动指示。 */
data class AgentProgressStep(val key: String, val label: String, val status: String, val active: Boolean)

/** 主线程投影入口；不请求网络、不做写入、不改变原事件处理顺序。 */
object AgentRenderRows {
    /** 输入完整UI状态和当前认证/动作资格，返回与业务数据隔离的有序行快照。 */
    fun project(state: AgentUiState, authenticated: Boolean, isCurrent: (AgentCardPayload) -> Boolean): List<AgentRenderRow> {
        // 认证优先于任何私有内容；退出状态不依赖旧消息是否已清理。
        if (!authenticated) return listOf(AgentRenderRow.Guide("guide:login", true))
        val rows = mutableListOf<AgentRenderRow>()
        val lastUser = state.messages.lastOrNull { it.fromUser }?.displayKey
        // 每条消息是父身份，卡片固定归属；历史事件仍留在状态中，但不再作为完成后的进度行展示。
        state.messages.forEach { message ->
            val parent = "message:${message.displayKey}"
            rows += AgentRenderRow.Message(parent, message.text, if (message.fromUser) AgentMessageStyle.USER else AgentMessageStyle.ASSISTANT)
            if (message.fromUser && message.displayKey == lastUser) state.viewedCard?.let { card ->
                rows += AgentRenderRow.Message("$parent:context", listOfNotNull(card.fields["description"],
                    card.fields["priceCents"]?.toIntOrNull()?.let { "团购价：${com.fandazi.app.core.time.BusinessDisplay.money(it)}" }).joinToString("\n"),
                    AgentMessageStyle.CONTEXT, "上次查看：" + listOfNotNull(card.fields["storeName"],card.fields["title"] ?: card.fields["name"]).joinToString(" · "))
            }
            if (!message.fromUser) {
                rows += cards(parent, message.cards, isCurrent, state)
            }
        }
        // 进度只属于正在处理的当前轮；完成、失败或等待用户补充/确认后隐藏，避免在回答下面重复显示。
        // 当前轮用最近用户键固定归属；没有消息的连接阶段仍保持稳定系统键。
        val current = "message:${lastUser ?: "connection"}:current"
        if (state.isSending) rows += progress(current, state.events, true)
        if (state.messages.none { !it.fromUser && it.cards == state.cards } || state.isSending)
            rows += cards(current, state.cards, isCurrent, state)
        if (state.isRecoveringHistory) rows += AgentRenderRow.Message("system:history", "正在恢复聊天历史…", AgentMessageStyle.ASSISTANT)
        else if (state.error == null) state.historyNotice?.let { rows += AgentRenderRow.Message("system:history", it, AgentMessageStyle.ASSISTANT) }
        // 只沿用原业务和只读历史重试入口，展示阶段绝不产生请求。
        val error = when (val e = state.error) {
            is AgentError.Server -> e.message
            is AgentError.Network -> e.message
            AgentError.Authentication -> "登录状态已失效，请重新登录"
            else -> null
        }
        error?.let { rows += AgentRenderRow.Message("system:error", state.historyNotice ?: it, AgentMessageStyle.ERROR, "暂时无法完成请求", true) }
        if (rows.isEmpty()) rows += AgentRenderRow.Guide("guide:empty", false)
        return java.util.Collections.unmodifiableList(rows)
    }

    /** 服务端资源/事件身份优先，缺身份只复用卡片创建时的展示键，不取正文hash或render随机键。 */
    private fun cards(parent: String, input: List<AgentCardPayload>, isCurrent: (AgentCardPayload) -> Boolean, state: AgentUiState): List<AgentRenderRow.Card> {
        val seen = mutableSetOf<String>()
        // 按服务端顺序逐项展示并保留 presentation 原始字段，不在投影阶段排序或重编号。
        return input.mapNotNull { card ->
            val identity = card.confirmationIdentity?.let { "${it.runId}:${it.traceId}:${it.eventId}" }
                ?: listOfNotNull(card.storePublicId,card.dealPublicId,card.orderPublicId,card.fields["voucherId"],card.fields["resourceId"],card.fields["couponId"])
                    .takeIf { it.isNotEmpty() }?.joinToString(":") { "${it.length}:$it" } ?: card.localDisplayKey
            val key = "$parent:card:${card.type}:$identity"
            // 同一所属消息的公开资源重放不增加子行，不同所属消息仍独立显示。
            if (!seen.add(key)) null else AgentRenderRow.Card(key,card.copy(fields=java.util.Collections.unmodifiableMap(LinkedHashMap(card.fields))),
                !card.historicalReadOnly && isCurrent(card),
                card.type in setOf("confirmation", "after_sale_refund", "after_sale_complaint") &&
                    (card.historicalReadOnly || (state.taskState.enabled && !state.isSending && !isCurrent(card))), state.taskState.enabled)
        }
    }

    /** 当前轮同run/step保留最后状态；步骤身份独立于展示文案，历史事件不经过此投影。 */
    private fun progress(parent: String, events: List<AgentEvent>, sending: Boolean): AgentRenderRow.Progress {
        val progress = events.filter { it.type == "progress" && !it.payload["stepId"].isNullOrBlank() }
            .asReversed().distinctBy { "${it.runId}:${it.payload["stepId"]}" }.asReversed()
        val steps = progress.map { event ->
            val status = when ((event.payload["stepStatus"] ?: event.status)?.lowercase()) {
                "running", "in_progress", "started" -> "进行中"
                "waiting_input", "needs_input" -> "等待补充"
                "waiting_confirmation", "awaiting_confirmation" -> "等待确认"
                "cancelled" -> "已取消"
                "failed", "error" -> "失败"
                "completed", "succeeded" -> "已完成"
                else -> "等待处理"
            }
            AgentProgressStep("${event.runId}:${event.payload["stepId"]}",event.payload["label"] ?: event.summary.ifBlank { "处理请求" },status,sending && status=="进行中")
        }.ifEmpty { listOf(AgentProgressStep("connection", "正在连接助手",if(sending) "进行中" else "已结束",sending)) }
        // 新版按唯一 composite 标签展示任务进度；旧布尔仅供历史事件回放兼容。
        return AgentRenderRow.Progress("$parent:progress",if(events.any{it.type=="intent_decided" && (it.payload["intent"]=="composite" || it.payload["isComposite"]=="true")}) "任务进度" else "处理进度",java.util.Collections.unmodifiableList(steps))
    }
}
