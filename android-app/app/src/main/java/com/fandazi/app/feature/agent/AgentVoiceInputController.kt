// 正式聊天语音输入绑定：只处理权限、录音状态和文字草稿UI，业务消息仍交给既有AgentViewModel。
package com.fandazi.app.feature.agent

import android.Manifest
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import android.widget.ScrollView
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fandazi.app.R
import com.fandazi.app.feature.auth.SessionRepository
import com.fandazi.app.feature.auth.SessionState
import java.security.MessageDigest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * fragment/root 为当前聊天View生命周期；model只处理语音草稿，session是唯一账号来源。
 * requestPermission使用Fragment预先注册的launcher；发送仍通过sendText委托原业务层。
 * 不在此层上传音频、恢复交易确认或创建第二个会话，close必须在onDestroyView调用。
 */
class AgentVoiceInputController(
    private val fragment: Fragment,
    private val root: View,
    private val model: AgentVoiceInputViewModel,
    private val session: SessionRepository,
    private val requestPermission: (String) -> Unit,
    private val isSending: () -> Boolean,
    private val sendText: (String) -> Unit
) : DefaultLifecycleObserver {
    private val input = root.findViewById<EditText>(R.id.agent_chat_input)
    private val send = root.findViewById<TextView>(R.id.agent_chat_send)
    private val mic = root.findViewById<View>(R.id.agent_chat_voice)
    private val panel = root.findViewById<View>(R.id.agent_voice_panel)
    private val title = root.findViewById<TextView>(R.id.agent_voice_title)
    private val message = root.findViewById<TextView>(R.id.agent_voice_message)
    private val cancel = root.findViewById<TextView>(R.id.agent_voice_cancel)
    private val finish = root.findViewById<TextView>(R.id.agent_voice_finish)
    private val notice = root.findViewById<TextView>(R.id.agent_voice_notice)
    private val permissionPrefs = fragment.requireContext().getSharedPreferences("voice_input_permission", Context.MODE_PRIVATE)
    private var synchronizing = false
    private var closed = false
    private var permissionAttempt: String? = null
    private var boundAccount: String? = null
    private var boundCredential: String? = null
    private var dialog: Dialog? = null
    private var dialogKind: String? = null
    private val owner = fragment.viewLifecycleOwner
    private val collector: Job

    /** TextWatcher只同步用户编辑，程序回填通过synchronizing避免递归清除成功提示。 */
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun afterTextChanged(s: Editable?) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            if (!synchronizing) model.updateDraft(s?.toString().orEmpty())
            refreshComposer()
        }
    }

    init {
        bindIdentity(session.state.value)
        input.addTextChangedListener(watcher)
        mic.setOnClickListener { beginWithPermission() }
        cancel.setOnClickListener { model.cancelInput() }
        finish.setOnClickListener {
            if (model.state.value.phase == VoicePhase.RECORDING) model.finishRecording() else beginWithPermission()
        }
        send.setOnClickListener {
            if (canSendQuickQuery() && model.state.value.draft.isNotBlank()) {
                // 只有用户点击发送按钮才调用原业务层；识别或弹窗按钮永远不走该委托。
                sendText(model.state.value.draft)
                model.updateDraft("")
            }
        }
        owner.lifecycle.addObserver(this)
        collector = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.state.collect { render(it) } }
                launch { session.state.collect { bindIdentity(it); refreshComposer() } }
            }
        }
        render(model.state.value)
    }

    /** 普通消息/快捷输入的明确发送资格；活动语音或待合并时不可发出尚未确认的草稿。 */
    fun canSendQuickQuery(): Boolean = !closed && session.state.value is SessionState.Authenticated && !isSending() &&
        model.state.value.phase !in listOf(VoicePhase.RECORDING, VoicePhase.RECOGNIZING) && model.state.value.pendingText == null

    /** 业务发送状态变化时刷新外部输入栏；不重建聊天消息、不修改业务卡片授权。 */
    fun refreshComposer() {
        if (closed) return
        val authenticated = session.state.value is SessionState.Authenticated
        val state = model.state.value
        val active = state.phase in listOf(VoicePhase.RECORDING, VoicePhase.RECOGNIZING)
        input.isEnabled = authenticated && !active
        mic.isEnabled = authenticated && !active && state.pendingText == null
        send.isEnabled = canSendQuickQuery() && state.draft.isNotBlank()
        mic.alpha = if (mic.isEnabled) 1f else 0.45f
        input.alpha = if (input.isEnabled) 1f else 0.45f
        send.alpha = if (active || !authenticated) 0.45f else 1f
    }

    /**
     * 系统权限回调只对同次登录且当前页仍可见时启动；页面关闭/换号后的回调只更新系统权限本身。
     * 拒绝后仍保留可编辑文字，永久拒绝提供显式设置入口。
     */
    fun onPermissionResult(granted: Boolean) {
        val expected = permissionAttempt
        permissionAttempt = null
        if (closed || expected == null || expected != credentialKey() || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        if (granted) model.startRecording() else {
            model.showPermissionError()
            if (!fragment.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) showSettingsDialog()
        }
    }

    /** 每个可见状态映射到确认的UI-14A–I；只读取本地状态，异常正文/私人路径不进入UI。 */
    private fun render(state: VoiceInputState) {
        if (closed) return
        if (input.text.toString() != state.draft) {
            synchronizing = true
            try { input.setText(state.draft); input.setSelection(input.text.length) } finally { synchronizing = false }
        }
        panel.isVisible = state.phase != VoicePhase.IDLE
        notice.isVisible = state.notice != null
        notice.text = state.notice.orEmpty()
        title.setTextColor(ContextCompat.getColor(root.context, if (state.phase == VoicePhase.RECORDING) R.color.group_buy_orange else R.color.group_buy_green))
        cancel.text = when (state.phase) {
            VoicePhase.RECORDING -> text(R.string.agent_voice_cancel_recording)
            VoicePhase.RECOGNIZING -> text(R.string.agent_voice_cancel_recognition)
            else -> text(R.string.agent_voice_continue_text)
        }
        finish.isVisible = state.phase in listOf(VoicePhase.RECORDING, VoicePhase.ERROR)
        finish.isEnabled = !state.isPreparing
        finish.text = text(if (state.phase == VoicePhase.RECORDING) R.string.agent_voice_finish_recording else R.string.agent_voice_retry)
        when (state.phase) {
            VoicePhase.RECORDING -> {
                title.text = if (state.isPreparing) text(R.string.agent_voice_prepare) else text(R.string.agent_voice_recording, state.seconds)
                message.text = text(R.string.agent_voice_recording_hint)
            }
            VoicePhase.RECOGNIZING -> { title.text = text(R.string.agent_voice_recognizing); message.text = text(R.string.agent_voice_recognizing_hint) }
            VoicePhase.ERROR -> { title.text = text(R.string.agent_voice_no_result); message.text = state.error.orEmpty() }
            VoicePhase.IDLE -> Unit
        }
        refreshComposer()
        if (state.pendingText != null && dialogKind != "merge") showMergeDialog(state)
        else if (state.pendingText == null && dialogKind == "merge") dismissDialog()
    }

    /** 申请权限前明确说明本机转写；已有永久拒绝只提供设置和文字选择，不反复弹系统框。 */
    private fun beginWithPermission() {
        if (closed || session.state.value !is SessionState.Authenticated || !mic.isEnabled) return
        // 切入语音时收起文字键盘，确保结束/取消控制可见；不删除原文字草稿。
        (root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(input.windowToken, 0)
        if (ContextCompat.checkSelfPermission(root.context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            model.startRecording()
            return
        }
        if (permissionPrefs.getBoolean("asked", false) && !fragment.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            model.showPermissionError(); showSettingsDialog(); return
        }
        showDialog("permission", text(R.string.agent_voice_permission_title), text(R.string.agent_voice_permission_body),
            listOf(text(R.string.agent_voice_request_permission) to {
                permissionAttempt = credentialKey()
                permissionPrefs.edit().putBoolean("asked", true).apply()
                if (permissionAttempt != null) requestPermission(Manifest.permission.RECORD_AUDIO)
            }, text(R.string.agent_voice_continue_text) to { model.cancelInput() }))
    }

    /** 系统设置只在用户点击时打开；返回后不自动录音，须再次点击麦克风。 */
    private fun showSettingsDialog() {
        showDialog("settings", text(R.string.agent_voice_permission_denied), text(R.string.agent_voice_settings_body),
            listOf(text(R.string.agent_voice_open_settings) to {
                fragment.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${root.context.packageName}")))
            }, text(R.string.agent_voice_continue_text) to { model.cancelInput() }))
    }

    /** 非空草稿及识别文本只显示于本次弹窗；旧轮弹窗即使迟到点击也不能修改当前草稿。 */
    private fun showMergeDialog(state: VoiceInputState) {
        val expectedRound = state.round
        fun merge(choice: DraftMergeChoice) { if (model.state.value.round == expectedRound) model.mergeRecognition(choice) }
        showDialog("merge", text(R.string.agent_voice_merge_title), text(R.string.agent_voice_merge_body, state.draft, state.pendingText.orEmpty()),
            listOf(text(R.string.agent_voice_append) to { merge(DraftMergeChoice.APPEND) }, text(R.string.agent_voice_replace) to { merge(DraftMergeChoice.REPLACE) },
                text(R.string.agent_voice_cancel) to { merge(DraftMergeChoice.CANCEL) }), onCancel = { merge(DraftMergeChoice.CANCEL) })
    }

    /** 按Figma复用白色圆角弹窗结构，三个动作分开绑定；对话关闭本身不执行业务。 */
    private fun showDialog(kind: String, heading: String, body: String, actions: List<Pair<String, () -> Unit>>, onCancel: () -> Unit = {}) {
        if (closed || !owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        dismissDialog()
        val created = Dialog(root.context)
        created.requestWindowFeature(Window.FEATURE_NO_TITLE)
        created.setContentView(R.layout.dialog_agent_voice_input)
        created.findViewById<TextView>(R.id.agent_voice_dialog_title).text = heading
        val bodyView = created.findViewById<TextView>(R.id.agent_voice_dialog_body)
        bodyView.text = body
        val density = root.resources.displayMetrics.density
        val width = minOf((353 * density).toInt(), root.resources.displayMetrics.widthPixels - (40 * density).toInt())
        // 测量完整正文后只限制滚动视口，不限制TextView自身高度，长草稿仍可完整查看。
        bodyView.measure(View.MeasureSpec.makeMeasureSpec(width - (32 * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        created.findViewById<ScrollView>(R.id.agent_voice_dialog_scroll).layoutParams = created.findViewById<ScrollView>(R.id.agent_voice_dialog_scroll).layoutParams.apply {
            height = minOf(bodyView.measuredHeight, (220 * density).toInt())
        }
        listOf(R.id.agent_voice_dialog_primary, R.id.agent_voice_dialog_secondary, R.id.agent_voice_dialog_cancel).forEachIndexed { index, id ->
            val button = created.findViewById<TextView>(id)
            button.isVisible = index < actions.size
            actions.getOrNull(index)?.let { (label, action) ->
                button.text = label
                button.setBackgroundResource(if (label in listOf("取消", "继续文字输入")) R.drawable.agent_voice_secondary_background else R.drawable.agent_voice_primary_background)
                button.setOnClickListener { dismissDialog(); if (!closed && session.state.value is SessionState.Authenticated) action() }
            }
        }
        created.setOnCancelListener { onCancel() }
        created.setOnDismissListener { if (dialog === created) { dialog = null; dialogKind = null } }
        dialog = created; dialogKind = kind
        created.show()
        created.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.28f }
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    /** 关闭本页弹窗，不把程序dismiss当作用户合并选择；取消状态由ViewModel统一处理。 */
    private fun dismissDialog() { dialog?.dismiss(); dialog = null; dialogKind = null }

    /** 账号及凭据变化先取消活动轮/弹窗；摘要仅用于内部身份匹配，不保存原令牌。 */
    private fun bindIdentity(state: SessionState) {
        val authenticated = state as? SessionState.Authenticated
        val account = authenticated?.let { hash(it.user.username + "\u0000" + it.user.email) }
        val credential = authenticated?.let { hash(it.token) }
        if (boundAccount != account || boundCredential != credential) {
            permissionAttempt = null
            dismissDialog()
        }
        boundAccount = account
        boundCredential = credential
        model.bindAccount(account, credential)
        if (authenticated == null || permissionAttempt?.let { it != credential } == true) {
            permissionAttempt = null
            dismissDialog()
        }
    }

    /** 返回当前登录凭据摘要，仅作权限回调的代次核对，不作授权凭证或请求参数。 */
    private fun credentialKey(): String? = (session.state.value as? SessionState.Authenticated)?.let { hash(it.token) }

    /** 摘要不记录到日志、SavedState或网络；业务登录态仍以SessionRepository为准。 */
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** 中文文案集中于ASR资源，格式化正文仅显示在当前界面，不记录或发送。 */
    private fun text(@StringRes id: Int, vararg arguments: Any): String = root.context.getString(id, *arguments)

    /** 转后台/离页/旋转即取消录音与本轮回填，保留草稿；返回不自动重启。 */
    override fun onStop(owner: LifecycleOwner) { permissionAttempt = null; dismissDialog(); model.cancelInput() }

    /** View销毁清理监听与弹窗；ViewModel仍可保存同账号草稿，JNI在自身安全退出后释放。 */
    fun close() {
        if (closed) return
        closed = true
        permissionAttempt = null
        dismissDialog()
        model.cancelInput()
        collector.cancel()
        owner.lifecycle.removeObserver(this)
        input.removeTextChangedListener(watcher)
        mic.setOnClickListener(null); send.setOnClickListener(null)
        cancel.setOnClickListener(null); finish.setOnClickListener(null)
    }
}
