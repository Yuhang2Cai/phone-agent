// redBook 离线识别迁移：保留 Paraformer、解码及16k重采样配置；移除Hilt、远程下载和正文/路径日志。
package com.fandazi.app.core.speech

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.QnnConfig
import kotlinx.coroutines.CancellationException
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 只使用Application Context中的固定资产；policy必须与录音器共享，dispatcher默认后台IO。
 * 同一mutex覆盖初始化、解码、推理和关闭，不能从另一线程释放正在执行的JNI对象。
 * 取消不能强行中断底层decode，因此返回前再次检查协程有效性，由页面轮号拒绝迟到结果。
 */
class SherpaOnnxSpeechRecognizer(
    context: Context,
    private val policy: AsrInputPolicy,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : OfflineTranscriber {
    private val application = context.applicationContext
    private val lock = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var closed = false

    /**
     * 输入仅为录音器签发的本地缓存句柄；成功返回修剪后的文字，空结果或解码失败返回失败。
     * 路径拒绝、取消、异常及成功均不访问网络；合法音频在最终清理中删除，取消原样传播。
     */
    override suspend fun transcribe(audio: RecordedAudio): Result<String> {
        try {
            val file = policy.requireOwned(audio)
            return withContext(dispatcher) {
                lock.withLock {
                    check(!closed) { "本地识别器已关闭" }
                    currentCoroutineContext().ensureActive()
                    val decoded = decodeAudioFile(file)
                    // 少量编码底噪不能当作有效说话；过短和静音均拒绝，但不虚构其识别文本。
                    if (decoded.samples.size < TARGET_SAMPLE_RATE / 4 || decoded.samples.none { abs(it) > 0.0001f }) {
                        throw IOException("没有有效录音，请重新录音")
                    }
                    val engine = recognizer ?: OfflineRecognizer(application.assets, createRecognizerConfig()).also { recognizer = it }
                    currentCoroutineContext().ensureActive()
                    val stream = engine.createStream()
                    try {
                        stream.acceptWaveform(decoded.samples, decoded.sampleRate)
                        engine.decode(stream)
                        // JNI可能在取消后才结束；此检查防止已取消协程向调用方交付结果。
                        currentCoroutineContext().ensureActive()
                        val text = engine.getResult(stream).text?.trim().orEmpty()
                        if (text.isBlank()) throw IOException("没有识别到有效语音")
                        Result.success(text)
                    } finally { stream.release() }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { return Result.failure(failure) }
        // 缺失或不兼容JNI库属于本地识别不可用，交给页面提示文字输入，不升级成隐式联网兜底。
        catch (failure: LinkageError) { return Result.failure(failure) }
        finally {
            // 策略自身核对签发身份，拒绝输入不删外部文件；合法但已损坏的缓存也必须回收。
            withContext(NonCancellable + dispatcher) { policy.discard(audio) }
        }
    }

    /** 等活动推理释放锁后幂等关闭模型；JNI运行期间没有另一个线程销毁对象。 */
    override suspend fun close() = withContext(NonCancellable + dispatcher) {
        lock.withLock {
            closed = true
            recognizer?.release()
            recognizer = null
        }
    }

    /** 固定来源的双层资源目录与CPU两线程/80维/greedy配置；不替换模型、ABI或后端。 */
    private fun createRecognizerConfig(): OfflineRecognizerConfig {
        val base = "sherpa-onnx/sherpa-onnx-paraformer-zh-small-2024-03-09/" +
            "sherpa-onnx-paraformer-zh-small-2024-03-09"
        val model = OfflineModelConfig().apply {
            paraformer = OfflineParaformerModelConfig(model = "$base/model.int8.onnx", qnnConfig = QnnConfig())
            tokens = "$base/tokens.txt"
            numThreads = 2
            provider = "cpu"
            debug = false
        }
        return OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig(sampleRate = TARGET_SAMPLE_RATE, featureDim = 80, dither = 0f)
            modelConfig = model
            decodingMethod = "greedy_search"
            maxActivePaths = 4
            blankPenalty = 0f
        }
    }

    /**
     * 先选音轨，逐块送入MediaCodec，再把输出PCM按原来源逻辑合并为单声道。
     * 有界解码与协程检查防止坏文件无限等待；无论失败/取消都释放codec和extractor。
     */
    private suspend fun decodeAudioFile(file: File): DecodedAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val samples = ArrayList<Float>(64_000)
        var sampleRate = TARGET_SAMPLE_RATE
        var channelCount = 1
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IOException("录音没有音轨")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: throw IOException("录音格式缺失")
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            requireAudioFormat(sampleRate, channelCount)
            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val deadline = System.nanoTime() + DECODE_LIMIT_NANOS
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() > deadline) throw IOException("录音解码超时")
                // 压缩AAC送到解码器；音轨结束时只发送一次EOS，不继续空转取样。
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index) ?: throw IOException("录音解码输入不可用")
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                // 输出格式可能由设备解码器修正，PCM编码、采样率、声道以实际输出为准。
                when (val index = decoder.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = decoder.outputFormat
                        if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        if (output.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (output.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channelCount = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        requireAudioFormat(sampleRate, channelCount)
                    }
                    else -> if (index >= 0) {
                        try {
                            val buffer = decoder.getOutputBuffer(index)
                            if (buffer != null && info.size > 0) {
                                val bytes = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.get(bytes)
                                samples.addAll(decodePcmChunk(bytes, channelCount, pcmEncoding))
                                if (samples.size.toLong() > sampleRate.toLong() * MAX_DECODE_SECONDS) throw IOException("录音长度超出限制")
                            }
                        } finally { decoder.releaseOutputBuffer(index, false) }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    }
                }
            }
        } finally {
            // 先停止再释放；单个释放异常不阻断其他资源回收。
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
        currentCoroutineContext().ensureActive()
        val source = samples.toFloatArray()
        val result = if (sampleRate == TARGET_SAMPLE_RATE) source else resample(source, sampleRate, TARGET_SAMPLE_RATE)
        if (result.isEmpty()) throw IOException("录音为空")
        return DecodedAudio(result, TARGET_SAMPLE_RATE)
    }

    /** 拒绝无效采样率或声道数，不能以除零或无界分配方式处理异常解码输出。 */
    private fun requireAudioFormat(rate: Int, channels: Int) {
        if (rate !in 8_000..192_000 || channels !in 1..8) throw IOException("录音采样格式无效")
    }

    /** 小端Float或16位PCM按声道平均并裁剪到[-1,1]；未知编码不误按16位解释。 */
    private fun decodePcmChunk(bytes: ByteArray, channels: Int, encoding: Int): List<Float> {
        if (encoding !in listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) throw IOException("录音PCM格式不支持")
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val count = bytes.size / bytesPerSample / channels
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return buildList(count) {
            repeat(count) {
                var sum = 0f
                repeat(channels) { sum += if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.float else buffer.short / 32768f }
                val sample = sum / channels
                if (!sample.isFinite()) throw IOException("录音PCM数据无效")
                add(sample.coerceIn(-1f, 1f))
            }
        }
    }

    /** 按redBook原线性插值重采样为16kHz；分段检查取消，不改变数字/否定等文本内容。 */
    private suspend fun resample(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
        if (input.isEmpty() || sourceRate == targetRate) return input
        val length = max(1, (input.size.toLong() * targetRate / sourceRate).toInt())
        val output = FloatArray(length)
        val step = sourceRate.toDouble() / targetRate
        for (index in output.indices) {
            if (index % 4096 == 0) currentCoroutineContext().ensureActive()
            val position = index * step
            val first = position.toInt().coerceAtMost(input.lastIndex)
            val next = min(first + 1, input.lastIndex)
            output[index] = input[first] + (input[next] - input[first]) * (position - first).toFloat()
        }
        return output
    }

    /** 解码输出仅驻内存，单声道样本及采样率不保存到日志或SavedState。 */
    private data class DecodedAudio(val samples: FloatArray, val sampleRate: Int)

    companion object {
        /** 原模型固定16kHz输入，设备录音44.1kHz在解码后转换。 */
        private const val TARGET_SAMPLE_RATE = 16_000
        /** 解码器每次轮询10毫秒，允许协程在下一块取消。 */
        private const val TIMEOUT_US = 10_000L
        /** 30秒录音加编码尾帧的安全上界，防止异常文件无界积累PCM。 */
        private const val MAX_DECODE_SECONDS = 31L
        /** 解码最长等待60秒，仅防坏文件死循环，不能作为性能达标结论。 */
        private const val DECODE_LIMIT_NANOS = 60_000_000_000L
    }
}
