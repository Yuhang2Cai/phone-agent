// 语音状态机行为测试：验证草稿、取消、账号和时长边界；本轮只建立测试，按用户要求不执行。
package com.fandazi.app.feature.agent

import androidx.lifecycle.SavedStateHandle
import com.fandazi.app.core.speech.LocalAudioCapture
import com.fandazi.app.core.speech.OfflineTranscriber
import com.fandazi.app.core.speech.RecordedAudio
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** 用可控录音和迟到推理验证状态边界；不伪称真实麦克风或模型通过。 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentVoiceInputViewModelTest {
    /** 空草稿识别后仅回填，可再编辑；状态机没有发送或交易仓库依赖。 */
    @Test fun recognitionFillsDraftWithoutSending() = runTest {
        val capture = FakeCapture()
        val recognizer = FakeTranscriber()
        val vm = AgentVoiceInputViewModel(capture, recognizer, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        vm.bindAccount("account-a")
        vm.startRecording(); runCurrent(); vm.finishRecording(); runCurrent()
        recognizer.result.complete(Result.success("两个人，人均80元")); runCurrent()
        assertEquals("两个人，人均80元", vm.state.value.draft)
        assertEquals(VoicePhase.IDLE, vm.state.value.phase)
        assertNull(vm.state.value.pendingText)
        vm.updateDraft("两个人，不要太辣")
        assertEquals("两个人，不要太辣", vm.state.value.draft)
        vm.cancelInput(); runCurrent()
    }

    /** 非空草稿不自动替换；追加/替换/取消必须由用户选择，取消保留原文。 */
    @Test fun existingDraftRequiresExplicitMergeChoice() = runTest {
        val recognizer = FakeTranscriber()
        val vm = AgentVoiceInputViewModel(FakeCapture(), recognizer, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        vm.bindAccount("account-a"); vm.updateDraft("晚餐")
        vm.startRecording(); runCurrent(); vm.finishRecording(); runCurrent()
        recognizer.result.complete(Result.success("不要太辣")); runCurrent()
        assertEquals("晚餐", vm.state.value.draft)
        assertEquals("不要太辣", vm.state.value.pendingText)
        vm.mergeRecognition(DraftMergeChoice.CANCEL)
        assertEquals("晚餐", vm.state.value.draft)
        assertNull(vm.state.value.pendingText)
        vm.cancelInput(); runCurrent()
    }

    /** JNI不可中断时也不得把取消后的迟到文本写回；此测试主动模拟不可取消的推理。 */
    @Test fun lateNativeResultAfterCancelIsDiscarded() = runTest {
        val recognizer = FakeTranscriber()
        val vm = AgentVoiceInputViewModel(FakeCapture(), recognizer, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        vm.bindAccount("account-a"); vm.updateDraft("原草稿")
        vm.startRecording(); runCurrent(); vm.finishRecording(); runCurrent()
        vm.cancelInput(); runCurrent()
        recognizer.result.complete(Result.success("迟到内容")); runCurrent()
        assertEquals("原草稿", vm.state.value.draft)
        assertNull(vm.state.value.pendingText)
        assertEquals(VoicePhase.IDLE, vm.state.value.phase)
    }

    /** 更换账号清除当前显示的旧草稿，旧推理不能覆盖新账号内容，返回原账号仍有自己的草稿。 */
    @Test fun accountChangeInvalidatesOldRoundAndSeparatesDrafts() = runTest {
        val recognizer = FakeTranscriber()
        val vm = AgentVoiceInputViewModel(FakeCapture(), recognizer, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        vm.bindAccount("account-a"); vm.updateDraft("A草稿")
        vm.startRecording(); runCurrent(); vm.finishRecording(); runCurrent()
        vm.bindAccount("account-b"); vm.updateDraft("B草稿"); runCurrent()
        recognizer.result.complete(Result.success("A的迟到推理")); runCurrent()
        assertEquals("B草稿", vm.state.value.draft)
        vm.bindAccount("account-a"); runCurrent()
        assertEquals("A草稿", vm.state.value.draft)
    }

    /** 双击不启动第二录音，最长30秒后只停止一次；此处检查状态机而非实测录音时长。 */
    @Test fun repeatedStartDoesNotOverlapAndLimitFinishesOnce() = runTest {
        val capture = FakeCapture(); val recognizer = FakeTranscriber()
        val vm = AgentVoiceInputViewModel(capture, recognizer, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        vm.bindAccount("account-a"); vm.startRecording(); vm.startRecording(); runCurrent()
        assertEquals(1, capture.starts)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, capture.stops)
        assertEquals(VoicePhase.RECOGNIZING, vm.state.value.phase)
        recognizer.result.complete(Result.success("识别结果")); runCurrent()
        vm.cancelInput(); runCurrent()
    }

    /** 识别期间用户编辑草稿不能被静默覆盖，识别成功后仍须合并选择。 */
    @Test fun editsDuringRecognitionAreRetained() = runTest {
        val recognizer = FakeTranscriber()
        val vm = AgentVoiceInputViewModel(FakeCapture(), recognizer, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        vm.bindAccount("account-a"); vm.startRecording(); runCurrent(); vm.finishRecording(); runCurrent()
        vm.updateDraft("手工新输入")
        recognizer.result.complete(Result.success("语音内容")); runCurrent()
        assertEquals("手工新输入", vm.state.value.draft)
        assertEquals("语音内容", vm.state.value.pendingText)
        vm.mergeRecognition(DraftMergeChoice.APPEND)
        assertEquals("手工新输入\n语音内容", vm.state.value.draft)
    }

    /** 显式替换才覆盖旧草稿，不能把识别成功本身当作用户已同意替换。 */
    @Test fun replacingDraftRequiresUserChoice() = runTest {
        val recognizer = FakeTranscriber()
        val vm = AgentVoiceInputViewModel(FakeCapture(), recognizer, SavedStateHandle(), StandardTestDispatcher(testScheduler))
        vm.bindAccount("account-a"); vm.updateDraft("旧文字")
        vm.startRecording(); runCurrent(); vm.finishRecording(); runCurrent()
        recognizer.result.complete(Result.success("新文字")); runCurrent()
        assertEquals("旧文字", vm.state.value.draft)
        vm.mergeRecognition(DraftMergeChoice.REPLACE)
        assertEquals("新文字", vm.state.value.draft)
        assertNull(vm.state.value.pendingText)
    }

    /** 同一账号重新登录也须作废旧轮，但其文字草稿保持；凭据摘要不进入SavedState。 */
    @Test fun changingCredentialInvalidatesRoundButKeepsSameAccountDraft() = runTest {
        val recognizer = FakeTranscriber(); val saved = SavedStateHandle()
        val vm = AgentVoiceInputViewModel(FakeCapture(), recognizer, saved, StandardTestDispatcher(testScheduler))
        vm.bindAccount("account-a", "credential-1"); vm.updateDraft("原文字")
        vm.startRecording(); runCurrent(); vm.finishRecording(); runCurrent()
        vm.bindAccount("account-a", "credential-2"); runCurrent()
        recognizer.result.complete(Result.success("旧登录迟到文字")); runCurrent()
        assertEquals("原文字", vm.state.value.draft)
        assertNull(vm.state.value.pendingText)
        assertEquals(setOf("voice_input_owner", "voice_input_draft"), saved.keys())
        assertEquals("account-a", saved.get<String>("voice_input_owner"))
        assertEquals("原文字", saved.get<String>("voice_input_draft"))
    }

    /** 模拟录音计数；不会调用系统麦克风，不作为设备链路证据。 */
    private class FakeCapture : LocalAudioCapture {
        var starts = 0; var stops = 0
        override suspend fun start() { starts++ }
        override suspend fun stop(): RecordedAudio { stops++; return RecordedAudio(File("unused.m4a"), 1000, "fake") }
        override suspend fun cancel() = Unit
    }

    /** 模拟不可取消的JNI迟到结果，防止仅凭协程取消就假设底层计算已中断。 */
    private class FakeTranscriber : OfflineTranscriber {
        val result = CompletableDeferred<Result<String>>()
        override suspend fun transcribe(audio: RecordedAudio): Result<String> = withContext(NonCancellable) { result.await() }
        override suspend fun close() = Unit
    }
}
