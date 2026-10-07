// AAC 本地录音适配器：迁移 redBook 的 MIC/MPEG4/AAC 配置，补充串行生命周期、30秒上限和取消清理。
package com.fandazi.app.core.speech

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 仅持有 Application Context；policy 与识别器共享，dispatcher 默认后台 IO。
 * 调用方负责运行时麦克风权限；未授权时 start 抛异常，不能退回系统 ASR 或上传。
 */
class LocalAudioRecorder(
    context: Context,
    private val policy: AsrInputPolicy,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : LocalAudioCapture {
    private val application = context.applicationContext
    private val lock = Mutex()
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var startedAt = 0L
    @Volatile private var limitReached = false

    /**
     * 串行建立缓存及录音器，沿用44.1kHz/96kbps AAC；硬件上限独立于界面计时。
     * 取消可能发生在 prepare/start 中，finally 会回收尚未交付成功的录音器和缓存。
     */
    override suspend fun start() = withContext(dispatcher) {
        lock.withLock {
            check(recorder == null) { "录音已经开始" }
            currentCoroutineContext().ensureActive()
            val file = policy.createRecordingFile()
            val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(application)
                else @Suppress("DEPRECATION") MediaRecorder()
            var handedOver = false
            try {
                limitReached = false
                created.setAudioSource(MediaRecorder.AudioSource.MIC)
                created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                created.setAudioEncodingBitRate(96_000)
                created.setAudioSamplingRate(44_100)
                created.setMaxDuration(AsrInputPolicy.MAX_RECORDING_MILLIS.toInt())
                created.setOnInfoListener { _, what, _ ->
                    // 系统达到上限会自动停止；标记用于兼容之后 stop 的设备状态异常。
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) limitReached = true
                }
                created.setOutputFile(file.absolutePath)
                created.prepare()
                currentCoroutineContext().ensureActive()
                created.start()
                currentCoroutineContext().ensureActive()
                recordingFile = file
                startedAt = SystemClock.elapsedRealtime()
                recorder = created
                handedOver = true
            } finally {
                if (!handedOver) {
                    runCatching { created.stop() }
                    runCatching { created.release() }
                    policy.discardUnregistered(file)
                }
            }
        }
    }

    /** 正常停止、释放设备后登记音频；普通停止失败不把损坏文件交给识别器。 */
    override suspend fun stop(): RecordedAudio = withContext(NonCancellable + dispatcher) {
        lock.withLock {
            val current = recorder ?: throw IOException("录音尚未开始")
            val file = recordingFile ?: throw IOException("录音缓存不存在")
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            try {
                val stopped = runCatching { current.stop() }
                // 仅系统明确触发30秒自动停止时允许已停止异常；其他异常一律拒绝。
                if (stopped.isFailure && !limitReached) throw IOException("录音未完成，请重新录音")
                policy.register(file, if (limitReached) AsrInputPolicy.MAX_RECORDING_MILLIS else elapsed)
            } catch (failure: Exception) {
                policy.discardUnregistered(file)
                throw failure
            } finally {
                runCatching { current.release() }
                recorder = null
                recordingFile = null
                startedAt = 0L
            }
        }
    }

    /** 取消即释放当前录音并删除未签发文件；非可取消清理防止页面离开后继续持有麦克风。 */
    override suspend fun cancel(): Unit = withContext(NonCancellable + dispatcher) {
        lock.withLock {
            val current = recorder
            try { runCatching { current?.stop() }; Unit } finally {
                runCatching { current?.release() }
                policy.discardUnregistered(recordingFile)
                recorder = null
                recordingFile = null
                startedAt = 0L
                limitReached = false
            }
        }
    }
}
