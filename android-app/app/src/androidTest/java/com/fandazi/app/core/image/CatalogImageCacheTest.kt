// 图片缓存设备测试：真实Glide与受控HTTP加载Task13授权照片，统计请求和字节，不构造假业务餐图。
package com.fandazi.app.core.image

import android.widget.ImageView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bumptech.glide.Glide
import com.fandazi.app.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.io.IOException
import android.graphics.drawable.BitmapDrawable
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import com.fandazi.app.feature.store.CatalogImages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.File

/** 使用实际ImageView尺寸和生命周期验证缓存；关闭缓存应触发暖加载请求数断言。 */
@RunWith(AndroidJUnit4::class)
class CatalogImageCacheTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()

    /** 冷加载一请求；内存命中与清内存后的磁盘命中都不再访问受控HTTP。 */
    @Test fun ordinaryPublicImageUsesMemoryAndDiskCache() {
        val bytes=instrumentation.context.assets.open("catalog-cache/vegetable-rice-de2d10fae70e.jpg").use { it.readBytes() }
        val server=PhotoServer(bytes)
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        lateinit var image:ImageView
        var created:ImageView?=null
        try {
            // 先清磁盘与内存形成真正冷态，视图固定100像素而非无界原图解码。
            Glide.get(instrumentation.targetContext).clearDiskCache()
            scenario.onActivity { activity ->
                Glide.get(activity).clearMemory()
                image=ImageView(activity);created=image
                activity.addContentView(image,FrameLayout.LayoutParams(100,100))
            }
            bind(image,server.url,"冷加载");awaitDescription(image,"冷加载")
            assertEquals("冷态必须访问真实HTTP一次",1,server.requests.get())
            bind(image,server.url,"内存命中");awaitDescription(image,"内存命中")
            assertEquals("普通公开图片暖态不得再次请求HTTP",1,server.requests.get())
            instrumentation.runOnMainSync { Glide.with(image).clear(image);Glide.get(image.context).clearMemory() }
            bind(image,server.url,"磁盘命中");awaitDescription(image,"磁盘命中")
            assertEquals("清内存后应命中磁盘，不能重新下载",1,server.requests.get())
            val record=JSONObject().put("scenario","public-cold-memory-disk").put("requests",server.requests.get()).put("responseBytes",server.responseBytes.get()).put("photoBytes",bytes.size)
            val photoB=instrumentation.context.assets.open("catalog-cache/tomato-egg-noodles-aaec774a72d9.jpg").use { it.readBytes() }
            // 同URL先用服务器版本一，再切换版本二，比较实际解码内容而非仅检查签名枚举。
            val versionOne=CatalogImageRequestOptions(resourceVersion="revision-one")
            val versionTwo=CatalogImageRequestOptions(resourceVersion="revision-two")
            bind(image,server.url,"版本一",versionOne);awaitDescription(image,"版本一")
            val firstFingerprint=fingerprint(image)
            server.body.set(photoB)
            bind(image,server.url,"版本二",versionTwo);awaitDescription(image,"版本二")
            val secondFingerprint=fingerprint(image)
            assertNotEquals("新版本必须显示另一张真实餐图",firstFingerprint,secondFingerprint)
            assertEquals(3,server.requests.get())
            bind(image,server.url,"版本二暖态",versionTwo);awaitDescription(image,"版本二暖态")
            assertEquals("同版本不得每次生成随机键",3,server.requests.get())
            record.put("versionRequests",2).put("versionContentChanged",true)

            // 真实503失败保持单图监听；用户点击后才发同URL请求，不触发元数据刷新或自动循环。
            server.status.set(503)
            val retryUrl=server.url+"?case=failure"
            bind(image,retryUrl,"失败恢复");awaitDescription(image,"图片加载失败，点击重试")
            val failureCount=server.requests.get()
            Thread.sleep(100);assertEquals("失败不能自动循环请求",failureCount,server.requests.get())
            server.status.set(200)
            instrumentation.runOnMainSync { assertTrue(image.performClick()) }
            awaitDescription(image,"失败恢复")
            assertEquals("用户点击仅重试这一个资源",failureCount+1,server.requests.get())
            record.put("failureThenRetryRequests",2)

            // 先有公共缓存，再以两个合成账户读取同URL；私人访问必须看到网络当前内容且不共用缓存。
            server.body.set(bytes)
            val beforePrivate=server.requests.get()
            bind(image,server.url,"账户甲",CatalogImageRequestOptions(access=CatalogImageAccess.Private("fixture-a")))
            awaitDescription(image,"账户甲");assertEquals(firstFingerprint,fingerprint(image))
            server.body.set(photoB)
            bind(image,server.url,"账户乙",CatalogImageRequestOptions(access=CatalogImageAccess.Private("fixture-b")))
            awaitDescription(image,"账户乙");assertEquals(secondFingerprint,fingerprint(image))
            assertEquals("两个账户不得复用公共或其他账户缓存",beforePrivate+2,server.requests.get())
            record.put("privateAccountRequests",2)

            // 旧图响应故意迟到，再把同一视图绑定新图；旧请求取消后不得覆盖新条目。
            server.body.set(bytes);server.delayMillis.set(250)
            val beforeReuse=server.requests.get()
            bind(image,server.url+"?case=late-old","旧图迟到")
            val requestDeadline=System.nanoTime()+5_000_000_000L
            while(server.requests.get()==beforeReuse && System.nanoTime()<requestDeadline) Thread.sleep(10)
            assertEquals(beforeReuse+1,server.requests.get())
            server.body.set(photoB);server.delayMillis.set(0)
            bind(image,server.url+"?case=new-holder","新条目")
            awaitDescription(image,"新条目");Thread.sleep(300)
            awaitDescription(image,"新条目");assertEquals(secondFingerprint,fingerprint(image))
            record.put("lateOldImageIgnored",true)

            // 缺图仅刷新最后绑定条目的元数据；旧点击监听不能在复用后执行。
            var oldRefresh=0;var newRefresh=0
            instrumentation.runOnMainSync {
                AfterSaleImageLoader.load(image,null,"旧条目") { oldRefresh++ }
                AfterSaleImageLoader.load(image,null,"新条目") { newRefresh++ }
                assertTrue(image.performClick())
            }
            assertEquals(0,oldRefresh);assertEquals(1,newRefresh)
            bind(image,server.url,"复用成功",versionTwo);awaitDescription(image,"复用成功")
            instrumentation.runOnMainSync { assertFalse(image.isClickable) }
            record.put("metadataRefreshes",1).put("staleMetadataRefreshes",0)

            // 局部元数据正在读取时复用视图，新身份令牌必须丢弃晚到元数据。
            val metadataStarted=AtomicInteger(0)
            val metadataCancelled=AtomicBoolean(false)
            val releaseMetadata=CompletableDeferred<Unit>()
            instrumentation.runOnMainSync {
                CatalogImageMetadataRefresh.bind(image,null,"旧元数据") {
                    metadataStarted.incrementAndGet()
                    try { releaseMetadata.await() }
                    finally { if(!currentCoroutineContext().isActive) metadataCancelled.set(true) }
                    server.url to "迟到元数据"
                }
                assertTrue(image.performClick())
            }
            val metadataDeadline=System.nanoTime()+5_000_000_000L
            while(metadataStarted.get()==0 && System.nanoTime()<metadataDeadline) Thread.sleep(10)
            assertEquals(1,metadataStarted.get())
            instrumentation.runOnMainSync {
                CatalogImageMetadataRefresh.bind(image,server.url+"?case=current","当前元数据") { fail("有效当前图片不能触发元数据读取");null }
            }
            awaitDescription(image,"当前元数据");releaseMetadata.complete(Unit);Thread.sleep(150)
            awaitDescription(image,"当前元数据")
            assertTrue("视图复用必须取消旧元数据任务",metadataCancelled.get())
            record.put("lateOldMetadataIgnored",true)
            record.put("oldMetadataRequestCancelled",true)

            // 缩略图保留实际视图尺寸与默认缓存；同一签名可复用大图缓存，灰色占位滤镜在成功时清除。
            val beforeThumb=server.requests.get()
            instrumentation.runOnMainSync { CatalogImages.thumbnail(image,server.url,"真实缩略图",versionTwo) { fail("真实图片不能刷新元数据") } }
            awaitDescription(image,"真实缩略图")
            instrumentation.runOnMainSync { assertNull(image.imageTintList);assertFalse(image.isClickable) }
            assertEquals(beforeThumb,server.requests.get())
            record.put("thumbnailAdditionalRequests",0)
            // 已测到实际解码尺寸在视图预算内，保持Glide按ImageView尺寸解码，不额外override。
            instrumentation.runOnMainSync {
                val bitmap=(image.drawable as BitmapDrawable).bitmap
                assertTrue("解码宽度不得超出视图预算",bitmap.width<=100)
                assertTrue("解码高度不得超出视图预算",bitmap.height<=100)
                record.put("decodedWidth",bitmap.width).put("decodedHeight",bitmap.height).put("decodedBytes",bitmap.allocationByteCount)
            }
            record.put("totalRequests",server.requests.get()).put("totalResponseBytes",server.responseBytes.get())
            File(instrumentation.targetContext.getExternalFilesDir(null),"performance").apply { mkdirs() }.resolve("image-cache-device.json").writeText(record.toString(2))
        } finally {
            instrumentation.runOnMainSync { created?.let { Glide.with(it).clear(it) } }
            scenario.close();server.close()
        }
    }

    /** 仅在主线程触发正式加载器，其他字段与业务状态不进入图片测试。 */
    private fun bind(image:ImageView,url:String,alt:String,options:CatalogImageRequestOptions=CatalogImageRequestOptions()) {
        instrumentation.runOnMainSync { AfterSaleImageLoader.load(image,url,alt,options) { fail("有效照片不能触发元数据刷新") } }
    }

    /** 对实际显示的解码像素计算摘要，证明版本更新与账号访问显示当前内容，不输出图片正文。 */
    private fun fingerprint(image:ImageView):String {
        var bytes=byteArrayOf()
        instrumentation.runOnMainSync {
            val output=ByteArrayOutputStream()
            (image.drawable as BitmapDrawable).bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)
            bytes=output.toByteArray()
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /** 等待正式成功无障碍描述；超时保留实际状态，避免把占位当加载成功。 */
    private fun awaitDescription(image:ImageView,expected:String) {
        val end=System.nanoTime()+10_000_000_000L
        var actual=""
        while(System.nanoTime()<end) {
            instrumentation.runOnMainSync { actual=image.contentDescription?.toString().orEmpty() }
            if(actual==expected) return
            Thread.sleep(25)
        }
        assertEquals("真实Glide加载未完成",expected,actual)
    }

    /** 测试HTTP只提供Task13原始JPEG；每连接记录真实请求与实际响应体字节。 */
    private class PhotoServer(bytes:ByteArray):AutoCloseable {
        private val socket=ServerSocket(0)
        private val running=AtomicBoolean(true)
        private val executor=Executors.newSingleThreadExecutor()
        val requests=AtomicInteger(0)
        val responseBytes=AtomicInteger(0)
        val body=AtomicReference(bytes)
        val status=AtomicInteger(200)
        val delayMillis=AtomicInteger(0)
        val url="http://127.0.0.1:${socket.localPort}/catalog-images/v1/thumb/vegetable-rice-de2d10fae70e.jpg"
        init {
            executor.submit {
                while(running.get()) {
                    try { socket.accept().use { client ->
                        val input=client.getInputStream().bufferedReader()
                        require(input.readLine().startsWith("GET "))
                        while(!input.readLine().isNullOrEmpty()) { }
                        requests.incrementAndGet()
                        val response=if(status.get()==200) body.get() else byteArrayOf()
                        // 只延迟本测试照片以复现Holder复用，不延迟真实业务服务或改变数据库。
                        val delay=delayMillis.get();if(delay>0) Thread.sleep(delay.toLong())
                        client.getOutputStream().apply {
                            write("HTTP/1.0 ${status.get()} Test\r\nContent-Type: image/jpeg\r\nContent-Length: ${response.size}\r\n\r\n".toByteArray())
                            write(response);flush();responseBytes.addAndGet(response.size)
                        }
                    } } catch(error:IOException) { if(running.get() && error !is SocketException) throw error }
                }
            }
        }
        /** 只关闭本测试自己的监听与线程，不触碰业务服务。 */
        override fun close() { running.set(false);socket.close();executor.shutdownNow() }
    }
}
