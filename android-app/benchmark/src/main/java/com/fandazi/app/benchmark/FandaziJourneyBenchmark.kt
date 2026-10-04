// 固定性能驱动：通过真实页面操作测量release派生目标，夹具与凭据只从隔离回放和运行参数取得。
package com.fandazi.app.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/** 每次调用生成独立JSON/Perfetto；三批由外部运行器调用，禁止把模拟器输出称真机基线。 */
@RunWith(AndroidJUnit4::class)
class FandaziJourneyBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val arguments = InstrumentationRegistry.getArguments()

    /** 原始启动控制固定每批10次，不生成Profiles，CompilationMode.None保持可比编译条件。 */
    @Test fun coldStartup() {
        benchmark.measureRepeated(
            packageName = PACKAGE, metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            iterations = 10, startupMode = StartupMode.COLD, compilationMode = CompilationMode.None(),
            // 每次启动控制都走同一未登录页面，避免上一场聊天历史改变启动工作量。
            setupBlock = { device.executeShellCommand("pm clear $PACKAGE"); pressHome() }
        ) { startActivityAndWait() }
    }

    /** 固定目录规模与同一组手势；空缓存及预热缓存分别报告，不能混合计算收益。 */
    @Test fun catalogScrollCold() = catalogScroll(clearCache = true)

    /** 预热返回测量复用同一图片；只有该批自己的预热完成后才开始采样。 */
    @Test fun catalogScrollWarm() = catalogScroll(clearCache = false)

    /** 20轮实际Tab往返测量栈及请求代价；每次等待目标出现，不把空点击算完成。 */
    @Test fun tabRoundTrips() {
        benchmark.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), CompilationMode.None(), iterations = 3,
            setupBlock = { startActivityAndWait(); enterGuestIfNeeded(); waitFor("store_name") }) {
            repeat(20) {
                click("profileFragment"); device.waitForIdle()
                click("homeFragment"); waitFor("store_name")
            }
        }
    }

    /** 聊天用隔离账号及后端回答，人工发送5轮；不录音、不碰ASR权限或模型。 */
    @Test fun chatRounds() {
        benchmark.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), CompilationMode.None(), iterations = 3,
            setupBlock = {
                // 每批聊天从明确的未登录状态开始，避免沿用目录游客或上一批账号改变准备路径。
                device.executeShellCommand("pm clear $PACKAGE")
                startActivityAndWait(); loginIfNeeded(); click("agentAssistantTab"); waitFor("agent_chat_input")
            }) {
            val requestStart = messageRequestCount()
            repeat(5) { index ->
                val input = device.findObject(By.res(PACKAGE, "agent_chat_input"))
                input.text = "perf-query-$index"
                hideKeyboardIfShown()
                check(device.wait(Until.hasObject(By.res(PACKAGE, "agent_chat_send").enabled(true)), 15000)) { "聊天发送资格未恢复" }
                click("agent_chat_send")
                // 发送会清空草稿，空草稿的按钮正常禁用；先写未发送的下一轮草稿才能观察isSending结束。
                // 最后一轮仅保留临时等待草稿，随后清除，绝不多发第六轮。
                input.text = "perf-wait-next"
                check(device.wait(Until.hasObject(By.res(PACKAGE, "agent_chat_send").enabled(true)), 15000)) { "聊天未完成" }
                device.waitForIdle()
            }
            device.findObject(By.res(PACKAGE, "agent_chat_input")).text = ""
            check(messageRequestCount() - requestStart == 5) { "后端未收到精确五轮聊天，不能作为测量" }
        }
    }

    /** 公共目录滚动流程；500行只由服务器配置选取，Android不内置门店身份或金额。 */
    private fun catalogScroll(clearCache: Boolean) {
        control(JSONObject().put("catalogSize", arguments.getString("catalogSize", "100").toInt()))
        benchmark.measureRepeated(PACKAGE, listOf(FrameTimingMetric()), CompilationMode.None(), iterations = 5,
            setupBlock = {
                // 冷图缓存通过清理应用数据实现；该动作不计入滚动测量，之后重新走游客浏览入口。
                if (clearCache) device.executeShellCommand("pm clear $PACKAGE")
                startActivityAndWait(); enterGuestIfNeeded(); waitFor("store_name")
                if (!clearCache) { swipeRows(); device.pressBack(); startActivityAndWait(); enterGuestIfNeeded(); waitFor("store_name") }
            }) { swipeRows() }
    }

    /** 固定手势避开输入/底栏；每次方向和步数相同，后续RecyclerView使用同一触摸输入。 */
    private fun swipeRows() {
        repeat(12) { device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
            device.displayWidth / 2, device.displayHeight * 2 / 5, 30) }
        repeat(12) { device.swipe(device.displayWidth / 2, device.displayHeight * 2 / 5,
            device.displayWidth / 2, device.displayHeight * 4 / 5, 30) }
    }

    /** 仅在初次启动点击现有游客入口；不修改Application或后台身份存储装配。 */
    private fun enterGuestIfNeeded() {
        device.findObject(By.res(PACKAGE, "skip_login_button"))?.click()
        device.waitForIdle()
    }

    /** 从私有运行参数输入隔离后端签发的账号；无参数明确失败，不使用源码内置凭据。 */
    private fun loginIfNeeded() {
        if (device.hasObject(By.res(PACKAGE, "agent_chat_input"))) return
        if (!device.hasObject(By.res(PACKAGE, "username_input"))) {
            click("profileFragment")
            device.findObject(By.res(PACKAGE, "profile_login_entry"))?.click()
        }
        val username = requireNotNull(arguments.getString("fixtureUsername")) { "缺少隔离回放账号" }
        val password = requireNotNull(arguments.getString("fixturePassword")) { "缺少隔离回放凭据" }
        device.findObject(By.res(PACKAGE, "username_input"))?.text = username
        device.findObject(By.res(PACKAGE, "password_input"))?.text = password
        hideKeyboardIfShown(); click("submit_button"); waitFor("bottom_navigation")
    }

    /** UiAutomator赋值未必打开软键盘；只在系统报告输入窗口已显示时返回，防止误退出当前页面。 */
    private fun hideKeyboardIfShown() {
        val inputState = device.executeShellCommand("dumpsys input_method")
        if (inputState.contains("mInputShown=true")) device.pressBack()
    }

    /** 点击前等待资源身份，超过15秒抛明确断言，防止测到错误页面的空闲帧。 */
    private fun click(id: String) { waitFor(id); device.findObject(By.res(PACKAGE, id)).click() }

    /** 固定15秒就绪门槛，仅检测UI结构；业务内容由回放请求日志交叉核对。 */
    private fun waitFor(id: String) {
        check(device.wait(Until.hasObject(By.res(PACKAGE, id)), 15000)) { "测量页面未就绪：$id" }
    }

    /** 私有回放控制只向本机18080提交白名单参数，禁止继续到真实交易路径。 */
    private fun control(body: JSONObject) {
        val controlBase = arguments.getString("fixtureControlUrl", "http://10.0.2.2:18080")
        val call = URL("$controlBase/__perf/control").openConnection() as HttpURLConnection
        try {
            call.requestMethod = "POST"; call.doOutput = true
            call.connectTimeout = 5000; call.readTimeout = 5000
            call.setRequestProperty("Content-Type", "application/json")
            call.outputStream.use { it.write(body.toString().toByteArray()) }
            check(call.responseCode == 200) { "隔离性能回放未运行" }
        } finally { call.disconnect() }
    }

    /** 用后端只读计数交叉验证每批实际发送轮数；不读取账号、会话正文或资格数据。 */
    private fun messageRequestCount(): Int {
        val controlBase = arguments.getString("fixtureControlUrl", "http://10.0.2.2:18080")
        val call = URL("$controlBase/__perf/stats").openConnection() as HttpURLConnection
        try {
            call.connectTimeout = 5000; call.readTimeout = 5000
            check(call.responseCode == 200) { "无法核对回放请求计数" }
            val stats = JSONObject(call.inputStream.bufferedReader().use { it.readText() })
            return stats.getJSONObject("requests").optInt("messageRequests", 0)
        } finally { call.disconnect() }
    }

    private companion object {
        /** 唯一正式应用包名；驱动不能选择其他用户应用或生成第二套产品页面。 */
        const val PACKAGE = "com.fandazi.app"
    }
}
