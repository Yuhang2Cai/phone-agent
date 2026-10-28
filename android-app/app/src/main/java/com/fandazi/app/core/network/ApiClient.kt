// 网络客户端：统一管理服务地址、认证与请求配置。
package com.fandazi.app.core.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.TimeUnit

sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

sealed interface ApiError {
    data class Http(val code: Int, val message: String) : ApiError
    data class Network(val message: String) : ApiError
    data object Timeout : ApiError
    data object Storage : ApiError
}

/** 普通业务API入口；公开get/post等签名保留，认证在实际发包时附加，取消/读体/失效由统一执行器管理。 */
class ApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    okHttpClient: OkHttpClient? = null,
    /** 仅实际请求凭据的401通知身份源，默认空回调保持旧构造入口兼容；403不失效。 */
    private val onUnauthorized: suspend (String) -> Unit = {}
) {
    private val client = (okHttpClient ?: OkHttpClient()).newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            // 活动请求携带原生会话护栏：在真正发包（含续期后的重发）前重新核对身份。
            // 护栏抛出的 IOException 交给统一执行器转为失败，绝不能回退到新账号凭据。
            val bound = chain.request().tag(BoundCredential::class.java)
            val token = if (bound != null) bound.resolve() else tokenProvider()
            val request = chain.request().newBuilder().apply {
                if (bound != null) removeHeader("Authorization")
                token?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
            }.build()
            chain.proceed(request)
        }
        .build()

    /** 共享普通HTTP生命周期，保留同一客户端认证/超时；GET重试仍由request决定。 */
    private val executor = CoroutineCallExecutor(client, onUnauthorized)
    /** 活动桥需要服务端稳定 reasonCode；只对显式绑定请求保留 JSON，其余原生页面沿用文本提示。 */
    private val boundExecutor = CoroutineCallExecutor(client, onUnauthorized, errorMessage = { body, _ -> body })

// 获取当前数据。
    suspend fun get(path: String): ApiResult<String> = request(
        Request.Builder().url(url(path)).get().build(),
        retryOnce = true
    )

    /** 会话绑定读取；credential 在发包线程核验身份并返回当前凭据，换号时应抛 IOException。 */
    suspend fun getBound(path: String, credential: () -> String?): ApiResult<String> = request(
        Request.Builder().url(url(path)).get().tag(BoundCredential::class.java, BoundCredential(credential)).build(),
        retryOnce = true
    )

    /** 会话绑定写；保留调用方幂等键，不自动重试，凭据只存在于原生请求标签中。 */
    suspend fun postJsonBound(path: String, json: String, headers: Map<String, String>, credential: () -> String?): ApiResult<String> = request(
        Request.Builder().url(url(path)).headers(headers.toHeaders())
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .tag(BoundCredential::class.java, BoundCredential(credential)).build(),
        retryOnce = false
    )

    /** 仅供 OkHttp 拦截器读取的会话校验器，不序列化、不进入日志或 JS。 */
    private class BoundCredential(val resolve: () -> String?)

// 处理 `postJson` 相关逻辑。
    suspend fun postJson(path: String, json: String): ApiResult<String> = request(
        Request.Builder()
            .url(url(path))
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build(),
        retryOnce = false
    )

// 处理 `postJson` 相关逻辑。
    suspend fun postJson(path: String, json: String, headers: Map<String, String>): ApiResult<String> = request(
        Request.Builder()
            .url(url(path))
            .headers(headers.toHeaders())
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build(),
        retryOnce = false
    )

// 处理 `putJson` 相关逻辑。
    suspend fun putJson(path: String, json: String): ApiResult<String> = request(
        Request.Builder()
            .url(url(path))
            .put(json.toRequestBody(JSON_MEDIA_TYPE))
            .build(),
        retryOnce = false
    )

// 删除当前数据。
    suspend fun delete(path: String): ApiResult<String> = request(
        Request.Builder().url(url(path)).delete().build(),
        retryOnce = false
    )

// 处理 `patch` 相关逻辑。
    suspend fun patch(path: String, json: String): ApiResult<String> = request(
        Request.Builder().url(url(path)).patch(json.toRequestBody(JSON_MEDIA_TYPE)).build(),
        retryOnce = false
    )

    /** 一次普通请求；GET仅网络失败可重试一次，任务取消必须在重试前传播，写请求不自动重试。 */
    private suspend fun request(request: Request, retryOnce: Boolean): ApiResult<String> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        // 按原生请求标签选择错误格式，不让 RN 的结构化契约改变既有页面的用户提示。
        val transport = if (request.tag(BoundCredential::class.java) != null) boundExecutor else executor
        val firstResult = transport.execute(request)
        // 状态检查先于重试判断：取消后的网络回调不能创建新请求，HTTP错误也不触发自动重试。
        currentCoroutineContext().ensureActive()
        if (retryOnce && firstResult is ApiResult.Failure && firstResult.error.isRetryableNetworkFailure()) {
            transport.execute(request)
        } else firstResult
    }

// 处理 `url` 相关逻辑。
    private fun url(path: String) = requireNotNull(baseUrl.toHttpUrl().resolve(path)) {
        "Unable to resolve API path: $path"
    }

    private fun Map<String, String>.toHeaders() = okhttp3.Headers.Builder().also { builder ->
        forEach { (name, value) -> builder.set(name, value) }
    }.build()

    private fun ApiError.isRetryableNetworkFailure(): Boolean = this is ApiError.Network || this is ApiError.Timeout

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 20L
        const val WRITE_TIMEOUT_SECONDS = 20L
    }
}
