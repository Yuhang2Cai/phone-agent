// AI 助手完整聊天页：承载消息、流式进度、真实业务卡片和错误状态。
package com.fandazi.app.feature.agent

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.MainActivity
import com.fandazi.app.feature.auth.SessionState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * AI 助手正式页面。
 *
 * 页面只负责把 ViewModel 的状态映射成完整视觉界面；门店、套餐和订单字段全部来自服务端，
 * 卡片点击后才发起明确导航，接收流式事件本身不会自动切换 Fragment。
 */
class AgentChatFragment : Fragment(R.layout.fragment_agent_chat) {
    private lateinit var viewModel: AgentViewModel
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: TextView
    /** 游客仅渲染登录卡，不订阅私有会话、不调用发送接口。 */
    private val authenticated: Boolean get() = (requireActivity() as MainActivity).applicationSession().state.value is SessionState.Authenticated

    /** 绑定页面控件、注册发送操作，并订阅会话状态。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewModel = ViewModelProvider(
            requireActivity(),
            AgentViewModelFactory((requireActivity().application as FandaziApplication).agentRepository)
        )[AgentViewModel::class.java]
        content = view.findViewById(R.id.agent_chat_content)
        scroll = view.findViewById(R.id.agent_chat_scroll)
        input = view.findViewById(R.id.agent_chat_input)
        send = view.findViewById(R.id.agent_chat_send)

        // Figma 右上角为更多菜单，不冒充关闭；退出只取消页面订阅，不清除历史。
        view.findViewById<View>(R.id.agent_chat_close).setOnClickListener { anchor ->
            PopupMenu(requireContext(), anchor).apply {
                menu.add("返回首页")
                setOnMenuItemClickListener { findNavController().navigate(R.id.homeFragment); true }
                show()
            }
        }
        send.setOnClickListener {
            if (!authenticated) return@setOnClickListener
            viewModel.sendText(input.text.toString())
            input.setText("")
        }
        // 输入内容变化时同步发送按钮状态，避免空文本触发无效请求。
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                send.isEnabled = authenticated && !viewModel.uiState.value.isSending && !text.isNullOrBlank()
            }
            override fun afterTextChanged(editable: Editable?) = Unit
        })
        if (authenticated) viewModel.openAssistant()
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest(::render)
        }
    }

    /**
     * 根据完整页面状态重建聊天内容。
     *
     * 每次重建都会清理上一轮的临时视图，避免多轮请求中残留错误卡或旧卡片；
     * 业务字段只经过布局绑定，不在页面层生成默认门店、套餐或价格。
     */
    private fun render(state: AgentUiState) {
        content.removeAllViews()
        input.isEnabled = authenticated
        send.isEnabled = authenticated && !state.isSending && input.text.toString().isNotBlank()
        if (!authenticated) {
            // 不显示其他账号遗留历史或结果，登录成功由 Activity 回到同一聊天入口。
            val gate = layoutInflater.inflate(R.layout.item_agent_login_gate, content, false)
            gate.findViewById<View>(R.id.agent_login_action).setOnClickListener {
                (requireActivity() as MainActivity).openProtectedDestination(R.id.agentChatFragment)
            }
            gate.findViewById<View>(R.id.agent_login_later).setOnClickListener { findNavController().navigate(R.id.homeFragment) }
            content.addView(gate)
            return
        }
        if (state.messages.isEmpty() && state.cards.isEmpty() && state.error == null && !state.isSending) {
            val empty = layoutInflater.inflate(R.layout.item_agent_empty, content, false)
            listOf(R.id.agent_quick_nearby, R.id.agent_quick_deal, R.id.agent_quick_order).forEach { id ->
                empty.findViewById<TextView>(id).setOnClickListener { viewModel.sendText((it as TextView).text.toString()) }
            }
            content.addView(empty)
        }

        // 先按会话顺序绘制消息，保证用户问题和助手回答在多轮场景中不会交叉。
        state.messages.forEachIndexed { index, message ->
            renderMessage(message)
            // J 摘要来自真实点击选择和其服务端字段，放在最新用户消息之后，不改变原历史结果。
            if (message.fromUser && index == state.messages.indexOfLast { it.fromUser }) state.viewedCard?.let { renderContext(it) }
            // 历史结果绑定对应助手回答，后续输入不会把旧卡片挪到当前回答末尾。
            if (!message.fromUser) {
                if (message.events.any { it.type == "progress" }) renderProgress(state.copy(events = message.events, isSending = false))
                renderCards(message.cards)
            }
        }

        // 进度只属于当前正在处理的一轮；结束后由回复和业务卡片承接结果，不能留下过期的进行中状态。
        if (state.isSending) renderProgress(state)

        // 卡片内容由网关补全；每一种业务卡使用独立布局，避免所有卡片退化为同一个文本块。
        if (state.messages.none { !it.fromUser && it.cards == state.cards } || state.isSending) renderCards(state.cards)

        // 错误卡保留在当前消息之后，重试由 ViewModel 重新发送最近一次用户输入。
        (state.error as? AgentError.Server)?.let { renderError(it.message) }
        (state.error as? AgentError.Network)?.let { renderError(it.message) }
        (state.error as? AgentError.Authentication)?.let { renderError("登录状态已失效，请重新登录") }
        if (state.messages.isNotEmpty() || state.cards.isNotEmpty() || state.isSending) {
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** 只在显式卡片点击时使用既有路由；文本和进度从不触发导航。 */
    private fun renderCards(cards: List<AgentCardPayload>) {
        AgentCardRenderer.render(this, content, cards, viewModel::isCurrentConfirmation) { request ->
            cards.firstOrNull { card -> when (request) {
                is AgentNavigationRequest.Store -> card.storePublicId == request.storePublicId
                is AgentNavigationRequest.Deal -> card.dealPublicId == request.dealPublicId
                is AgentNavigationRequest.Order -> card.orderPublicId == request.orderPublicId
                else -> false
            } }?.let(viewModel::rememberViewedCard)
            when (request) {
                is AgentNavigationRequest.Store -> findNavController().navigate(
                    R.id.storeFragment,
                    Bundle().apply { putString("storePublicId", request.storePublicId) }
                )
                is AgentNavigationRequest.Deal -> findNavController().navigate(
                    R.id.dealDetailFragment,
                    Bundle().apply {
                        putString("storePublicId", request.storePublicId)
                        putString("dealPublicId", request.dealPublicId)
                    }
                )
                // 卡片按钮是用户显式确认；只向 Agent 发送确认语句，实际写入仍由服务端消费短时凭证。
                // 即便点击后状态已被新回复替换，ViewModel仍按原run/摘要版本二次拒绝旧动作。
                is AgentNavigationRequest.AgentReply -> viewModel.replyToCard(request)
                is AgentNavigationRequest.Order -> findNavController().navigate(R.id.groupOrderDetailFragment,
                    Bundle().apply { putString("orderId", request.orderPublicId) })
            }
        }

    }

    /** 将一条消息绑定到统一气泡布局，用户消息靠右、助手消息靠左并显示头像。 */
    private fun renderMessage(message: ChatMessage) {
        val item = layoutInflater.inflate(R.layout.item_agent_message, content, false)
        val root = item.findViewById<LinearLayout>(R.id.agent_message_root)
        val avatar = item.findViewById<TextView>(R.id.agent_message_avatar)
        val text = item.findViewById<TextView>(R.id.agent_message_text)
        text.text = message.text
        if (message.fromUser) {
            text.maxWidth = 285.dp()
            root.gravity = Gravity.END
            avatar.isVisible = false
            text.background = ContextCompat.getDrawable(requireContext(), R.drawable.agent_chat_user_bubble)
        } else {
            root.gravity = Gravity.START
            avatar.isVisible = true
            text.background = ContextCompat.getDrawable(requireContext(), R.drawable.agent_chat_bot_bubble)
        }
        content.addView(item)
    }

    /** 只做公开事实排版，未付款卡不写“实付”，也不宣称服务端已消费当前选择。 */
    private fun renderContext(card: AgentCardPayload) {
        val item = layoutInflater.inflate(R.layout.item_agent_context, content, false)
        item.findViewById<TextView>(R.id.agent_context_title).text = "上次查看：" +
            listOfNotNull(card.fields["storeName"], card.fields["title"] ?: card.fields["name"]).joinToString(" · ")
        item.findViewById<TextView>(R.id.agent_context_body).text = listOfNotNull(card.fields["description"],
            card.fields["priceCents"]?.toIntOrNull()?.let { "团购价：${com.fandazi.app.core.time.BusinessDisplay.money(it)}" }).joinToString("\n")
        content.addView(item)
    }

    /** 把服务端事件按顺序展示为步骤，明确区分已完成、进行中和失败。 */
    private fun renderProgress(state: AgentUiState) {
        val item = layoutInflater.inflate(R.layout.item_agent_progress, content, false)
        val steps = item.findViewById<LinearLayout>(R.id.agent_progress_steps)
        // 同一处理步骤可能多次上报状态；保留最后一次，避免一项任务在界面里重复出现。
        val events = state.events.filter { it.type == "progress" && !it.payload["stepId"].isNullOrBlank() }
            .asReversed().distinctBy { "${it.runId}:${it.payload["stepId"]}" }.asReversed()
        val composite = state.events.any { it.type == "intent_decided" && it.payload["isComposite"] == "true" }
        item.findViewById<TextView>(R.id.agent_progress_title).text = if (composite) "任务进度" else "处理进度"
        if (events.isEmpty()) {
            addProgressStep(steps, "正在连接助手", if (state.isSending) "进行中" else "已结束", state.isSending)
        } else {
            events.forEachIndexed { index, event ->
                // 协议状态是机器可读枚举，必须转换成用户能理解的文案，不能直接展示 running 等原值。
                val status = when ((event.payload["stepStatus"] ?: event.status)?.lowercase()) {
                    "running", "in_progress", "started" -> "进行中"
                    "waiting_input", "needs_input" -> "等待补充"
                    "waiting_confirmation", "awaiting_confirmation" -> "等待确认"
                    "cancelled" -> "已取消"
                    "failed", "error" -> "失败"
                    "completed", "succeeded" -> "已完成"
                    else -> "等待处理"
                }
                addProgressStep(steps, event.payload["label"] ?: event.summary.ifBlank { "处理请求" }, status,
                    state.isSending && status == "进行中")
            }
        }
        content.addView(item)
    }

    /** 创建单个进度步骤；步骤文字来自事件，状态只做界面呈现。 */
    private fun addProgressStep(container: LinearLayout, summary: String, status: String, active: Boolean) {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 38.dp()
        }
        // 原生不定进度只在当前运行步骤存在，暂停/终态重绘后立即移除，不写固定耗时。
        if (active) row.addView(ProgressBar(requireContext()).apply {
            isIndeterminate = true
        }, LinearLayout.LayoutParams(18.dp(), 18.dp()))
        else row.addView(TextView(requireContext()).apply { text = if (status == "已完成") "✓" else "○" }, LinearLayout.LayoutParams(18.dp(), 24.dp()))
        row.addView(TextView(requireContext()).apply {
            text = summary; textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.group_buy_text))
            setPadding(8.dp(), 0, 8.dp(), 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(requireContext()).apply {
            text = status; textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.group_buy_muted))
        })
        container.addView(row)
    }

    /** 创建错误卡并绑定重试操作，错误不会覆盖或清除之前的聊天内容。 */
    private fun renderError(message: String) {
        val item = layoutInflater.inflate(R.layout.item_agent_error, content, false)
        item.findViewById<TextView>(R.id.agent_error_text).text = message
        item.findViewById<TextView>(R.id.agent_error_retry).setOnClickListener { viewModel.retry() }
        content.addView(item)
    }

    /** 统一屏幕密度换算，避免核心布局在不同模拟器上发生尺寸漂移。 */
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()

    /** 离开唯一聊天页时释放 SSE；保留消息历史，回来后从原数据库游标继续。 */
    override fun onDestroyView() {
        viewModel.closeAssistant()
        super.onDestroyView()
    }
}
