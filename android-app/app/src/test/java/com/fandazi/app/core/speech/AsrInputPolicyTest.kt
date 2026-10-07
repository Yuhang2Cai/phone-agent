// ASR 输入边界测试：约束录音来源及清理职责；本轮按用户要求只建立测试，不执行。
package com.fandazi.app.core.speech

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** 防止外部文件、伪造句柄或路径穿越进入本地解码；测试夹具仅写临时缓存。 */
class AsrInputPolicyTest {
    /** 正常录音由策略登记，识别后删除文件，句柄不能再次消费。 */
    @Test fun registeredCaptureCanBeConsumedOnceAndCleaned() {
        val cache = Files.createTempDirectory("asr-policy").toFile()
        try {
            val policy = AsrInputPolicy(cache)
            val file = policy.createRecordingFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val audio = policy.register(file, 1000)
            assertEquals(file.canonicalFile, policy.requireOwned(audio))
            policy.discard(audio)
            assertFalse(file.exists())
            assertThrows(IllegalArgumentException::class.java) { policy.requireOwned(audio) }
        } finally { cache.deleteRecursively() }
    }

    /** 外部/HTTP来源没有录音器签发凭据，不能以文件名或手工句柄绕过校验。 */
    @Test fun outsideAndHttpLikePathsCannotBeRegisteredOrConsumed() {
        val cache = Files.createTempDirectory("asr-policy").toFile()
        val outside = File.createTempFile("external-asr", ".m4a")
        try {
            val policy = AsrInputPolicy(cache)
            for (file in listOf(outside, File("https://example.invalid/audio.m4a"), File(cache, "voice-input/../outside.m4a"))) {
                assertThrows(IllegalArgumentException::class.java) { policy.register(file, 1000) }
                assertThrows(IllegalArgumentException::class.java) { policy.requireOwned(RecordedAudio(file, 1000, "forged")) }
            }
            // 拒绝输入不能顺手删除外部资源，避免异常清理扩大到不属于本轮的文件。
            policy.discard(RecordedAudio(outside, 1000, "forged"))
            assertTrue(outside.exists())
        } finally { outside.delete(); cache.deleteRecursively() }
    }

    /** 同一目录内未登记文件也不是合法输入，不能只靠缓存路径判断可信来源。 */
    @Test fun unregisteredCacheFileIsRejected() {
        val cache = Files.createTempDirectory("asr-policy").toFile()
        try {
            val policy = AsrInputPolicy(cache)
            val file = policy.createRecordingFile().apply { writeBytes(byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) { policy.requireOwned(RecordedAudio(file, 1000, "forged")) }
        } finally { cache.deleteRecursively() }
    }
}
