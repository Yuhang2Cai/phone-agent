// 活动恢复设备验收：在真实应用进程中核对原子薄引用、会话归属和RN桥；不发业务写请求。
package com.fandazi.app.feature.activity

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.PromiseImpl
import com.facebook.react.bridge.ReadableMap
import com.fandazi.app.FandaziApplication
import com.fandazi.app.feature.auth.SessionState
import com.fandazi.app.feature.auth.authenticationIdentity
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 由明确的expectedPending参数选择前置，不创建账号/任务，也不自动领取或领奖。 */
@RunWith(AndroidJUnit4::class)
class ActivityRecoveryDeviceTest {
    /** 真实进程重建后读取同一磁盘引用并通过原生桥解析；仅打印非敏感断言结果。 */
    @Test fun pendingReferenceSurvivesProcessAndCrossesBridge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val expected = InstrumentationRegistry.getArguments().getString("expectedPending")
        require(expected == "present" || expected == "absent") { "必须明确选择薄引用存在性前置" }
        val app = instrumentation.targetContext.applicationContext as FandaziApplication
        val state = app.sessionRepository.state.value
        assertTrue("设备测试须使用已登录合成账号", state is SessionState.Authenticated)
        val file = File(app.noBackupFilesDir, "activity_pending_reference.json")
        assertEquals("薄引用存在性必须匹配故障/恢复阶段", expected == "present", file.exists())
        val record = if (file.exists()) JSONObject(file.readText()) else null
        if (record != null) {
            // 在调用宿主前独立核对归属，避免宿主清理错身份记录后掩盖原问题。
            val owner = "${(state as SessionState.Authenticated).user.username}:${authenticationIdentity(state)}"
            val digest = MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            assertEquals("进程重建不能改变原请求会话归属", digest, record.getString("owner"))
        }
        val runtime = app.activityRuntime
        // 启动正式无页面运行时并取实际注册模块；不实例化旧架构上下文，也不渲染页面触发回读。
        val startup = runtime.host.start()
        assertTrue("正式RN运行时须在20秒内启动", startup.waitForCompletion(20, TimeUnit.SECONDS))
        assertNull("正式RN运行时初始化失败", startup.getError())
        // 旧模块互操作按注册名查找；按类查找只适用于带ReactModule注解的模块。
        val bridge = requireNotNull(runtime.host.currentReactContext?.getNativeModule("ActivityHost") as? ActivityHostModule)
        val result = AtomicReference<Any?>()
        val rejection = AtomicReference<String?>()
        val completed = CountDownLatch(1)
        val promise = PromiseImpl(Callback { values -> result.set(values.firstOrNull()); completed.countDown() },
            Callback { values -> rejection.set((values.firstOrNull() as? ReadableMap)?.getString("code") ?: "桥调用被拒绝"); completed.countDown() })
        try {
            // 与JS相同的公开桥方法，在IO线程读文件并回传真实NativeMap，不以单元替身代替。
            bridge.getPending(runtime.refreshIdentity().generation.toDouble(), promise)
            assertTrue("桥调用须在10秒内结束", completed.await(10, TimeUnit.SECONDS))
            assertNull("薄引用桥不能拒绝：${rejection.get()}", rejection.get())
            val reference = result.get() as? ReadableMap
            if (record == null) assertNull("没有未确认写时桥应返回null", reference)
            else {
                assertNotNull("未确认写必须通过桥传回", reference)
                assertEquals(record.getString("action"), reference!!.getString("action"))
                val payload = reference.getMap("payload")!!
                assertEquals(record.getJSONObject("payload").getString("requestId"), payload.getString("requestId"))
                assertEquals(record.getJSONObject("payload").getString("id"), payload.getString("id"))
            }
        } finally {
            bridge.invalidate()
        }
    }
}
