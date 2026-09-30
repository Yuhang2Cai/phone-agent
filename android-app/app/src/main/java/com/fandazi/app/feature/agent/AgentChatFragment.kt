// AI 助手全屏聊天页：承载消息、流式进度、后端卡片和错误状态。
package com.fandazi.app.feature.agent

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * AI 助手正式页面。
 * 页面只负责显示服务端状态；业务卡片点击后才发起明确导航，不自动跳转。
 */
class AgentChatFragment : Fragment(R.layout.fragment_agent_chat) {
    private lateinit var viewModel: AgentViewModel
    private lateinit var content: LinearLayout

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewModel = ViewModelProvider(
            requireActivity(),
            AgentViewModelFactory((requireActivity().application as FandaziApplication).agentRepository)
        )[AgentViewModel::class.java]
        content = view.findViewById(R.id.agent_chat_content)
        view.findViewById<View>(R.id.agent_chat_close).setOnClickListener { findNavController().navigateUp() }
        view.findViewById<View>(R.id.agent_chat_send).setOnClickListener {
            viewModel.sendText(view.findViewById<android.widget.EditText>(R.id.agent_chat_input).text.toString())
            view.findViewById<android.widget.EditText>(R.id.agent_chat_input).setText("")
        }
        viewModel.openAssistant()
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { render(it) }
        }
    }

    /** 将当前状态按消息、进度、卡片和错误四段渲染，避免页面残留上一轮业务内容。 */
    private fun render(state: AgentUiState) {
        content.removeAllViews()
        state.messages.forEach { message ->
            content.addView(TextView(requireContext()).apply {
                text = message.text
                textSize = 16f
                setTextColor(resources.getColor(R.color.group_buy_text, null))
                setPadding(16, 14, 16, 14)
                gravity = if (message.fromUser) android.view.Gravity.END else android.view.Gravity.START
            })
        }
        AgentProgressPresentation.current(state.events, state.isSending)?.let { progress ->
            content.addView(TextView(requireContext()).apply {
                text = progress
                textSize = 15f
                setTextColor(resources.getColor(R.color.group_buy_orange, null))
                setPadding(8, 10, 8, 10)
            })
        }
        AgentCardRenderer.render(this, content, state.cards) { request ->
            when (request) {
                is AgentNavigationRequest.Store -> findNavController().navigate(R.id.storeFragment, Bundle().apply { putString("storePublicId", request.storePublicId) })
                is AgentNavigationRequest.Deal -> findNavController().navigate(R.id.dealDetailFragment, Bundle().apply {
                    putString("storePublicId", request.storePublicId)
                    putString("dealPublicId", request.dealPublicId)
                })
            }
        }
        (state.error as? AgentError.Server)?.let { error ->
            content.addView(TextView(requireContext()).apply {
                text = error.message
                textSize = 15f
                setTextColor(resources.getColor(R.color.warning_red, null))
                setPadding(8, 12, 8, 12)
            })
        }
    }
}
