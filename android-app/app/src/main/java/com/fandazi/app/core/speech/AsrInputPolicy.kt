// 本地语音输入边界：录音器签发缓存句柄，识别器只消费已登记文件；不解析或下载外部来源。
package com.fandazi.app.core.speech

import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 一次本应用录音的句柄；file 为缓存文件，durationMillis 为单调时钟时长。
 * 构造器仅在模块内可见，ownershipId 还须经策略登记，手工构造不能绕过来源检查。
 */
data class RecordedAudio internal constructor(
    val file: File,
    val durationMillis: Long,
    internal val ownershipId: String
)

/** 录音生命周期接口；权限由页面申请，失败抛异常，取消须释放设备并删除本轮缓存。 */
interface LocalAudioCapture {
    /** 开始本地录音；已有录音时拒绝重复启动，成功后才启动页面计时。 */
    suspend fun start()
    /** 结束录音并返回已登记句柄；无法形成有效音频时清理并抛异常。 */
    suspend fun stop(): RecordedAudio
    /** 幂等取消；不产生识别输入，也不访问网络。 */
    suspend fun cancel()
}

/** 本地离线识别接口；结果只能回填草稿，接口没有发送消息或交易的能力。 */
interface OfflineTranscriber {
    /** 消费句柄；结束或取消后清理音频，协程取消直接传播而不是包装成失败结果。 */
    suspend fun transcribe(audio: RecordedAudio): Result<String>
    /** 等正在执行的本地推理安全退出后释放模型，不能跨线程销毁活动 JNI 对象。 */
    suspend fun close()
}

/**
 * 以应用缓存根目录创建专用录音目录；录音器与识别器共享同一实例。
 * 规范化路径和随机签发标识同时校验，防止穿越、符号链接和仅同名缓存文件冒充录音。
 */
class AsrInputPolicy(cacheDir: File) {
    private val directory = File(cacheDir.canonicalFile, "voice-input").canonicalFile
    private val owners = ConcurrentHashMap<String, String>()

    /** 建立本轮随机缓存文件；目录创建失败抛异常，不回退到共享存储。 */
    internal fun createRecordingFile(): File {
        check(directory.isDirectory || directory.mkdirs()) { "无法创建本地录音缓存" }
        return File.createTempFile("capture_", ".m4a", directory)
    }

    /**
     * 由录音器在成功停止后登记文件和时长，返回唯一消费句柄。
     * 不接收字符串 URL；路径不属于缓存、空文件或超出录音限制时拒绝。
     */
    internal fun register(file: File, durationMillis: Long): RecordedAudio {
        val canonical = requireLocalFile(file)
        require(canonical.isFile && canonical.length() in 1..MAX_AUDIO_BYTES) { "录音文件无效" }
        require(durationMillis in MIN_RECORDING_MILLIS..MAX_RECORDING_MILLIS + STOP_GRACE_MILLIS) { "录音过短或超出时长" }
        val id = UUID.randomUUID().toString()
        check(owners.putIfAbsent(canonical.path, id) == null) { "录音已经登记" }
        return RecordedAudio(canonical, durationMillis.coerceAtMost(MAX_RECORDING_MILLIS), id)
    }

    /** 返回可解码的规范化文件；未登记、被替换或超尺寸的句柄均抛 IllegalArgumentException。 */
    fun requireOwned(audio: RecordedAudio): File {
        val canonical = requireLocalFile(audio.file)
        require(owners[canonical.path] == audio.ownershipId) { "仅支持本应用录音" }
        require(canonical.isFile && canonical.length() in 1..MAX_AUDIO_BYTES) { "录音文件无效" }
        return canonical
    }

    /** 仅注销并删除匹配签发标识的音频；拒绝输入绝不能导致外部文件被异常清理。 */
    fun discard(audio: RecordedAudio) {
        val canonical = runCatching { requireLocalFile(audio.file) }.getOrNull() ?: return
        if (owners.remove(canonical.path, audio.ownershipId)) canonical.delete()
    }

    /** 清理录音器尚未签发的本轮文件；调用者仅传自身保存的句柄，禁止批量清空缓存目录。 */
    internal fun discardUnregistered(file: File?) {
        val canonical = file?.let { runCatching { requireLocalFile(it) }.getOrNull() } ?: return
        if (!owners.containsKey(canonical.path)) canonical.delete()
    }

    /**
     * 先比较原始绝对父目录，再比较规范化父目录；穿越与指向外部文件的链接均被拒绝。
     * 文件名仅允许本类随机创建的 m4a，不接受 HTTP、content URI 或任意缓存附件。
     */
    private fun requireLocalFile(file: File): File {
        require(file.isAbsolute && file.parentFile?.absoluteFile == directory.absoluteFile) { "仅支持本应用录音" }
        val canonical = file.canonicalFile
        require(canonical.parentFile == directory && canonical.name.startsWith("capture_") && canonical.extension == "m4a") { "仅支持本应用录音" }
        return canonical
    }

    companion object {
        /** 最长录音30秒；这是录音器与状态机共用上限，不能据此推算识别耗时。 */
        const val MAX_RECORDING_MILLIS = 30_000L
        /** 少于半秒的录音通常不能形成有效 AAC 音频，提示重新录音并保留草稿。 */
        const val MIN_RECORDING_MILLIS = 500L
        /** AAC 96kbps 的30秒缓存远小于1MiB；额外限制防止解码异常文件耗尽内存。 */
        private const val MAX_AUDIO_BYTES = 1_048_576L
        /** 系统停止回调调度允许微小延迟，文件本身仍由 MediaRecorder 的30秒上限限制。 */
        private const val STOP_GRACE_MILLIS = 2_000L
    }
}
