// 真后端历史恢复及实际Activity状态测量：读取隔离MySQL已持久50/200消息，不调用ASR或重发业务。
package com.fandazi.app.feature.agent

import android.os.Bundle
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
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
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 测量真实后端恢复与真实保存树，测试参数仅私有runner注入，不写凭据到报告。 */
@RunWith(AndroidJUnit4::class)
class AgentHistoryDeviceTest {
    /** 同设备、同线程、同2000原事件加2000重放输入比较旧批量fold与新索引分配，结果身份和顺序必须一致。 */
    @Test fun measureEventIndexAllocationAgainstOriginalBatchFold() {
        val original=(1..2000).map { number -> AgentEvent(number.toLong(),"progress","相同文案",payload=mapOf(
            "eventId" to "event-$number","runId" to "run","traceId" to "trace","sequence" to number.toString())) }
        val input=original+original
        // 先暖执行相同操作，避免把首次类加载费用归给某个实现；不变更应用或服务的数据条件。
        repeat(2) { legacyBatchFold(input);AgentEventIdentityIndex().newEvents(input) }
        fun measure(operation:()->List<AgentEvent>):Pair<List<AgentEvent>,JSONObject> {
            val before=android.os.Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
            val started=System.nanoTime()
            val output=operation()
            val elapsed=(System.nanoTime()-started)/1_000_000.0
            val allocated=android.os.Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()-before
            return output to JSONObject().put("allocatedBytes",allocated).put("elapsedMillis",elapsed)
        }
        val legacy=measure { legacyBatchFold(input) }
        val indexed=measure { AgentEventIdentityIndex().newEvents(input) }
        assertEquals(original,legacy.first)
        assertEquals(legacy.first,indexed.first)
        assertTrue("身份索引批量处理不得继续产生旧逐项列表复制分配",indexed.second.getLong("allocatedBytes")<legacy.second.getLong("allocatedBytes"))
        val summary=JSONObject().put("inputEvents",4000).put("uniqueEvents",2000).put("resultAndOrderEqual",true)
            .put("legacy",legacy.second).put("indexed",indexed.second)
            .put("scope","专用x86_64模拟器debug定向分配观察，非正式arm64性能验收")
        val app=InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val output=File(app.getExternalFilesDir(null),"performance/event-index-allocation-device.json")
        output.parentFile!!.mkdirs();output.writeText(summary.toString(2))
    }

    /** 两种规模全量恢复、2048字草稿保存及配置重建；不把重建测量冒称正式性能结论。 */
    @Test fun restorePersistedHistoryAndMeasureActualSavedState() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val arguments=InstrumentationRegistry.getArguments()
        val app=instrumentation.targetContext.applicationContext as FandaziApplication
        val owner=requireNotNull(arguments.getString("fixtureUsername"))
        val login=runBlocking { app.sessionRepository.login(owner,requireNotNull(arguments.getString("fixturePassword"))) }
        assertTrue("合成账号登录前置失败",login is ApiResult.Success)
        val records=JSONArray()
        for(size in listOf(50,200)) {
            val session=requireNotNull(arguments.getString("session$size"))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var model:AgentViewModel
                scenario.onActivity { activity ->
                    val original=ViewModelProvider(activity,AgentViewModelFactory(app.agentRepository, app))[AgentViewModel::class.java]
                    // 复用Activity已注册的真实SavedStateHandle，只注入薄恢复索引模拟系统传入状态；正文仍从真实GET读取。
                    val handleField=AgentViewModel::class.java.getDeclaredField("savedState").apply { isAccessible=true }
                    val handle=handleField.get(original) as SavedStateHandle
                    activity.viewModelStore.clear()
                    handle["agent_session_owner"]=owner
                    handle["agent_session_id"]=session
                    handle["agent_session_established"]=true
                    handle["agent_composer"]="测".repeat(2048)
                    val factory=object:ViewModelProvider.Factory {
                        /** 创建产品ViewModel，沿用系统已注册的薄字段，无测试伪造消息或业务卡。 */
                        @Suppress("UNCHECKED_CAST")
                        override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T =
                            AgentViewModel(app.agentRepository,handle) as T
                    }
                    model=ViewModelProvider(activity,factory)[AgentViewModel::class.java]
                    model.bindAuthenticatedOwner(owner)
                    model.openAssistant()
                }
                val started=System.nanoTime()
                awaitHistory(model,size)
                val elapsed=(System.nanoTime()-started)/1_000_000.0
                assertEquals(size,model.uiState.value.messages.size)
                assertEquals(size,model.uiState.value.messages.map { it.displayKey }.toSet().size)
                // 与夹具完整正文逐字核对，避免错误估算字符数量把正确响应判成截断。
                assertTrue("恢复正文必须与后端夹具完整一致", model.uiState.value.messages.all {
                    it.text.startsWith("O7历史$size") && it.text.substringAfter("条")=="完整正文。".repeat(320)
                })
                assertEquals("测".repeat(2048),model.uiState.value.composerText)
                assertFalse(model.uiState.value.authorization.isTaskAuto)
                val whole=Bundle()
                scenario.onActivity { instrumentation.callActivityOnSaveInstanceState(it,whole) }
                val agent=requireNotNull(findAgentState(whole)) { "实际Activity树缺少Agent保存字段" }
                assertFalse(agent.containsKey("agent_messages"))
                assertFalse(agent.containsKey("agent_events"))
                assertFalse(agent.containsKey("agent_authorization"))
                val agentBytes=bytes(agent);val wholeBytes=bytes(whole)
                assertTrue("Agent超过冻结32KiB预算",agentBytes<=32768)
                assertTrue("Activity超过冻结128KiB预算",wholeBytes<=131072)
                records.put(JSONObject().put("messages",size).put("draftCharacters",2048)
                    .put("agentBundleBytes",agentBytes).put("activityBundleBytes",wholeBytes)
                    .put("recoveryMillis",elapsed).put("pssKb",android.os.Debug.getPss())
                    .put("allocatedBytes",android.os.Debug.getRuntimeStat("art.gc.bytes-allocated"))
                    .put("gcCount",android.os.Debug.getRuntimeStat("art.gc.gc-count"))
                    .put("source","实际Spring历史GET及Activity保存树"))
            }
        }
        val output=File(app.getExternalFilesDir(null),"performance/history-recovery-device.json")
        output.parentFile!!.mkdirs();output.writeText(records.toString(2))
    }

    /** 等待真实网络全页恢复，失败立即报安全状态，不把HTTP错误当超时或打印认证响应。 */
    private fun awaitHistory(model:AgentViewModel,count:Int) {
        val deadline=System.nanoTime()+30_000_000_000L
        while(model.uiState.value.isRecoveringHistory || model.uiState.value.messages.size!=count) {
            check(model.uiState.value.error==null) { "历史恢复失败：${model.uiState.value.error}" }
            check(System.nanoTime()<deadline) { "历史未完整恢复，期望$count，实际${model.uiState.value.messages.size}" }
            Thread.sleep(10)
        }
    }

    /** 在实际SavedStateRegistry保存树中定位Agent字段，不估算内存对象大小。 */
    private fun findAgentState(bundle:Bundle):Bundle? {
        for(key in bundle.keySet()) {
            val nested=bundle.get(key) as? Bundle ?: continue
            if(key.endsWith("AgentViewModel"))return nested
            findAgentState(nested)?.let { return it }
        }
        return null
    }

    /** 实际Parcel序列化，finally回收缓冲，避免测量器累积分配。 */
    private fun bytes(bundle:Bundle):Int {
        val parcel=Parcel.obtain()
        return try { parcel.writeBundle(bundle);parcel.dataSize() } finally { parcel.recycle() }
    }

    /** 固定Task15.4原addIfNew/fold身份语义作为测量基线，不进入生产代码，避免优化改变重放归属。 */
    private fun legacyBatchFold(input:List<AgentEvent>):List<AgentEvent> = input.fold(emptyList()) { accepted,event ->
        if(accepted.any { old -> (event.eventId!=null && old.eventId==event.eventId) ||
            (event.id!=null && old.id==event.id) ||
            (event.runId!=null && event.traceId!=null && event.sequence!=null && old.runId==event.runId &&
                old.traceId==event.traceId && old.sequence==event.sequence) }) accepted else accepted+event
    }
}
