// 语音输入依赖装配：仅在正式聊天页请求ViewModel时创建适配器，模型仍延迟到首次识别初始化。
package com.fandazi.app.feature.agent

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.fandazi.app.core.speech.AsrInputPolicy
import com.fandazi.app.core.speech.LocalAudioRecorder
import com.fandazi.app.core.speech.SherpaOnnxSpeechRecognizer

/** 使用Application Context与Fragment提供的SavedState，录音和识别共享同一输入策略，无Hilt依赖。 */
class AgentVoiceInputViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val application = context.applicationContext

    /** 只装配语音ViewModel；未知类型抛异常，不复用业务AgentViewModel或创建第二套网络仓库。 */
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        require(modelClass == AgentVoiceInputViewModel::class.java) { "不支持的语音状态类型" }
        val policy = AsrInputPolicy(application.cacheDir)
        @Suppress("UNCHECKED_CAST")
        return AgentVoiceInputViewModel(LocalAudioRecorder(application, policy),
            SherpaOnnxSpeechRecognizer(application, policy), extras.createSavedStateHandle()) as T
    }
}
