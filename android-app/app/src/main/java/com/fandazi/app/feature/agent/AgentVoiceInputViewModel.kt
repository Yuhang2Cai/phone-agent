// 聊天语音草稿状态机：独立于Agent业务ViewModel，管理录音、离线推理、取消与显式草稿合并。
package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fandazi.app.core.speech.AsrInputPolicy
import com.fandazi.app.core.speech.LocalAudioCapture
import com.fandazi.app.core.speech.OfflineTranscriber
import com.fandazi.app.core.speech.RecordedAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 语音阶段仅影响草稿输入；开始准备属于录音阶段，isPreparing 区分麦克风尚未就绪。 */
enum class VoicePhase { IDLE, RECORDING, RECOGNIZING, ERROR }
/** 非空草稿的显式合并选择，取消仅丢弃识别结果。 */
enum class DraftMergeChoice { APPEND, REPLACE, CANCEL }

/**
 * 输入栏不可变状态：draft 可编辑；seconds 为录音计时；round 作废迟到结果。
 * pendingText 仅在合并弹窗期间驻内存，error 只含安全中文文案，无音频路径和原始异常。
 */
data class VoiceInputState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val draft: String = "",
    val seconds: Int = 0,
    val round: Long = 0,
    val isPreparing: Boolean = false,
    val pendingText: String? = null,
    val error: String? = null,
    val notice: String? = null
)

/**
 * capture 与 transcriber 通过接口注入，dispatcher 可供定向行为测试控制调度。
 * SavedState 仅存所属账号标识和草稿；不存录音文件、识别结果、活动JNI或授权凭据。
 * 本类没有消息仓库，识别文本不可能隐式调用sendText或交易。
 */
class AgentVoiceInputViewModel(
    private val capture: LocalAudioCapture,
    private val transcriber: OfflineTranscriber,
    private val savedState: SavedStateHandle,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {
    private var owner: String? = savedState[OWNER_KEY]
    /** 登录凭据摘要仅驻内存用于作废活动轮，不写SavedState，也不保存原始令牌。 */
    private var sessionIdentity: String? = null
    private val draftsByOwner = mutableMapOf<String, String>()
    private val mutableState = MutableStateFlow(VoiceInputState(draft = savedState[DRAFT_KEY] ?: ""))
    /** 页面订阅的草稿状态；只有本类变更，页面不能注入识别终态。 */
    val state: StateFlow<VoiceInputState> = mutableState.asStateFlow()
    private val captureLock = Mutex()
    /** 录音设备所属轮号在同一锁内读写，旧清理任务不能停止新轮麦克风。 */
    private var recordingRound: Long? = null
    private var draftRevision = 0L
    private var operation: Job? = null
    private var timer: Job? = null
    private var round = 0L
    private var finishing = false

    /**
     * 绑定脱敏账号身份；改变身份先作废活动轮，再切换各自草稿，游客不显示私人草稿。
     * 同账号旋转不会清空草稿，旧账号草稿仅保留于该ViewModel内存，不写公共缓存。
     */
    fun bindAccount(accountKey: String?, sessionKey: String? = accountKey) {
        if (owner == accountKey && sessionIdentity == sessionKey) return
        owner?.let { draftsByOwner[it] = mutableState.value.draft }
        cancelInput()
        owner = accountKey
        sessionIdentity = sessionKey
        savedState[OWNER_KEY] = accountKey
        val draft = accountKey?.let { draftsByOwner[it] }.orEmpty()
        savedState[DRAFT_KEY] = draft
        mutableState.value = mutableState.value.copy(draft = draft, error = null, notice = null)
    }

    /** 手工编辑只更新草稿；识别中也可改字，识别返回时会与最新草稿显式合并。 */
    fun updateDraft(text: String) {
        if (owner == null || text == mutableState.value.draft) return
        ++draftRevision
        savedState[DRAFT_KEY] = text
        mutableState.value = mutableState.value.copy(draft = text, notice = null)
    }

    /**
     * 已登录且没有活动录音/识别/合并时启动；立即设置忙状态，双击不能排队启动两次。
     * 麦克风成功准备后开始30秒计时；启动异常保留草稿，只显示安全提示。
     */
    fun startRecording() {
        if (owner == null || finishing || operation?.isActive == true || mutableState.value.phase in listOf(VoicePhase.RECORDING, VoicePhase.RECOGNIZING) || mutableState.value.pendingText != null) return
        val thisRound = ++round
        val thisOwner = owner
        mutableState.value = mutableState.value.copy(phase = VoicePhase.RECORDING, seconds = 0, round = thisRound,
            isPreparing = true, pendingText = null, error = null, notice = null)
        operation = viewModelScope.launch(dispatcher) {
            try {
                captureLock.withLock {
                    if (!isCurrent(thisRound, thisOwner)) return@withLock
                    // 前轮取消清理可能仍在排队；启动新轮前先串行释放旧设备，不能重叠录音。
                    if (recordingRound != null) { capture.cancel(); recordingRound = null }
                    capture.start()
                    recordingRound = thisRound
                    if (!isCurrent(thisRound, thisOwner)) { capture.cancel(); recordingRound = null }
                }
                if (!isCurrent(thisRound, thisOwner)) return@launch
                mutableState.value = mutableState.value.copy(isPreparing = false)
                timer = viewModelScope.launch(dispatcher) {
                    for (second in 1..(AsrInputPolicy.MAX_RECORDING_MILLIS / 1000).toInt()) {
                        delay(1000)
                        if (!isCurrent(thisRound, thisOwner) || mutableState.value.phase != VoicePhase.RECORDING) return@launch
                        mutableState.value = mutableState.value.copy(seconds = second)
                    }
                    finishRecording()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failIfCurrent(thisRound, thisOwner, "录音无法开始，请检查麦克风权限或继续文字输入") }
        }
    }

    /** 结束录音后只进行本地识别；停止期间禁止重复操作，音频交由识别器消费和清理。 */
    fun finishRecording() {
        if (finishing || mutableState.value.phase != VoicePhase.RECORDING || mutableState.value.isPreparing) return
        finishing = true
        timer?.cancel(); timer = null
        val thisRound = round
        val thisOwner = owner
        val recognizedDraftRevision = draftRevision
        mutableState.value = mutableState.value.copy(phase = VoicePhase.RECOGNIZING, error = null)
        operation = viewModelScope.launch(dispatcher) {
            var audio: RecordedAudio? = null
            try {
                // 同一调度上下文只切换为不可取消Job，确保stop签发的句柄不会在返回时因取消丢失。
                withContext(NonCancellable) {
                    captureLock.withLock {
                        check(recordingRound == thisRound) { "本轮录音已取消" }
                        try { audio = capture.stop() } finally { recordingRound = null }
                    }
                }
                // 即使取消发生在不可取消的stop中，也消费文件以执行识别器finally清理；结果仍按轮号拒绝。
                val result = transcriber.transcribe(checkNotNull(audio))
                if (!isCurrent(thisRound, thisOwner)) return@launch
                result.fold(onSuccess = { value ->
                    val text = value.trim()
                    if (text.isBlank()) failIfCurrent(thisRound, thisOwner, "没有识别到有效语音，请重新录音")
                    else applyRecognition(text, recognizedDraftRevision)
                }, onFailure = { failIfCurrent(thisRound, thisOwner, "语音识别失败，请重新录音或继续文字输入") })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failIfCurrent(thisRound, thisOwner, "录音过短或未完成，请重新录音") }
            finally {
                // 仅当前轮能重置忙标志；旧JNI迟到结束不能干扰已经开始的新一轮。
                if (thisRound == round) finishing = false
                if (audio == null) withContext(NonCancellable) {
                    captureLock.withLock { if (recordingRound == thisRound) { capture.cancel(); recordingRound = null } }
                }
            }
        }
    }

    /**
     * 页面离开、后台、旋转和账号切换均调用：先增加轮号拒绝迟到结果，再取消协程及录音。
     * 不跨线程释放正在推理的JNI对象；识别器自己在计算安全退出后回收流和音频。
     */
    fun cancelInput() {
        val invalidatedRound = round
        ++round
        timer?.cancel(); timer = null
        operation?.cancel(); operation = null
        finishing = false
        mutableState.value = mutableState.value.copy(phase = VoicePhase.IDLE, round = round, seconds = 0,
            isPreparing = false, pendingText = null, error = null, notice = null)
        viewModelScope.launch(dispatcher) { withContext(NonCancellable) {
            captureLock.withLock {
                // 只清理被此次取消覆盖的轮，旧后台清理迟到不能停止新轮录音。
                if (recordingRound?.let { it <= invalidatedRound } == true) { capture.cancel(); recordingRound = null }
            }
        } }
    }

    /** 拒权和永久拒权只变为文字可用的错误提示，不启动录音、系统识别或请求。 */
    fun showPermissionError() {
        mutableState.value = mutableState.value.copy(phase = VoicePhase.ERROR, error = "麦克风权限未开启，可以继续文字输入")
    }

    /** 用户显式选择追加/替换/取消后结束合并；取消不修改草稿。 */
    fun mergeRecognition(choice: DraftMergeChoice) {
        val text = mutableState.value.pendingText ?: return
        when (choice) {
            DraftMergeChoice.APPEND -> updateDraft(listOf(mutableState.value.draft, text).filter(String::isNotBlank).joinToString("\n"))
            DraftMergeChoice.REPLACE -> updateDraft(text)
            DraftMergeChoice.CANCEL -> Unit
        }
        mutableState.value = mutableState.value.copy(pendingText = null, notice = if (choice == DraftMergeChoice.CANCEL) null else "语音已转为文字，请确认后发送")
    }

    /** 当前草稿为空才直接回填，否则保存待用户选择的结果，期间不触发消息发送。 */
    private fun applyRecognition(text: String, startedRevision: Long) {
        // 用户在识别中清空草稿也属于编辑，不能因为此刻为空就用迟到结果覆盖其选择。
        if (mutableState.value.draft.isBlank() && draftRevision == startedRevision) {
            updateDraft(text)
            mutableState.value = mutableState.value.copy(phase = VoicePhase.IDLE, notice = "语音已转为文字，请确认后发送")
        } else mutableState.value = mutableState.value.copy(phase = VoicePhase.IDLE, pendingText = text)
    }

    /** 同时核对轮号与账号；UI取消和账号改变都使旧轮失效。 */
    private fun isCurrent(value: Long, account: String?): Boolean = value == round && account != null && account == owner

    /** 错误不清除已有草稿，也不公开原始异常或私人音频路径。 */
    private fun failIfCurrent(value: Long, account: String?, message: String) {
        if (isCurrent(value, account)) mutableState.value = mutableState.value.copy(phase = VoicePhase.ERROR,
            isPreparing = false, pendingText = null, error = message)
    }

    /** ViewModel回收后用独立清理作用域等待录音和JNI锁；模型关闭安全结束，不依赖已取消的viewModelScope。 */
    override fun onCleared() {
        ++round
        timer?.cancel(); operation?.cancel()
        val cleanupJob = SupervisorJob()
        CoroutineScope(cleanupJob + Dispatchers.IO).launch {
            try { captureLock.withLock { capture.cancel() } } finally {
                try { transcriber.close() } finally { cleanupJob.complete() }
            }
        }
        super.onCleared()
    }

    companion object {
        /** 仅保存当前账号归属标识与文字草稿，恢复不隐式重启麦克风或重发请求。 */
        private const val OWNER_KEY = "voice_input_owner"
        private const val DRAFT_KEY = "voice_input_draft"
    }
}
