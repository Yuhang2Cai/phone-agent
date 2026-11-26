// 六类列表设备验收：真实HTTP读取合成30/100/500条，生产Adapter和原XML在真实Window中回收/更新/恢复。
package com.fandazi.app.feature.store

import android.os.*
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.deal.HttpGroupBuyingRepository
import com.fandazi.app.feature.home.CatalogState
import com.fandazi.app.feature.order.GroupOrderListAdapter
import com.fandazi.app.feature.voucher.HttpVoucherRepository
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 合成HTTP夹具与真实Spring流程分列；本测量无交易写入、模型或音频，不代表正式arm64三批性能。 */
@RunWith(AndroidJUnit4::class)
class BusinessListRecyclingTest {
    /** 一次运行六类×三种规模，检查只创建可见区域Holder，并保留端到端点击身份及局部差量。 */
    @Test fun allSixListsRecycleOrderedHttpSnapshotsAcrossThreeSizes() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val samples=JSONArray()
        ActivityScenario.launch(MainActivity::class.java).use { scenario->
            for(size in listOf(30,100,500)) {
                // 测试专用HTTP拦截只向外部夹具传规模，生产页面与ApiClient源码不增加测试开关。
                val client=OkHttpClient.Builder().addInterceptor{chain->chain.proceed(chain.request().newBuilder().url(chain.request().url.newBuilder().addQueryParameter("count",size.toString()).build()).build())}.build()
                val api=ApiClient("http://10.0.2.2:18089/api/",{null},client)
                val catalog=HttpCatalogRepository(api)
                val stores=read("门店",runBlocking{catalog.stores(CatalogQuery())})
                val dishes=read("菜品",runBlocking{catalog.dishes(STORE)})
                val reviews=read("评价",runBlocking{catalog.reviews(STORE)})
                val deals=read("套餐",runBlocking{HttpGroupBuyingRepository(api).loadStoreDeals(STORE)})
                val orders=read("订单",runBlocking{HttpVoucherRepository(api).listOrders()})
                listOf(stores.size,dishes.size,reviews.size,deals.size,orders.size).forEach{assertEquals(size,it)}
                for(kind in listOf("home","search","deals","dishes","reviews","orders")) {
                    lateinit var overlay:FrameLayout;lateinit var list:RecyclerView
                    lateinit var adapter:RecyclerView.Adapter<*>
                    lateinit var metrics:BusinessListMetrics
                    var clicked:String?=null
                    val frames=java.util.Collections.synchronizedList(mutableListOf<Long>())
                    val handlerThread=HandlerThread("业务列表帧测量").apply{start()}
                    val listener=Window.OnFrameMetricsAvailableListener{_,frame,_->frames.add(frame.getMetric(FrameMetrics.TOTAL_DURATION))}
                    val latch=CountDownLatch(1)
                    scenario.onActivity {activity->
                        overlay=FrameLayout(activity)
                        val native=when(kind){"home"->R.layout.fragment_home;"search"->R.layout.fragment_search;"orders"->R.layout.fragment_orders;else->R.layout.fragment_store}
                        val page=LayoutInflater.from(activity).inflate(native,overlay,false);overlay.addView(page)
                        list=page.findViewById(when(kind){"home"->R.id.home_store_list;"search"->R.id.search_results;"orders"->R.id.order_list;else->R.id.store_content})
                        list.layoutManager=LinearLayoutManager(activity)
                        adapter=when(kind) {
                            "home","search"->StoreListAdapter({store,_->clicked=store.publicId},{}) .also{metrics=it.metrics;it.submitState(CatalogState(stores=stores)){latch.countDown()}}
                            "orders"->GroupOrderListAdapter{id->clicked=id}.also{metrics=it.metrics;it.submitOrders(orders){latch.countDown()}}
                            else->StoreDetailListAdapter({},{},{},{},{deal->clicked=deal.publicId}).also{
                                metrics=it.metrics
                                val tab=when(kind){"deals"->StoreTab.DEALS;"dishes"->StoreTab.DISHES;else->StoreTab.REVIEWS}
                                val rows=when(kind){"deals"->deals.map{v->StoreDetailRow.Deal(v)};"dishes"->dishes.map{v->StoreDetailRow.Dish(v,STORE)};else->reviews.map{v->StoreDetailRow.Review(v)}}
                                it.submitState(StoreBrowseState(STORE,tab=tab,summary=stores.first(),summaryLoading=false,loading=false,rows=rows)){latch.countDown()}
                            }
                        }
                        list.adapter=adapter
                        activity.addContentView(overlay,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
                        activity.window.addOnFrameMetricsAvailableListener(listener,Handler(handlerThread.looper))
                    }
                    assertTrue("初次差量必须完成",latch.await(10,TimeUnit.SECONDS));awaitLayout(scenario,list)
                    var initialCreates=0L
                    scenario.onActivity{activity->
                        initialCreates=metrics.creates.sum();assertTrue("$kind/${size}不能一次创建全部业务行",initialCreates<size);assertTrue(list.childCount in 1..35)
                        // 与真实系统栏高度交叉核对，不能让滚动容器占用状态栏；XML占位也须满足当前设备。
                        val location=IntArray(2);list.getLocationOnScreen(location)
                        val safeTop=androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(activity.window.decorView.rootWindowInsets).getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
                        assertTrue("业务滚动容器不得进入状态栏区域",location[1]>=safeTop)
                    }
                    val started=System.nanoTime()
                    // 真实回收容器滚动到末段、返回中段及首段，窗口每段实际完成布局后检查附着行数量。
                    for(position in listOf(adapter.itemCount-1,adapter.itemCount/2,0)) {
                        scenario.onActivity{(list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position,0)}
                        awaitLayout(scenario,list);Thread.sleep(100)
                        scenario.onActivity{assertTrue(list.childCount<40);assertTrue("500行回收必须只创建有限骨架",metrics.creates.sum()<60)}
                    }
                    // 首行或第一业务行的点击返回原HTTP公开ID，不能残留已经回收过的末项闭包。
                    scenario.onActivity {
                        val position=when(kind){"reviews"->3;"deals","dishes"->2;else->0}
                        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(position,0)
                    }
                    awaitLayout(scenario,list)
                    scenario.onActivity {
                        val position=when(kind){"reviews"->3;"deals","dishes"->2;else->0}
                        val holder=requireNotNull(list.findViewHolderForAdapterPosition(position))
                        when(kind) {
                            "home","search"->{holder.itemView.performClick();assertEquals(STORE,clicked);assertEquals("合成门店001",holder.itemView.findViewById<TextView>(R.id.store_name).text.toString())}
                            "deals"->{holder.itemView.findViewById<View>(R.id.deal_action).performClick();assertEquals(DEAL,clicked)}
                            "orders"->{holder.itemView.performClick();assertEquals(ORDER,clicked)}
                            "dishes"->assertEquals("合成菜品001",holder.itemView.findViewById<TextView>(R.id.dish_title).text.toString())
                            "reviews"->assertEquals("合成评价001",holder.itemView.findViewById<TextView>(R.id.review_content).text.toString())
                        }
                    }
                    if(size==30) {
                        // 同一公开ID只有内容改变时只触发一行change；插入/删除/整表重建会违反明确断言。
                        var changes=0;var inserts=0;var removes=0;var fullRefreshes=0
                        val observer=object:RecyclerView.AdapterDataObserver() {
                            override fun onChanged(){fullRefreshes++}
                            override fun onItemRangeChanged(start:Int,count:Int,payload:Any?){changes+=count}
                            override fun onItemRangeInserted(start:Int,count:Int){inserts+=count}
                            override fun onItemRangeRemoved(start:Int,count:Int){removes+=count}
                        }
                        val changed=CountDownLatch(1)
                        scenario.onActivity {
                            adapter.registerAdapterDataObserver(observer)
                            when(val a=adapter) {
                                is StoreListAdapter->a.submitState(CatalogState(stores=stores.mapIndexed{index,value->if(index==0)value.copy(name="合成局部更新")else value})){changed.countDown()}
                                is GroupOrderListAdapter->a.submitOrders(orders.mapIndexed{index,value->if(index==0)value.copy(dealTitle="合成局部更新")else value}){changed.countDown()}
                                is StoreDetailListAdapter->{
                                    val tab=when(kind){"deals"->StoreTab.DEALS;"dishes"->StoreTab.DISHES;else->StoreTab.REVIEWS}
                                    val rows=when(kind){
                                        "deals"->deals.mapIndexed{index,value->StoreDetailRow.Deal(if(index==0)value.copy(title="合成局部更新")else value)}
                                        "dishes"->dishes.mapIndexed{index,value->StoreDetailRow.Dish(if(index==0)value.copy(name="合成局部更新")else value,STORE)}
                                        else->reviews.mapIndexed{index,value->StoreDetailRow.Review(if(index==0)value.copy(content="合成局部更新")else value)}
                                    }
                                    a.submitState(StoreBrowseState(STORE,tab=tab,summary=stores.first(),summaryLoading=false,loading=false,rows=rows)){changed.countDown()}
                                }
                            }
                        }
                        assertTrue(changed.await(10,TimeUnit.SECONDS));awaitLayout(scenario,list)
                        scenario.onActivity {
                            assertEquals("仅目标行应更新",1,changes);assertEquals(0,inserts);assertEquals(0,removes);assertEquals(0,fullRefreshes)
                            val position=when(kind){"reviews"->3;"deals","dishes"->2;else->0}
                            val holder=requireNotNull(list.findViewHolderForAdapterPosition(position))
                            val title=when(kind){"home","search"->R.id.store_name;"orders"->R.id.group_order_deal;"deals"->R.id.deal_title;"dishes"->R.id.dish_title;else->R.id.review_content}
                            assertEquals("合成局部更新",holder.itemView.findViewById<TextView>(title).text.toString())
                            // 回收释放必须立即移除点击和旧说明，之后完整绑定恢复同一公开ID的正确事实。
                            when(val a=adapter){is StoreListAdapter->a.release(holder);is StoreDetailListAdapter->a.release(holder);is GroupOrderListAdapter->a.release(holder)}
                            assertFalse(holder.itemView.hasOnClickListeners())
                            @Suppress("UNCHECKED_CAST")
                            (adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>).onBindViewHolder(holder,position)
                            if(kind in listOf("home","search","orders"))assertTrue(holder.itemView.hasOnClickListeners())
                            adapter.unregisterAdapterDataObserver(observer)
                        }
                    }
                    // 行键/像素恢复在同一后端集合上核对，预期键由已知测试资源字面量独立构造。
                    scenario.onActivity{(list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(10,-19)}
                    awaitLayout(scenario,list)
                    lateinit var anchor:ListAnchor
                    scenario.onActivity {
                        anchor=BusinessListAnchor.capture(list){position->when(val a=adapter){is StoreListAdapter->a.rowKey(position);is StoreDetailListAdapter->a.rowKey(position);is GroupOrderListAdapter->a.rowKey(position);else->null}}
                        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0,0)
                    }
                    awaitLayout(scenario,list)
                    scenario.onActivity {
                        val keys=when(val a=adapter){is StoreListAdapter->a.currentList.map{it.key};is StoreDetailListAdapter->a.currentList.map{it.key};is GroupOrderListAdapter->a.currentList.map{"order:${it.orderId}"};else->emptyList()}
                        BusinessListAnchor.restore(list,anchor,keys)
                    }
                    awaitLayout(scenario,list)
                    scenario.onActivity {
                        val restored=BusinessListAnchor.capture(list){position->when(val a=adapter){is StoreListAdapter->a.rowKey(position);is StoreDetailListAdapter->a.rowKey(position);is GroupOrderListAdapter->a.rowKey(position);else->null}}
                        assertEquals(anchor,restored);assertEquals(-19,restored.offset)
                        val actualFrames=synchronized(frames){frames.toList().sorted()}
                        fun percentile(p:Double):Double=if(actualFrames.isEmpty())0.0 else actualFrames[((actualFrames.size-1)*p).toInt()]/1_000_000.0
                        samples.put(JSONObject().put("kind",kind).put("rows",size).put("initialCreates",initialCreates).put("creates",JSONArray(metrics.creates.toList())).put("binds",JSONArray(metrics.binds.toList())).put("attached",list.childCount).put("windowFrameSamples",actualFrames.size).put("frameP50Ms",percentile(.5)).put("frameP95Ms",percentile(.95)).put("frameP99Ms",percentile(.99)).put("pssKb",Debug.getPss()).put("elapsedMs",(System.nanoTime()-started)/1_000_000.0).put("anchorRestored",true))
                        it.window.removeOnFrameMetricsAvailableListener(listener)
                        // 清除附着图片及事件，再移走测试覆盖层；没有改变正式Fragment的生产仓库。
                        for(i in 0 until list.childCount)when(val a=adapter){is StoreListAdapter->a.release(list.getChildViewHolder(list.getChildAt(i)));is StoreDetailListAdapter->a.release(list.getChildViewHolder(list.getChildAt(i)));is GroupOrderListAdapter->a.release(list.getChildViewHolder(list.getChildAt(i)))}
                        list.adapter=null;list.recycledViewPool.clear();(overlay.parent as ViewGroup).removeView(overlay)
                    }
                    handlerThread.quitSafely();handlerThread.join(1000)
                }
            }
        }
        val output=File(context.getExternalFilesDir(null),"performance/business-lists-device.json");output.parentFile!!.mkdirs()
        output.writeText(JSONObject().put("samples",samples).put("scope","合成HTTP边界/生产Adapter/原XML/x86_64调试单批，非正式arm64性能收益").put("realReviewContract","真实Spring仍为最近20条，不把500条夹具当新分页能力").put("asr","未运行").toString(2))
    }
    /** 实际Window布局后才读Holder及像素，waitForIdle不能单独代替下一帧布局完成。 */
    private fun awaitLayout(scenario:ActivityScenario<MainActivity>,list:RecyclerView) {
        val deadline=SystemClock.uptimeMillis()+10000
        while(SystemClock.uptimeMillis()<deadline){var ready=false;scenario.onActivity{ready=!list.isComputingLayout && !list.isLayoutRequested && list.childCount>0};if(ready){Thread.sleep(60);return};Thread.sleep(30)}
        error("有界列表在10秒内未完成布局")
    }
    /** 测试边界只显示错误类别/无敏感夹具URL，禁止失败强转Success掩盖真实网络与解析原因。 */
    private fun <T> read(name:String,result:ApiResult<List<T>>):List<T> = when(result) {
        is ApiResult.Success->result.value
        is ApiResult.Failure->error("合成HTTP$name 未成功：${result.error}")
    }
    companion object {
        /** 合成测试资源字面量独立于生产身份工具，点击断言不能由被测代码计算期望。 */
        const val STORE="00000000-0000-0000-0000-000000000001"
        const val DEAL="00000000-0000-0000-0000-000000010001"
        const val ORDER="00000000-0000-0000-0000-000000040001"
    }
}
