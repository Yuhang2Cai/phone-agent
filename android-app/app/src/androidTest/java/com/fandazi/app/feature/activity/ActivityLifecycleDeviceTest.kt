// 活动生命周期设备验收：使用正式身份、导航和RN运行时，验证前后台、重建及受控运行时异常恢复。
package com.fandazi.app.feature.activity

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 不替换业务接口或认证，受控异常经RN真实异常处理入口进入原生兜底。 */
@RunWith(AndroidJUnit4::class)
class ActivityLifecycleDeviceTest {
    /** 验证重建仍复用宿主、后台停止焦点，异常后原生按钮可重新加载正式Bundle。 */
    @Test fun lifecycleAndNativeRetryUseOneHost() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as FandaziApplication
        ActivityScenario.launch(MainActivity::class.java).use { page ->
            page.onActivity { it.findViewById<BottomNavigationView>(R.id.bottom_navigation).selectedItemId = R.id.activityFragment }
            val runtime = app.activityRuntime
            await("初次挂载应就绪") { runtime.snapshot.value.focused && runtime.host.currentReactContext != null }
            val host = runtime.host
            page.moveToState(Lifecycle.State.CREATED)
            await("进入后台应停止活动焦点") { !runtime.snapshot.value.focused }
            page.moveToState(Lifecycle.State.RESUMED)
            await("前台返回应恢复活动焦点") { runtime.snapshot.value.focused }
            page.recreate()
            await("Activity重建应恢复活动页面") { runtime.snapshot.value.focused && runtime.host.currentReactContext != null }
            assertSame("生命周期变化不能创建第二个宿主", host, runtime.host)
            val count = ActivityRuntime::class.java.getDeclaredField("runtimeCount").apply { isAccessible = true }.getInt(runtime)
            assertEquals("运行时只创建一次", 1, count)
            // 通过RN正式异常入口触发失败；此为受控生命周期故障，不伪造业务错误或修改Bundle。
            page.onActivity { runtime.host.currentReactContext!!.handleException(IllegalStateException("活动验收受控运行时异常")) }
            await("异常应进入原生兜底") { runtime.failure.value }
            await("原生兜底应可见") { var visible = false; page.onActivity { visible = it.findViewById<View>(R.id.activity_native_error)?.visibility == View.VISIBLE }; visible }
            page.onActivity {
                val overlay = it.findViewById<ViewGroup>(R.id.activity_native_error)
                val button = descendants(overlay).filterIsInstance<Button>().single { view -> view.text.toString() == "重新加载" }
                assertTrue("原生重试按钮应可点击", button.performClick())
            }
            await("重试应恢复正式上下文") { !runtime.failure.value && runtime.host.currentReactContext != null }
            page.onActivity { assertEquals("成功重试须移除错误覆盖层", View.GONE, it.findViewById<View>(R.id.activity_native_error).visibility) }
        }
    }
    /** 有界等待实际异步状态，超时以明确断言失败，不把等待结束等同成功。 */
    private fun await(message: String, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 20000
        while (SystemClock.elapsedRealtime() < end) { if (ready()) return; SystemClock.sleep(50) }
        fail(message)
    }
    /** 从真实覆盖层寻找按钮，避免测试直接调用重试而漏掉点击接线。 */
    private fun descendants(parent: ViewGroup): List<View> = (0 until parent.childCount).flatMap { index ->
        val child = parent.getChildAt(index)
        listOf(child) + if (child is ViewGroup) descendants(child) else emptyList()
    }
}
