// 秒杀真机性能验收：在真实页面、真实认证和隔离 HTTP/MySQL 上测量开抢边界与受影响页面帧数据。
package com.fandazi.app.feature.seckill

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.NavHostFragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 只在显式传入 trueDeviceSeckill=true 时运行，防止日常测试误用真机、真实隔离库或延长构建时间。
 * 测试只读取活动和资格，不点击购买按钮，不生成报价、订单或支付事实。
 */
@RunWith(AndroidJUnit4::class)
class SeckillTrueDevicePerformanceTest {
    /**
     * 测量单轮“服务端开抢时刻 → 页面按钮可点击”的延迟。
     * 宿主机在运行前把活动设为未来时刻；本测试必须先观察到禁用预告态，再接受开放态。
     */
    @Test
    fun measureSingleBoundaryRound() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("trueDeviceSeckill")=="true")
        val activityId=requireNotNull(arguments.getString("activityId")) {"缺少公开活动ID"}
        val round=requireNotNull(arguments.getString("round")) {"缺少轮次"}.toInt()
        require(round in 1..100) {"轮次超出验收范围"}

        val activity=launchActivity()
        try {
            openDetail(activity,activityId)
            // 输入校验：必须先看到真实预告态，避免启动太晚后把直接打开 LIVE 误报为边界达标。
            waitFor(activity,15_000) {screen->
                val button=screen.findViewById<TextView>(R.id.sk_primary)
                button?.text?.toString()=="即将开抢" && !button.isEnabled
            }
            var startsAt:Instant?=null
            onMain {
                val model=ViewModelProvider(detail(activity))[SeckillViewModel::class.java]
                startsAt=model.flow.state.value.activity?.startsAt
            }
            val expectedStart=requireNotNull(startsAt) {"预告态缺少权威开抢时刻"}

            // 规则判断：仅观察控件和同一流程的服务器时间锚点；不以手机墙上时间估算服务端边界。
            val observedServerTime=AtomicReference<Instant?>(null)
            val observedElapsed=AtomicLong(0L)
            var anchoredNow:Instant?=null
            onMain {
                val model=ViewModelProvider(detail(activity))[SeckillViewModel::class.java]
                anchoredNow=model.flow.clock.now(SystemClock.elapsedRealtime())
            }
            // 超时预算必须包含仍未走完的预告倒计时；此前固定15秒会在开抢前1.312秒提前失败。
            val remainingUntilStart=Duration.between(requireNotNull(anchoredNow),expectedStart).toMillis().coerceAtLeast(0)
            val opened=CountDownLatch(1)
            lateinit var watcher:TextWatcher
            onMain {
                val button=activity.findViewById<TextView>(R.id.sk_primary)
                val model=ViewModelProvider(detail(activity))[SeckillViewModel::class.java]
                fun captureAfterRender() {
                    // 文本在 enabled 之前赋值；post 到下一主线程消息，确保读取的是同一轮 render 完整结果。
                    button.post {
                        if(button.isEnabled && button.text.toString().startsWith("立即抢购")) {
                            val elapsed=SystemClock.elapsedRealtime()
                            val serverTime=model.flow.clock.now(elapsed)
                            if(serverTime!=null && observedServerTime.compareAndSet(null,serverTime)) {
                                observedElapsed.set(elapsed)
                                opened.countDown()
                            }
                        }
                    }
                }
                watcher=object:TextWatcher {
                    override fun beforeTextChanged(value:CharSequence?,start:Int,count:Int,after:Int)=Unit
                    override fun onTextChanged(value:CharSequence?,start:Int,before:Int,count:Int)=captureAfterRender()
                    override fun afterTextChanged(value:Editable?)=Unit
                }
                button.addTextChangedListener(watcher)
                captureAfterRender()
            }
            val completed=opened.await(remainingUntilStart+15_000,TimeUnit.MILLISECONDS)
            onMain {activity.findViewById<TextView>(R.id.sk_primary).removeTextChangedListener(watcher)}
            if(!completed)writeBoundaryDiagnostic(activity,expectedStart,round)
            val observed=requireNotNull(observedServerTime.get()) {"开抢后15秒内按钮仍未开放"}
            val latency=Duration.between(expectedStart,observed).toMillis()

            // 结果构造：每轮独立文件保留原始时间，汇总时不得删除慢样本或只选最佳轮次。
            val evidence=JSONObject()
                .put("round",round)
                .put("activityId",activityId)
                .put("serverStart",expectedStart.toString())
                .put("observedServerTime",observed.toString())
                .put("observedElapsedRealtimeMs",observedElapsed.get())
                .put("latencyMs",latency)
                .put("buttonTextPrefix","立即抢购")
                .put("buttonEnabled",true)
                .put("scope","真机实际页面、真实认证、真实隔离HTTP/MySQL；只读活动与资格")
            output("seckill-boundary-${round.toString().padStart(2,'0')}.json").writeText(evidence.toString(2))
            assertTrue("按钮不能早于服务器开抢时刻开放：${latency}ms",latency>=0)
            assertTrue("按钮开放延迟必须不超过1000ms：${latency}ms",latency<=1_000)
        } finally {
            onMain {activity.finish()}
        }
    }

    /**
     * 在活动详情与门店活动列表各执行60次真实触摸滚动，并保留 gfxinfo 全量帧数据。
     * 解析与阈值断言由宿主汇总器完成，使原始帧行可供复核且不在设备端选择性过滤。
     */
    @Test
    fun collectAffectedPageFrames() {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("trueDeviceSeckill")=="true")
        val activityId=requireNotNull(arguments.getString("activityId")) {"缺少公开活动ID"}
        val storeId=requireNotNull(arguments.getString("storeId")) {"缺少公开门店ID"}
        val operations=JSONArray()

        val activity=launchActivity()
        try {
            openDetail(activity,activityId)
            waitFor(activity,15_000) {screen->
                val button=screen.findViewById<TextView>(R.id.sk_primary)
                button?.isEnabled==true && button.text.toString().startsWith("立即抢购")
            }
            shell("dumpsys gfxinfo com.fandazi.app reset")
            // 详情页与目录页各60次，覆盖倒计时、长说明、库存及活动卡片布局；手势不触碰购买按钮。
            performScrolls("detail",1,60,operations)
            onMain {host(activity).navController.navigate(R.id.seckillStoreFragment,bundleOf("storePublicId" to storeId))}
            waitFor(activity,15_000) {screen->screen.findViewById<View>(R.id.sk_activity_list)!=null}
            performScrolls("store-list",61,120,operations)
            val raw=shell("dumpsys gfxinfo com.fandazi.app framestats")
            assertTrue("gfxinfo必须返回帧列定义",raw.contains("IntendedVsync") && raw.contains("FrameCompleted"))
            assertTrue("必须保留120次操作样本",operations.length()==120)
            output("seckill-true-device-framestats.txt").writeText(raw)
            output("seckill-true-device-operations.json").writeText(
                JSONObject().put("count",operations.length()).put("operations",operations)
                    .put("scope","秒杀详情60次、门店秒杀目录60次；真机实际触摸注入").toString(2),
            )
        } finally {
            onMain {activity.finish()}
        }
    }

    /** 显式启动目标 Activity；绕开部分真机厂商系统无法让 ActivityScenario 恢复前台的生命周期拦截。 */
    private fun launchActivity():MainActivity {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val intent=Intent(instrumentation.targetContext,MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        return instrumentation.startActivitySync(intent) as MainActivity
    }

    /** 打开实际秒杀详情；导航参数只有公开活动ID，不注入金额、状态或资格。 */
    private fun openDetail(activity:MainActivity,activityId:String) {
        onMain {host(activity).navController.navigate(R.id.seckillFragment,bundleOf("activityId" to activityId))}
    }

    /** 在主线程读取当前详情 Fragment；找不到即代表导航结构与验收前提不一致。 */
    private fun detail(activity:MainActivity):SeckillFragment {
        return host(activity).childFragmentManager.primaryNavigationFragment as? SeckillFragment
            ?: error("当前页面不是秒杀详情")
    }

    /** 轮询真实主线程控件；超时直接失败，不通过延长等待或重试隐藏慢页面。 */
    private fun waitFor(activity:MainActivity,timeoutMs:Long,condition:(MainActivity)->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+timeoutMs
        while(SystemClock.elapsedRealtime()<deadline) {
            var ready=false
            onMain {ready=condition(activity)}
            if(ready)return
            SystemClock.sleep(20)
        }
        fail("真实页面未在${timeoutMs}ms内达到指定状态")
    }

    /**
     * 仅在边界超时失败时保存各层只读状态，定位是时钟、刷新、HTTP还是控件绑定未推进。
     * 错误只保存类型，不写响应正文、账号、令牌或其他用户数据。
     */
    private fun writeBoundaryDiagnostic(activity:MainActivity,expectedStart:Instant,round:Int) {
        var diagnostic:JSONObject?=null
        onMain {
            val model=ViewModelProvider(detail(activity))[SeckillViewModel::class.java]
            val state=model.flow.state.value
            val button=activity.findViewById<TextView>(R.id.sk_primary)
            diagnostic=JSONObject()
                .put("round",round)
                .put("expectedStart",expectedStart.toString())
                .put("clockNow",model.flow.clock.now(SystemClock.elapsedRealtime())?.toString())
                .put("activityState",state.activity?.state)
                .put("activityVersion",state.activity?.version)
                .put("eligibilityState",state.eligibility?.state)
                .put("eligibilityVersion",state.eligibility?.version)
                .put("synchronized",state.synchronized)
                .put("busy",state.busy)
                .put("errorType",state.error?.javaClass?.simpleName)
                .put("buttonText",button?.text?.toString())
                .put("buttonEnabled",button?.isEnabled)
        }
        output("seckill-boundary-diagnostic-${round.toString().padStart(2,'0')}.json")
            .writeText(requireNotNull(diagnostic).toString(2))
    }

    /** 所有 View、Fragment 与 ViewModel 读取均切回主线程，避免测量器自身制造并发异常。 */
    private fun onMain(action:()->Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    }

    /** 注入固定时长触摸并记录每次单调时钟，不读取或修改业务状态。 */
    private fun performScrolls(page:String,first:Int,last:Int,operations:JSONArray) {
        for(index in first..last) {
            val upward=index%2==1
            val started=SystemClock.elapsedRealtime()
            val command=if(upward)"input swipe 720 2360 720 1160 80" else "input swipe 720 1160 720 2360 80"
            shell(command)
            val ended=SystemClock.elapsedRealtime()
            operations.put(JSONObject().put("index",index).put("page",page).put("direction",if(upward)"up" else "down")
                .put("startedElapsedRealtimeMs",started).put("endedElapsedRealtimeMs",ended))
            SystemClock.sleep(20)
        }
    }

    /** 通过测试自动化的 shell 身份执行只读诊断或触摸；描述符必须关闭，避免120轮累积泄漏。 */
    private fun shell(command:String):String {
        val descriptor=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use {it.readText()}
    }

    /** 所有证据只写应用外部测试目录，内容不含账号、令牌、报价授权或订单事实。 */
    private fun output(name:String)=File(
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
        "performance/$name",
    ).apply {parentFile!!.mkdirs()}

    /** 读取正式 NavHost，不给产品增加测试专用入口。 */
    private fun host(activity:MainActivity)=activity.supportFragmentManager
        .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
}
