// 真实网络门禁回归：公开只读允许恢复探测，离线业务POST/PUT/DELETE拒绝且没有恢复重放。
package com.fandazi.app.core.network
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Assert.*
import java.io.IOException
class OfflineWriteInterceptorTest {
    /** 各写方法都必须在发包前失败，服务端不能取得报价、订单或确认凭证。 */
    @Test fun offlineMutationsNeverReachServer(){MockWebServer().use{server->server.start();server.enqueue(MockResponse().setBody("{}"));val client=OkHttpClient.Builder().addInterceptor(OfflineWriteInterceptor(canWrite={false})).build();for(method in listOf("POST","PUT","DELETE")){val request=Request.Builder().url(server.url("/api/orders")).method(method,if(method=="DELETE")null else "{}".toRequestBody()).build();try{client.newCall(request).execute().close();fail("离线写请求必须在发包前拒绝")}catch(expected:IOException){assertTrue(expected.message.orEmpty().contains("离线"))}};assertEquals(0,server.requestCount)}}
    /** 恢复只执行明确的新GET/POST，不会将刚才拒绝的旧POST排队再次发出。 */
    @Test fun restoredNetworkDoesNotReplayOldWrite(){MockWebServer().use{server->server.start();repeat(3){server.enqueue(MockResponse().setBody("{}"))};var online=false;val client=OkHttpClient.Builder().addInterceptor(OfflineWriteInterceptor(canWrite={online})).build();val old=Request.Builder().url(server.url("/api/orders")).post("{}".toRequestBody()).build();try{client.newCall(old).execute().close();fail("离线写请求必须拒绝")}catch(expected:IOException){assertEquals(0,server.requestCount)};online=true;client.newCall(Request.Builder().url(server.url("/api/stores")).build()).execute().close();assertEquals(1,server.requestCount);assertEquals("GET",server.takeRequest().method);client.newCall(old).execute().close();assertEquals(2,server.requestCount);assertEquals("POST",server.takeRequest().method)}}
    /** 后端读探测在业务门禁关闭时依然可发起，恢复不能依赖已被拒绝的交易请求。 */
    @Test fun readIsAllowedWhenOfflineGateClosed(){MockWebServer().use{server->server.start();server.enqueue(MockResponse().setBody("[]"));val client=OkHttpClient.Builder().addInterceptor(OfflineWriteInterceptor(canWrite={false})).build();client.newCall(Request.Builder().url(server.url("/api/stores")).build()).execute().close();assertEquals(1,server.requestCount)}}
}
