// 原完整历史状态测量：由隔离回放提供回答，测量实际Activity保存树，不涉及ASR推理或录音。
package com.fandazi.app.feature.agent

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 仅记录原始大小；预算是后续15.5约束，初始版超预算仍须如实保留，不能改样本使其通过。 */
@RunWith(AndroidJUnit4::class)
class AgentSavedStateBaselineTest {
    /** 真实发送25/100轮形成50/200消息；整Activity与其中Agent模块分别Parcel序列化计量。 */
    @Test fun recordOriginalStateSizes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val app = instrumentation.targetContext.applicationContext as FandaziApplication
        val username = requireNotNull(arguments.getString("fixtureUsername")) { "缺少私有回放账号" }
        val password = requireNotNull(arguments.getString("fixturePassword")) { "缺少私有回放凭据" }
        val login = runBlocking { app.sessionRepository.login(username, password) }
        assertTrue("回放登录前置失败：${(login as? ApiResult.Failure)?.error}", login is ApiResult.Success)
        // 先核对真实仓库解析回放协议；失败信息只来自隔离合成接口，避免ViewModel友好错误隐藏根因。
        val probe = runBlocking { app.agentRepository.sendMessage(java.util.UUID.randomUUID().toString(), "perf-protocol-probe", AgentMode.CONFIRM) }
        assertTrue("回放协议不兼容：${(probe as? ApiResult.Failure)?.error}", probe is ApiResult.Success)
        // 测量代理只观察真实仓库返回，不改响应或造业务行；记录被友好错误映射遮住的协议失败。
        val originalRepository = app.agentRepository
        val repositoryFailure = java.util.concurrent.atomic.AtomicReference<com.fandazi.app.core.network.ApiError?>()
        val observedRepository = object : AgentRepository by originalRepository {
            override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult> =
                originalRepository.sendMessage(sessionId, text, mode).also { result ->
                    repositoryFailure.set((result as? ApiResult.Failure)?.error)
                }
        }
        // 仅测试通过反射接入观测代理，不给产品增加可替换仓库入口；finally恢复原装配。
        val field = FandaziApplication::class.java.getDeclaredField("agentRepository").apply { isAccessible = true }
        field.set(app, observedRepository)
        val records = JSONArray()
        try { ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model: AgentViewModel
            scenario.onActivity { activity ->
                model = ViewModelProvider(activity, AgentViewModelFactory(app.agentRepository))[AgentViewModel::class.java]
                model.bindAuthenticatedOwner(username)
            }
            // 前置只读HTTP就绪，逐轮等待终态，不用直接赋值塞入业务列表或调用假仓库。
            for (round in 1..100) {
                val oldCount = model.uiState.value.messages.size
                scenario.onActivity { model.sendText("perf-state-round-$round") }
                val deadline = System.nanoTime() + 15_000_000_000L
                while (model.uiState.value.isSending || model.uiState.value.messages.size < oldCount + 2) {
                    // 先显式区分协议/身份错误和调度超时，诊断只写合成回放的错误种类，不输出账号令牌。
                    check(model.uiState.value.error == null) { "回放错误：${model.uiState.value.error}；仓库原因=${repositoryFailure.get()}" }
                    check(System.nanoTime() < deadline) {
                        "回放回答未完成：$round，消息数=${model.uiState.value.messages.size}，发送中=${model.uiState.value.isSending}"
                    }
                    Thread.sleep(10)
                }
                if (round == 25 || round == 100) {
                    assertEquals(round * 2, model.uiState.value.messages.size)
                    val whole = Bundle()
                    // 固定2048字草稿是测量输入，不能据此给产品新增长度限制或截断用户编辑内容。
                    scenario.onActivity { model.updateComposer("测".repeat(2048)) }
                    scenario.onActivity { instrumentation.callActivityOnSaveInstanceState(it, whole) }
                    val agent = findAgentState(whole)
                    assertTrue("实际Activity保存树缺少Agent状态", agent != null)
                    records.put(JSONObject().put("messages", round * 2).put("draftCharacters", 2048).put("agentBundleBytes", bytes(agent!!))
                        .put("activityBundleBytes", bytes(whole)).put("source", "actual-activity-saved-state")
                        .put("pssKb", android.os.Debug.getPss())
                        .put("gcCount", android.os.Debug.getRuntimeStat("art.gc.gc-count"))
                        .put("allocatedBytes", android.os.Debug.getRuntimeStat("art.gc.bytes-allocated")))
                }
            }
        } } finally { field.set(app, originalRepository) }
        // 私有应用外部测试目录便于adb拉取；只输出字节/计数，不输出正文、账号或令牌。
        val output = File(app.getExternalFilesDir(null), "performance/saved-state-baseline.json")
        output.parentFile!!.mkdirs(); output.writeText(records.toString(2))
    }

    /** 递归读取真实保存树的Bundle，准确找到Activity级AgentViewModel字段，不估算对象堆大小。 */
    private fun findAgentState(bundle: Bundle): Bundle? {
        for (key in bundle.keySet()) {
            val nested = bundle.get(key) as? Bundle ?: continue
            if (key.endsWith("AgentViewModel")) return nested
            findAgentState(nested)?.let { return it }
        }
        return null
    }

    /** 使用Android实际Parcel编码计量Bundle；finally回收避免测量器自己保留序列化缓冲。 */
    private fun bytes(bundle: Bundle): Int {
        val parcel = Parcel.obtain()
        return try { parcel.writeBundle(bundle); parcel.dataSize() } finally { parcel.recycle() }
    }
}
