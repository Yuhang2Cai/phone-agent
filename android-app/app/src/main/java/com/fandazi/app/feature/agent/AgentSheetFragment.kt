package com.fandazi.app.feature.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentAgentSheetBinding
import com.fandazi.app.databinding.ItemAgentEventBinding
import com.fandazi.app.databinding.ItemOrderConfirmationBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class AgentSheetFragment : BottomSheetDialogFragment(R.layout.fragment_agent_sheet) {
    private var binding: FragmentAgentSheetBinding? = null
    private var recognizer: SpeechRecognizer? = null
    private val viewModel: AgentViewModel by activityViewModels {
        AgentViewModelFactory((requireActivity().application as FandaziApplication).agentRepository)
    }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginRecognition()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog =
        BottomSheetDialog(requireContext(), theme).also { dialog ->
            dialog.setOnShowListener {
                dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
                    BottomSheetBehavior.from(sheet).apply {
                        peekHeight = (resources.displayMetrics.heightPixels * 0.58f).toInt()
                        state = BottomSheetBehavior.STATE_COLLAPSED
                    }
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val views = FragmentAgentSheetBinding.bind(view)
        binding = views
        views.closeAgentButton.setOnClickListener { viewModel.closeAssistant() }
        views.expandAgentButton.setOnClickListener { viewModel.toggleFullScreen() }
        views.agentSendButton.setOnClickListener { viewModel.sendComposer() }
        views.agentMicButton.setOnClickListener { requestRecognition() }
        views.agentComposer.doAfterTextChanged { viewModel.updateComposer(it?.toString().orEmpty()) }
        views.quickLightMeal.setOnClickListener { viewModel.sendText(getString(R.string.quick_light_meal)) }
        views.quickRepeatOrder.setOnClickListener { viewModel.sendText(getString(R.string.quick_repeat_order)) }
        views.quickTrackOrder.setOnClickListener { viewModel.sendText(getString(R.string.quick_track_order)) }
        views.agentRetryButton.setOnClickListener { viewModel.retry() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    private fun render(state: AgentUiState) {
        val views = binding ?: return
        if (state.sheetMode == AgentSheetMode.HIDDEN) {
            dismissAllowingStateLoss()
            return
        }
        views.expandAgentButton.setText(if (state.sheetMode == AgentSheetMode.FULL) R.string.agent_half_screen else R.string.agent_full_screen)
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
                val params = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                params.topMargin = dp(8)
                layoutParams = params
                views.agentMessages.addView(this)
            }
        }
        views.agentEvents.removeAllViews()
        state.events.forEach { event ->
            val eventViews = ItemAgentEventBinding.inflate(layoutInflater, views.agentEvents, false)
            eventViews.agentEventSummary.text = event.summary
            eventViews.agentEventMeta.text = listOfNotNull(event.toolName, event.status).joinToString(" · ")
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
            null -> ""
        }
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { bottomSheet ->
            BottomSheetBehavior.from(bottomSheet).state = if (state.sheetMode == AgentSheetMode.FULL) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_COLLAPSED
        }
    }

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
        cardViews.confirmMode.text = if (card.authorizationMode == AgentMode.THIS_TASK_AUTO) getString(R.string.agent_this_task_auto) else getString(R.string.agent_confirm_mode)
        cardViews.confirmLimit.text = getString(R.string.agent_limit_value, card.amountLimitText)
        cardViews.confirmAutoAuthorization.isChecked = card.authorizationMode == AgentMode.THIS_TASK_AUTO
        cardViews.confirmAutoAuthorization.setOnClickListener { viewModel.selectThisTaskAuto() }
        cardViews.confirmOrderButton.setOnClickListener { viewModel.confirmOrder() }
        cardViews.cancelOrderButton.setOnClickListener { viewModel.cancelOrder() }
        cardViews.modifyOrderButton.setOnClickListener { viewModel.modifyOrder() }
        views.agentConfirmationContainer.addView(cardViews.root)
    }

    private fun requestRecognition() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) beginRecognition()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun beginRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(requireContext())) {
            viewModel.onAsrResult(getString(R.string.agent_asr_unavailable))
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(requireContext()).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(viewModel::onAsrResult)
                }
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onError(error: Int) = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            speech.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            })
        }
    }

    override fun onCancel(dialog: android.content.DialogInterface) { viewModel.closeAssistant(); super.onCancel(dialog) }
    override fun onDestroyView() { recognizer?.destroy(); recognizer = null; binding = null; super.onDestroyView() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "agent_sheet"
        fun show(manager: androidx.fragment.app.FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) AgentSheetFragment().show(manager, TAG)
        }
    }
}

class AgentViewModelFactory(private val repository: AgentRepository) : androidx.lifecycle.ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
        AgentViewModel(repository, androidx.lifecycle.SavedStateHandle()) as T
}
