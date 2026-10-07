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
            val request = chain.request().newBuilder().apply {
                tokenProvider()?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
            }.build()
            chain.proceed(request)
        }
        .build()

    /** 共享普通HTTP生命周期，保留同一客户端认证/超时；GET重试仍由request决定。 */
    private val executor = CoroutineCallExecutor(client, onUnauthorized)

// 获取当前数据。
    suspend fun get(path: String): ApiResult<String> = request(
        Request.Builder().url(url(path)).get().build(),
        retryOnce = true
    )

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
        val firstResult = executor.execute(request)
        // 状态检查先于重试判断：取消后的网络回调不能创建新请求，HTTP错误也不触发自动重试。
        currentCoroutineContext().ensureActive()
        if (retryOnce && firstResult is ApiResult.Failure && firstResult.error.isRetryableNetworkFailure()) {
            executor.execute(request)
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
