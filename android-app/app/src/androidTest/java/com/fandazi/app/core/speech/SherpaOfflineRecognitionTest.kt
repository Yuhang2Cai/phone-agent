// 离线真实模型设备测试入口：只接受人工准备的授权音频夹具，本轮按用户要求不运行。
package com.fandazi.app.core.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 授权评测音频须放目标应用cache/asr-evaluation；不是生产外部音频入口。
 * 设备断网及现场录音需另由人工执行，直灌夹具只证明解码/模型路径，不能替代麦克风验收。
 */
@RunWith(AndroidJUnit4::class)
class SherpaOfflineRecognitionTest {
    /** 使用固定音频和人工转写亲跑本地模型；缺参数为失败，不以跳过冒称通过。 */
    @Test fun authorizedLocalFixtureUsesRealOfflineModel() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val name = checkNotNull(arguments.getString("audioFile")) { "须提供冻结的授权评测文件名" }
        val expected = checkNotNull(arguments.getString("expectedTranscript")) { "须提供人工转写" }
        require(name == File(name).name && !name.contains("..")) { "评测文件名无效" }
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "asr-evaluation").canonicalFile
        val source = File(directory, name).canonicalFile
        require(source.parentFile == directory && source.isFile) { "授权音频未准备" }
        val policy = AsrInputPolicy(context.cacheDir)
        val recognizer = SherpaOnnxSpeechRecognizer(context, policy)
        try {
            // 仅测试装配签发夹具句柄，生产路径仍只由录音器签发；不修改生产入口接受范围。
            val file = policy.createRecordingFile()
            source.copyTo(file, overwrite = true)
            val audio = policy.register(file, 1000)
            val result = recognizer.transcribe(audio)
            assertTrue("真实模型识别失败", result.isSuccess)
            assertEquals(expected.trim(), result.getOrThrow().trim())
            assertFalse("消费后应清理录音缓存", file.exists())
        } finally { recognizer.close() }
    }
}
