// 统一发包前续期：普通HTTP和SSE共享身份仓库；认证调用独立绕过，业务写永不自动重放。
package com.fandazi.app.core.network
import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/** 刷新拥有独立调度器，业务异步名额全部在等待续期时，认证请求仍有名额可发出。 */
fun isolatedAuthenticationClient(base: okhttp3.OkHttpClient): okhttp3.OkHttpClient = base.newBuilder().dispatcher(okhttp3.Dispatcher()).build()

/** renew由唯一身份仓库提供；拦截器运行于OkHttp线程，不在UI线程同步等待网络。 */
class SessionRenewalInterceptor(private val renew: suspend (String?) -> ApiResult<String?>): Interceptor {
    /** 发包前获取可用凭据；401只允许GET/HEAD续期后读取一次，写请求保留原失败结果。 */
    override fun intercept(chain: Interceptor.Chain): Response {
        val original=chain.request()
        val path=original.url.encodedPath
        // 认证不能递归续期；公开读取不依赖长期会话，过期/离线身份不阻塞目录和餐图。
        val publicRead=original.method in setOf("GET","HEAD") && listOf("/api/stores","/api/deals","/api/merchants","/catalog-images/").any { path==it||path.startsWith(it+"/")||it.endsWith("/")&&path.startsWith(it) }
        if(path.startsWith("/api/auth/")||publicRead)return chain.proceed(original)
        if(chain.call().isCanceled())throw IOException("请求已取消")
        val token=usable(runBlocking {renew(null)})
        if(chain.call().isCanceled())throw IOException("请求已取消")
        val request=original.newBuilder().apply {if(token!=null)header("Authorization","Bearer $token") else removeHeader("Authorization")}.build()
        val response=chain.proceed(request)
        if(response.code!=401)return response
        val rejected=response.request.header("Authorization")?.takeIf {it.startsWith("Bearer ")}?.removePrefix("Bearer ") ?: return response
        val renewed=runBlocking {renew(rejected)}
        // 失败响应不关闭、不丢弃，让原执行器准确报告本次写请求结果；新的凭据只影响后续明确操作。
        if(original.method !in setOf("GET","HEAD")||renewed !is ApiResult.Success||renewed.value==null||renewed.value==rejected)return response
        response.close()
        if(chain.call().isCanceled())throw IOException("请求已取消")
        return chain.proceed(original.newBuilder().header("Authorization","Bearer ${renewed.value}").build())
    }
    /** 没有身份允许原鉴权流程处理；续期失败直接阻止发包，不能继续提交已知过期凭据。 */
    private fun usable(result: ApiResult<String?>): String? = when(result) {
        is ApiResult.Success -> result.value
        is ApiResult.Failure -> throw IOException("登录续期暂不可用，请联网后重试")
    }
}
