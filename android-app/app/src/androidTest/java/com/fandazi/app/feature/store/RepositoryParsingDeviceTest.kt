// 解析设备测量：本进程只读合成HTTP服务提供30/100/500固定载荷；不改变后端、身份或ASR。
package com.fandazi.app.feature.store

import android.os.Debug
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fandazi.app.core.network.ApiClient
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.io.File

/** 同APK/固定模拟器上记录总请求时延与进程分配，真实Gson记录解析线程；数字只作开发参考。 */
@RunWith(AndroidJUnit4::class)
class RepositoryParsingDeviceTest {
    /** 基线和增量使用同一测试与确定性载荷，输出原始5次样本，不以模拟器结果宣称真机性能。 */
    @Test fun recordFixedPayloadsAndActualParsingThread() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val expectedBackground=InstrumentationRegistry.getArguments().getString("expectedBackground","true").toBoolean()
        val server=ServerSocket(0);val body=AtomicReference("[]");val running=AtomicBoolean(true)
        val executor=Executors.newSingleThreadExecutor()
        // 测试服务只处理GET并完整写出固定UTF-8正文，不代理真实业务或写任何订单。
        executor.submit {
            while(running.get()) {
                try { server.accept().use { socket ->
                    val input=socket.getInputStream().bufferedReader()
                    require(input.readLine().startsWith("GET "))
                    while(!input.readLine().isNullOrEmpty()) { }
                    val bytes=body.get().toByteArray(Charsets.UTF_8)
                    socket.getOutputStream().apply {
                        write("HTTP/1.0 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                        write(bytes);flush()
                    }
                } } catch(closed:SocketException) { if(running.get()) throw closed }
            }
        }
        val records=JSONArray()
        try {
            val repository=HttpCatalogRepository(ApiClient("http://127.0.0.1:${server.localPort}/api/",{null}))
            val onMain=AtomicBoolean(false);val parseThread=AtomicReference("")
            val originalGson=Gson()
            val gson=GsonBuilder().registerTypeAdapter(CatalogStore::class.java,JsonDeserializer<CatalogStore> { json,_,_ ->
                onMain.set(Looper.myLooper()==Looper.getMainLooper());parseThread.set(Thread.currentThread().name)
                originalGson.fromJson(json,CatalogStore::class.java)
            }).create()
            repository.javaClass.getDeclaredField("gson").apply { isAccessible=true }.set(repository,gson)
            for(count in listOf(30,100,500)) {
                body.set(payload(count))
                // 两次预热再采5次，旧版与新版保持同一策略；包含HTTP、JSON和线程切换，不能称纯解析耗时。
                repeat(7) { iteration ->
                    val allocatedBefore=Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()
                    val start=System.nanoTime()
                    val result=runBlocking { withContext(Dispatchers.Main) { repository.stores(CatalogQuery()) } }
                    val elapsed=System.nanoTime()-start
                    val allocated=Debug.getRuntimeStat("art.gc.bytes-allocated").toLong()-allocatedBefore
                    val rows=(result as ApiResult.Success).value
                    assertEquals(count,rows.size);assertEquals("合成门店${count-1}",rows.last().name)
                    assertEquals("真实DTO解析线程不符合本次基线/增量预期",!expectedBackground,onMain.get())
                    if(iteration>=2) records.put(JSONObject().put("rows",count).put("iteration",iteration-2)
                        .put("elapsedNanos",elapsed).put("processAllocatedBytes",allocated).put("parseThread",parseThread.get()).put("parsedOnMain",onMain.get()))
                }
            }
            val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"performance").apply { mkdirs() }
            File(folder,"repository-parsing-device.json").writeText(JSONObject().put("expectedBackground",expectedBackground).put("samples",records).toString(2))
        } finally {
            running.set(false);server.close();executor.shutdownNow()
        }
    }
    /** 公开UUID按下标稳定生成，原始内容在两版本一致，未知事实保留null。 */
    private fun payload(count:Int)=(0 until count).joinToString(prefix="[",postfix="]") { index ->
        """{"publicId":"00000000-0000-0000-0000-${index.toString().padStart(12,'0')}","name":"合成门店$index","cityCode":"fixture","category":"火锅","averageSpendCents":3456,"reviewCount":0,"reviewDistribution":[0,0,0,0,0],"rating":null,"distanceMeters":null,"featuredDeal":null,"address":"合成地址","openingHours":"合成时间"}"""
    }
}
