// 智能助手模块：管理页面交互和状态展示。
package com.fandazi.app.feature.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.Context
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.navigation.Navigation
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentAgentSheetBinding
import com.fandazi.app.databinding.ItemAgentEventBinding
import com.fandazi.app.databinding.ItemOrderConfirmationBinding
import com.fandazi.app.feature.auth.SessionState
import com.fandazi.app.feature.ordering.OrderingViewModel
import com.fandazi.app.feature.ordering.OrderingViewModelFactory
import com.fandazi.app.feature.profile.TaskAutomationPreference
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class AgentSheetFragment : BottomSheetDialogFragment(R.layout.fragment_agent_sheet) {
    private var binding: FragmentAgentSheetBinding? = null
    private var recognizer: SpeechRecognizer? = null
    private val viewModel: AgentViewModel by activityViewModels {
        AgentViewModelFactory((requireActivity().application as FandaziApplication).agentRepository, requireActivity().application)
    }
    private val orderingViewModel: OrderingViewModel by activityViewModels {
        val activity = requireActivity() as MainActivity
        OrderingViewModelFactory(activity.orderingRepository(), activity.cartStateStore(), activity.cartOwner())
    }
    private var pendingPaymentOrderId: Long? = null
    private var paymentLoadAttempts = 0
    private var paymentLoadInFlight = false
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecognition()
        else viewModel.onAsrError(getString(R.string.agent_asr_unavailable))
    }

// 处理 `onCreateDialog` 相关逻辑。
    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog =
        BottomSheetDialog(requireContext(), theme).also { dialog ->
            dialog.setOnKeyListener { _, keyCode, event ->
                if (keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_UP) return@setOnKeyListener false
                if (viewModel.uiState.value.sheetMode == AgentSheetMode.FULL) {
                    viewModel.toggleFullScreen()
                } else {
                    viewModel.closeAssistant()
                }
                true
            }
            dialog.setOnShowListener {
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                    sheet.background = ColorDrawable(Color.TRANSPARENT)
                    sheet.layoutParams = sheet.layoutParams.apply { height = halfSheetHeight() }
                    BottomSheetBehavior.from(sheet).apply {
                        peekHeight = halfSheetHeight()
                        state = BottomSheetBehavior.STATE_EXPANDED
                    }
                }
            }
        }

// 在视图创建后绑定控件、事件与初始状态。
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val views = FragmentAgentSheetBinding.bind(view)
        binding = views
        val username = ((requireActivity().application as FandaziApplication).sessionRepository.state.value as? SessionState.Authenticated)?.user?.username
        username?.let { TaskAutomationPreference(requireContext(), it).consumeLimit() }?.let(viewModel::preferThisTaskAuto)
        views.closeAgentButton.setOnClickListener { viewModel.closeAssistant() }
        views.expandAgentButton.setOnClickListener { viewModel.toggleFullScreen() }
        views.agentSendButton.setOnClickListener {
            views.agentComposer.clearFocus()
            (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(views.agentComposer.windowToken, 0)
            viewModel.sendComposer()
        }
        views.agentMicButton.setOnClickListener { requestRecognition() }
        views.agentComposer.doAfterTextChanged { viewModel.updateComposer(it?.toString().orEmpty()) }
        views.quickLightMeal.setOnClickListener { viewModel.sendText(getString(R.string.quick_light_meal)) }
        views.quickRepeatOrder.setOnClickListener { viewModel.sendText("30 元以内") }
        views.quickTrackOrder.setOnClickListener { viewModel.sendText("少油少辣") }
        views.agentRetryButton.setOnClickListener {
            pendingPaymentOrderId?.let(::requestPaymentOrder) ?: viewModel.retry()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                orderingViewModel.uiState.collect { state ->
                    val orderId = pendingPaymentOrderId ?: return@collect
                    if (state.currentOrder?.id == orderId) {
                        paymentLoadInFlight = false
                        openPayment(orderId)
                    } else if (state.error != null) {
                        paymentLoadInFlight = false
                        if (paymentLoadAttempts < MAX_PAYMENT_LOAD_ATTEMPTS) requestPaymentOrder(orderId)
                    }
                }
            }
        }
    }

// 渲染当前数据。
    private fun render(state: AgentUiState) {
        val views = binding ?: return
        if (state.sheetMode == AgentSheetMode.HIDDEN) {
            dismissAllowingStateLoss()
            return
        }
        state.createdOrderId?.let { orderId ->
            if (pendingPaymentOrderId == null) {
                pendingPaymentOrderId = orderId
                if (orderingViewModel.uiState.value.currentOrder?.id == orderId) openPayment(orderId)
                else requestPaymentOrder(orderId)
            }
        }
        views.expandAgentButton.text = if (state.sheetMode == AgentSheetMode.FULL) "⌄" else "⌃"
        views.expandAgentButton.contentDescription = if (state.sheetMode == AgentSheetMode.FULL) "收起为半屏" else "展开为全屏"
        if (views.agentComposer.text.toString() != state.composerText) {
            views.agentComposer.setText(state.composerText)
            views.agentComposer.setSelection(state.composerText.length)
        }
        views.agentSendButton.isEnabled = !state.isSending
        views.agentMessages.removeAllViews()
        state.messages.forEach { message ->
            TextView(requireContext()).apply {
                text = message.text
                setTextAppearance(if (message.fromUser) com.google.android.material.R.style.TextAppearance_Material3_BodyMedium else com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setBackgroundResource(if (message.fromUser) R.drawable.agent_user_bubble else R.drawable.agent_bot_bubble)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                val params = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                params.topMargin = dp(8)
                if (message.fromUser) {
                    params.gravity = Gravity.END
                    params.marginStart = dp(54)
                } else {
                    params.gravity = Gravity.START
                    params.marginEnd = dp(54)
                }
                layoutParams = params
                views.agentMessages.addView(this)
            }
        }
        views.agentEvents.removeAllViews()
        val visibleProgress = state.events.map(::userProgress).toSet()
        listOf(
            "✨  正在理解你的需求",
            "🔍  正在寻找附近好店",
            "🍜  正在查看菜品",
            "📋  正在准备订单"
        ).filter(visibleProgress::contains).forEach { progress ->
            val eventViews = ItemAgentEventBinding.inflate(layoutInflater, views.agentEvents, false)
            eventViews.agentEventSummary.text = progress
            eventViews.agentEventMeta.visibility = View.GONE
            views.agentEvents.addView(eventViews.root)
        }
        renderConfirmation(state.confirmation)
        val error = state.error
        views.agentErrorText.visibility = if (error == null) View.GONE else View.VISIBLE
        views.agentRetryButton.visibility = if (error == null) View.GONE else View.VISIBLE
        views.agentErrorText.text = when (error) {
            AgentError.Authentication -> getString(R.string.agent_auth_required)
            is AgentError.Network -> error.message
            is AgentError.Server -> error.message
            is AgentError.Asr -> error.message
            null -> ""
        }
        views.agentRecordingStatus.visibility = if (state.inputState == AgentInputState.IDLE) View.GONE else View.VISIBLE
        views.agentRecordingStatus.text = when (state.inputState) {
            AgentInputState.RECORDING -> "●  正在听…点击麦克风结束"
            AgentInputState.RECOGNIZING -> "✨  正在识别，稍等一下"
            AgentInputState.IDLE -> ""
        }
        views.agentScroll.post { views.agentScroll.fullScroll(View.FOCUS_DOWN) }
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            val targetHeight = if (state.sheetMode == AgentSheetMode.FULL) {
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                halfSheetHeight()
            }
            if (bottomSheet.layoutParams.height != targetHeight) {
                bottomSheet.layoutParams = bottomSheet.layoutParams.apply { height = targetHeight }
                bottomSheet.requestLayout()
            }
            BottomSheetBehavior.from(bottomSheet).apply {
                peekHeight = if (targetHeight > 0) targetHeight else resources.displayMetrics.heightPixels
                this.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

// 渲染confirmation。
    private fun renderConfirmation(card: OrderConfirmation?) {
        val views = binding ?: return
        views.agentConfirmationContainer.removeAllViews()
        if (card == null) return
        val cardViews = ItemOrderConfirmationBinding.inflate(layoutInflater, views.agentConfirmationContainer, false)
        cardViews.confirmMerchant.text = card.merchantName
        cardViews.confirmItems.text = if (card.items.isEmpty()) getString(R.string.agent_items_server_summary) else {
            card.items.joinToString("\n") { "${it.name} ${it.specification} ×${it.quantity}  ${it.price}" }
        }
        cardViews.confirmAddress.text = getString(R.string.agent_default_address_value, card.address)
        cardViews.confirmFees.text = getString(R.string.agent_fees_value, card.deliveryFee, card.discount, card.total)
        cardViews.confirmTotal.text = card.total
        cardViews.confirmMode.text = if (card.authorizationMode == AgentMode.THIS_TASK_AUTO) getString(R.string.agent_this_task_auto) else getString(R.string.agent_confirm_mode)
        cardViews.confirmLimit.text = getString(R.string.agent_limit_value, card.amountLimitText)
        cardViews.confirmAutoAuthorization.isChecked = card.authorizationMode == AgentMode.THIS_TASK_AUTO
        cardViews.confirmAutoAuthorization.setOnClickListener {
            if (cardViews.confirmAutoAuthorization.isChecked) viewModel.selectThisTaskAuto() else viewModel.cancelOrder()
        }
        cardViews.confirmOrderButton.setOnClickListener { viewModel.confirmOrder() }
        cardViews.cancelOrderButton.setOnClickListener { viewModel.cancelOrder() }
        cardViews.modifyOrderButton.setOnClickListener { viewModel.modifyOrder() }
        views.agentConfirmationContainer.addView(cardViews.root)
    }

// 处理 `requestRecognition` 相关逻辑。
    private fun requestRecognition() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecognition()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

// 处理 `beginRecognition` 相关逻辑。
    private fun beginRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            viewModel.onAsrError(getString(R.string.agent_asr_unavailable))
            return
        }
        recognizer?.destroy()
        viewModel.onRecordingStarted()
        recognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
// 处理 `onResults` 相关逻辑。
                override fun onResults(results: Bundle) {
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(viewModel::onAsrResult)
                }
// 处理 `onReadyForSpeech` 相关逻辑。
                override fun onReadyForSpeech(params: Bundle?) = viewModel.onRecordingStarted()
// 处理 `onBeginningOfSpeech` 相关逻辑。
                override fun onBeginningOfSpeech() = Unit
// 处理 `onRmsChanged` 相关逻辑。
                override fun onRmsChanged(rmsdB: Float) = Unit
// 处理 `onBufferReceived` 相关逻辑。
                override fun onBufferReceived(buffer: ByteArray?) = Unit
// 处理 `onEndOfSpeech` 相关逻辑。
                override fun onEndOfSpeech() = viewModel.onRecognizing()
// 处理 `onError` 相关逻辑。
                override fun onError(error: Int) = viewModel.onAsrError(getString(R.string.agent_asr_unavailable))
// 处理 `onPartialResults` 相关逻辑。
                override fun onPartialResults(partialResults: Bundle?) = Unit
// 处理 `onEvent` 相关逻辑。
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            })
        }
    }

// 处理 `onCancel` 相关逻辑。
    override fun onCancel(dialog: android.content.DialogInterface) { viewModel.closeAssistant(); super.onCancel(dialog) }
// 处理 `onResume` 相关逻辑。
    override fun onResume() {
        super.onResume()
        pendingPaymentOrderId?.let { orderId ->
            if (orderingViewModel.uiState.value.currentOrder?.id == orderId) openPayment(orderId)
            else {
                if (paymentLoadAttempts >= MAX_PAYMENT_LOAD_ATTEMPTS) paymentLoadAttempts = 0
                requestPaymentOrder(orderId)
            }
        }
    }
// 在视图销毁时释放视图相关资源。
    override fun onDestroyView() { recognizer?.destroy(); recognizer = null; binding = null; super.onDestroyView() }
// 处理 `dp` 相关逻辑。
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
// 处理 `halfSheetHeight` 相关逻辑。
    private fun halfSheetHeight() = (resources.displayMetrics.heightPixels * 0.58f).toInt()

// 打开支付。
    private fun openPayment(orderId: Long) {
        if (pendingPaymentOrderId != orderId || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val controller = Navigation.findNavController(requireActivity(), R.id.nav_host_fragment)
        if (controller.currentDestination?.id != R.id.paymentFragment) controller.navigate(R.id.paymentFragment)
        viewModel.consumeCreatedOrder()
        pendingPaymentOrderId = null
        viewModel.closeAssistant()
        dismissAllowingStateLoss()
    }

// 处理 `requestPaymentOrder` 相关逻辑。
    private fun requestPaymentOrder(orderId: Long) {
        if (pendingPaymentOrderId != orderId || paymentLoadInFlight || paymentLoadAttempts >= MAX_PAYMENT_LOAD_ATTEMPTS) return
        paymentLoadAttempts += 1
        paymentLoadInFlight = true
        orderingViewModel.selectOrder(orderId)
    }

// 处理 `userProgress` 相关逻辑。
    private fun userProgress(event: AgentEvent): String {
        val source = "${event.type} ${event.toolName.orEmpty()} ${event.summary}".lowercase()
        return when {
            "search" in source || "merchant" in source -> "🔍  正在寻找附近好店"
            "dish" in source || "menu" in source -> "🍜  正在查看菜品"
            "cart" in source || "order" in source || "validate" in source -> "📋  正在准备订单"
            else -> "✨  正在理解你的需求"
        }
    }

    companion object {
        private const val TAG = "agent_sheet"
        private const val MAX_PAYMENT_LOAD_ATTEMPTS = 3
// 处理 `show` 相关逻辑。
        fun show(manager: androidx.fragment.app.FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) AgentSheetFragment().show(manager, TAG)
        }
    }
}

/** 所有页面共用应用私有的会话指针存储，使 Activity 重建后仍能按认证身份恢复同一历史。 */
class AgentViewModelFactory(private val repository: AgentRepository, context: android.content.Context) : androidx.lifecycle.ViewModelProvider.Factory {
    private val pointerStore: AgentSessionPointerStore = AndroidAgentSessionPointerStore(context)
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        AgentViewModel(repository, androidx.lifecycle.SavedStateHandle(), pointerStore = pointerStore) as T

    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(
        modelClass: Class<T>,
        extras: androidx.lifecycle.viewmodel.CreationExtras
    ): T = AgentViewModel(repository, extras.createSavedStateHandle(), pointerStore = pointerStore) as T
}
