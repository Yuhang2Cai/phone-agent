// 正式聊天列表定向测量：后端200条完整历史、固定增量回放及真实触控上翻，区别于真实模型端到端性能。
package com.fandazi.app.feature.agent
import android.os.SystemClock
import android.view.MotionEvent
import android.widget.EditText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 测量使用真实正式Fragment/Adapter和后端完整正文，固定事件仅用于展示成本及拖动竞态验证。 */
@RunWith(AndroidJUnit4::class)
class AgentChatRenderingDeviceTest {
    /** 恢复200条、快速回放300个增量、拖动后验证稳定首行，终态完整正文和回收计数分别记录。 */
    @Test fun actualPageReusesOffscreenRowsAndDoesNotStealDraggedAnchor() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val args=InstrumentationRegistry.getArguments()
        val app=instrumentation.targetContext.applicationContext as FandaziApplication
        val username=requireNotNull(args.getString("fixtureUsername"))
        assertTrue(runBlocking{app.sessionRepository.login(username,requireNotNull(args.getString("fixturePassword")))} is ApiResult.Success)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var model:AgentViewModel;lateinit var recycler:RecyclerView;lateinit var adapter:AgentMessageAdapter
            scenario.onActivity { activity ->
                val old=ViewModelProvider(activity,AgentViewModelFactory(app.agentRepository, app))[AgentViewModel::class.java]
                val field=AgentViewModel::class.java.getDeclaredField("savedState").apply{isAccessible=true};val handle=field.get(old) as SavedStateHandle
                activity.viewModelStore.clear();handle["agent_session_owner"]=username;handle["agent_session_id"]=requireNotNull(args.getString("session200"));handle["agent_session_established"]=true
                handle["agent_scroll_key"]=null;handle["agent_scroll_offset"]=0
                val factory=object:ViewModelProvider.Factory {
                    /** 只注入薄恢复索引，正文仍由实际服务读取，不构造私人历史UI样例。 */
                    @Suppress("UNCHECKED_CAST")
                    override fun <T:androidx.lifecycle.ViewModel> create(modelClass:Class<T>):T=AgentViewModel(app.agentRepository,handle) as T
                }
                model=ViewModelProvider(activity,factory)[AgentViewModel::class.java]
                (activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment).navController.navigate(R.id.agentChatFragment)
            }
            await("后端200条完整历史"){model.uiState.value.messages.size==200 && !model.uiState.value.isRecoveringHistory}
            scenario.onActivity{recycler=it.findViewById(R.id.agent_chat_scroll);adapter=recycler.adapter as AgentMessageAdapter}
            await("首批实际列表差量"){adapter.itemCount>=200 && recycler.childCount>0}
            val initial=adapter.metrics.creates.sum();assertTrue("RecyclerView不应为200条历史一次创建200个Holder",initial<50)
            // 固定事件无业务卡或写入，输入顺序与完整正文独立断言；不是模型响应耗时测量。
            val frameTimes=mutableListOf<Long>();val body="流式展示测量。".repeat(300);val started=System.nanoTime()
            scenario.onActivity{model.onAgentEvent(AgentEvent(900000,"run_started","",payload=mapOf("runId" to "measure-run","traceId" to "measure-trace","eventId" to "measure-start","sequence" to "0")))}
            repeat(30){batch ->
                scenario.onActivity {
                    val from=System.nanoTime()
                    repeat(10){part ->val n=batch*10+part;model.onAgentEvent(AgentEvent(900001L+n,"text_delta","",payload=mapOf("runId" to "measure-run","traceId" to "measure-trace","eventId" to "measure-$n","sequence" to (n+1).toString(),"delta" to body.substring(n*7,(n+1)*7))))}
                    frameTimes+=System.nanoTime()-from
                }
                if(batch==2) {
                    // waitForIdleSync不保证未来vsync已完成布局；先让前两批展示落地，再准备可控的上翻区域。
                    Thread.sleep(120)
                    scenario.onActivity {
                        val layout=recycler.layoutManager as LinearLayoutManager
                        layout.scrollToPositionWithOffset(40,0)
                    }
                    await("上翻准备位置实际布局") {var ready=false;scenario.onActivity{ready=(recycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition() in 39..40};ready}
                    scenario.onActivity {
                        // 真实触控DRAGGING使旧Diff回调失效，不能用程序定位冒充用户滚动竞态。
                        val time=SystemClock.uptimeMillis();val x=recycler.width/2f;val y=recycler.height/2f
                        var dragged=false
                        listOf(MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,x,y,0),MotionEvent.obtain(time,time+16,MotionEvent.ACTION_MOVE,x,y+120,0),MotionEvent.obtain(time,time+32,MotionEvent.ACTION_MOVE,x,y+240,0),MotionEvent.obtain(time,time+48,MotionEvent.ACTION_UP,x,y+240,0)).forEach{event->recycler.dispatchTouchEvent(event);dragged=dragged || recycler.scrollState==RecyclerView.SCROLL_STATE_DRAGGING;event.recycle()}
                        assertTrue("设备触控必须实际进入DRAGGING，不能把程序位置准备当作用户拖动",dragged)
                    }
                }
                Thread.sleep(20)
            }
            await("完整300个增量"){model.uiState.value.messages.last().text==body.take(2100)}
            lateinit var anchor:String
            var anchorOffset=0
            scenario.onActivity{val layout=recycler.layoutManager as LinearLayoutManager;val position=layout.findFirstVisibleItemPosition();anchor=adapter.currentList[position].key;anchorOffset=layout.getDecoratedTop(requireNotNull(layout.findViewByPosition(position)))-recycler.paddingTop;assertTrue("流式期间用户上翻位置被抢走：$position",position<100)}
            scenario.onActivity{model.onAgentEvent(AgentEvent(900999,"run_completed",body.take(2100),status="succeeded",payload=mapOf("runId" to "measure-run","traceId" to "measure-trace","eventId" to "measure-final","sequence" to "301")))}
            Thread.sleep(200);instrumentation.waitForIdleSync()
            scenario.onActivity{
                val layout=recycler.layoutManager as LinearLayoutManager
                assertEquals("终态刷新不得抢走用户上翻锚点",anchor,adapter.currentList[layout.findFirstVisibleItemPosition()].key)
                assertEquals(body.take(2100),model.uiState.value.messages.last().text)
                assertEquals(1,model.uiState.value.messages.count{it.displayKey==model.uiState.value.messages.last().displayKey})
                val metrics=adapter.metrics;assertTrue(metrics.creates.sum()<60)
                val result=JSONObject().put("historyMessages",200).put("fixedReplayDeltas",300).put("fullCharacters",2100)
                    .put("holderCreates",JSONArray(metrics.creates.toList())).put("holderBinds",JSONArray(metrics.binds.toList()))
                    .put("textPayloads",metrics.textPayloads).put("stepSlots",metrics.stepSlots).put("renderedFrames",metrics.renderedFrames)
                    .put("renderTotalNanos",metrics.renderNanos).put("renderMaxNanos",metrics.maxRenderNanos).put("eventBatchNanos",JSONArray(frameTimes))
                    .put("totalReplayMillis",(System.nanoTime()-started)/1_000_000.0).put("pssKb",android.os.Debug.getPss())
                    .put("draggedAnchorPreserved",true).put("scope","固定事件回放及x86_64调试单批观察，非模型端到端/正式arm64三批性能验收").put("asr","未运行")
                val output=File(app.getExternalFilesDir(null),"performance/chat-rendering-device.json");output.parentFile!!.mkdirs();output.writeText(result.toString(2))
            }
            // Activity真实重建复用正常生命周期及NavHost恢复，不触发录音；检查首批消息布局后再读稳定身份和偏移。
            scenario.onActivity{it.findViewById<EditText>(R.id.agent_chat_input).setText("recreate draft retained")}
            // 明确换到另一历史行，检验没有DRAGGING的程序定位；不能借刚结束的用户手势已保存锚点而误通过。
            scenario.onActivity{(recycler.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(60,-20)}
            // 前面的ACTION_UP可能仍有惯性和终态差量布局；重建期望必须取紧邻重建的实际位置，而非更早增量阶段的偏移。
            Thread.sleep(150)
            scenario.onActivity { activity ->
                recycler.stopScroll()
                val layout=recycler.layoutManager as LinearLayoutManager
                val position=layout.findFirstVisibleItemPosition()
                anchor=adapter.currentList[position].key
                anchorOffset=layout.getDecoratedTop(requireNotNull(layout.findViewByPosition(position)))-recycler.paddingTop
                // 只记录状态相等性与像素值，不打印账号、消息key或私人正文，定位保存时序与真实页面实例。
                val host=activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
                val page=host.childFragmentManager.primaryNavigationFragment as AgentChatFragment
                val field=AgentChatFragment::class.java.getDeclaredField("viewModel").apply{isAccessible=true}
                val pending=AgentChatFragment::class.java.getDeclaredField("restoreKey").apply{isAccessible=true}
                val diagnostic=JSONObject().put("sameModel",field.get(page)===model).put("modelKeyEquals",model.uiState.value.scrollAnchorKey==anchor)
                    .put("expectedOffset",anchorOffset).put("modelOffset",model.uiState.value.scrollAnchorOffset).put("firstPosition",position)
                    .put("scrollState",recycler.scrollState).put("restorePending",pending.get(page)!=null)
                val output=File(app.getExternalFilesDir(null),"performance/anchor-diagnostic.json");output.parentFile!!.mkdirs();output.writeText(diagnostic.toString(2))
            }
            // 程序定位无需手势状态变化，也必须在Activity真正保存树之前写入薄锚点；否则进程恢复会回到发送前位置。
            await("程序定位后薄锚点及时更新") {
                var ready=false
                scenario.onActivity {
                    val layout=recycler.layoutManager as LinearLayoutManager
                    val position=layout.findFirstVisibleItemPosition()
                    val key=adapter.currentList.getOrNull(position)?.key
                    val child=layout.findViewByPosition(position)
                    val offset=child?.let{layout.getDecoratedTop(it)-recycler.paddingTop}
                    // 差量提交还可能排入下一帧布局；比较当前真实位置与薄状态，保存同一次实际采样作重建期望。
                    ready=position in 59..60 && key!=null && offset!=null && model.uiState.value.scrollAnchorKey==key && model.uiState.value.scrollAnchorOffset==offset
                    if(ready){anchor=key!!;anchorOffset=offset!!}
                }
                ready
            }
            scenario.recreate()
            await("重建聊天消息真实布局") {
                var ready=false
                scenario.onActivity { activity ->
                    val current=activity.findViewById<RecyclerView>(R.id.agent_chat_scroll)
                    val currentAdapter=current?.adapter as? AgentMessageAdapter
                    val currentLayout=current?.layoutManager as? LinearLayoutManager
                    ready=currentAdapter!=null && currentLayout!=null && currentAdapter.currentList.getOrNull(currentLayout.findFirstVisibleItemPosition())?.key==anchor
                    if(ready){recycler=requireNotNull(current);adapter=requireNotNull(currentAdapter)}
                }
                ready
            }
            scenario.onActivity { activity ->
                val layout=recycler.layoutManager as LinearLayoutManager
                val position=layout.findFirstVisibleItemPosition()
                assertEquals("Activity重建不能改变上翻锚点像素偏移",anchorOffset,layout.getDecoratedTop(requireNotNull(layout.findViewByPosition(position)))-recycler.paddingTop)
                assertEquals("普通输入草稿重建后保持", "recreate draft retained",activity.findViewById<EditText>(R.id.agent_chat_input).text.toString())
                val output=File(app.getExternalFilesDir(null),"performance/chat-rendering-device.json")
                val result=JSONObject(output.readText()).put("activityRecreatedAnchorPreserved",true).put("activityRecreatedDraftPreserved",true).put("programmaticAnchorCheckpointed",true).put("anchorOffset",anchorOffset)
                output.writeText(result.toString(2))
            }
            // 离开时原本跟随底部，薄状态必须保存底部语义；历史首批恢复后仍跟随最新内容，而不是旧首行。
            scenario.onActivity{(recycler.layoutManager as LinearLayoutManager).scrollToPosition(adapter.itemCount-1)}
            await("底部跟随薄状态") {
                var ready=false
                scenario.onActivity{ready=!recycler.canScrollVertically(1) && model.uiState.value.scrollAnchorKey==null}
                ready
            }
            scenario.recreate()
            await("底部用户重建跟随真实末尾") {
                var ready=false
                scenario.onActivity { activity ->
                    val current=activity.findViewById<RecyclerView>(R.id.agent_chat_scroll)
                    val currentAdapter=current?.adapter as? AgentMessageAdapter
                    val layout=current?.layoutManager as? LinearLayoutManager
                    ready=currentAdapter!=null && layout!=null && currentAdapter.itemCount>=200 && layout.findFirstVisibleItemPosition()>100 && !current.canScrollVertically(1) && model.uiState.value.scrollAnchorKey==null
                }
                ready
            }
            val output=File(app.getExternalFilesDir(null),"performance/chat-rendering-device.json")
            output.writeText(JSONObject(output.readText()).put("bottomFollowingCheckpointed",true).put("bottomRecreatedFollowsTail",true).toString(2))
        }
    }
    /** 后端或列表超时明确失败，不以生成测试替代设备实际运行。 */
    private fun await(description:String,condition:()->Boolean){val end=System.nanoTime()+30_000_000_000L;while(!condition()){check(System.nanoTime()<end){"等待失败：$description"};Thread.sleep(10)}}
}
