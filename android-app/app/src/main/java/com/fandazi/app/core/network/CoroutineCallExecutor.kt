// 可取消普通HTTP执行器：统一桥接协程与OkHttp生命周期，供目录/订单和助手普通请求复用；SSE独立管理。
package com.fandazi.app.core.network

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 普通请求的唯一生命周期边界。client保留调用方认证/超时设置，errorMessage和networkError保留各入口原分类。
 * onUnauthorized接收实际已发送凭据且只在401触发；回调必须自行核对当前凭据，不能注销迟到响应后的新登录。
 * 不提供自动重试；仅ApiClient对GET决定一次重试，写请求和SSE不共用该决策。
 */
internal class CoroutineCallExecutor(
    private val client: OkHttpClient,
    private val onUnauthorized: suspend (String) -> Unit = {},
    private val errorMessage: (String, String) -> String = ::defaultErrorMessage,
    private val networkError: (IOException) -> ApiError = { error ->
        if (error is SocketTimeoutException) ApiError.Timeout
        else ApiError.Network(error.message ?: "Network request failed")
    }
) {
    /** 发起一次请求并读完整响应体，返回原API结果；取消始终向调用方传播且实际取消Call，响应体在回调中关闭。 */
    suspend fun execute(request: Request): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            // 输入校验：进入IO及创建Call前检查取消，注册取消处理后才enqueue，覆盖启动前/排队时竞争。
            currentCoroutineContext().ensureActive()
            val wire = awaitResponse(request)
            currentCoroutineContext().ensureActive()
            // 身份判断：凭据来自实际response.request，包括拦截器添加的值；403和无凭据401不触发全局失效。
            if (wire.code == 401) wire.credential?.takeIf(String::isNotBlank)?.let { onUnauthorized(it) }
            currentCoroutineContext().ensureActive()
            // 结果构造：消费结束后再次检查取消，旧页面只停止等待，不意味着服务器已回滚任何写入。
            if (wire.successful) ApiResult.Success(wire.body)
            else ApiResult.Failure(ApiError.Http(wire.code, errorMessage(wire.body, wire.message), businessReason(wire.body)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            currentCoroutineContext().ensureActive()
            ApiResult.Failure(networkError(failure))
        }
    }

    /** 等待网络响应与读体，回调拥有Response关闭责任；取消处理先于enqueue安装，不向已取消任务交付迟到结果。 */
    private suspend fun awaitResponse(request: Request): WireResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        if (!continuation.isActive) return@suspendCancellableCoroutine
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                // 取消会同时触发网络错误回调，协程取消已有结果，不能把它转换为失败并让GET续发。
                if (continuation.isActive) continuation.resumeWith(Result.failure(error))
            }
            override fun onResponse(call: Call, response: Response) {
                response.use { value ->
                    if (!continuation.isActive) return
                    try {
                        // 规范化只取状态/文本/实际凭据，完整Response不跨线程持有；读体期间取消由Call关闭底层连接。
                        val result = WireResponse(
                            value.code, value.isSuccessful, value.message, value.body?.string().orEmpty(),
                            value.request.header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
                        )
                        if (continuation.isActive) continuation.resumeWith(Result.success(result))
                    } catch (failure: IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                    }
                }
            }
        })
    }

    /** 已关闭响应的内部值，credential只用于身份版本比较，不输出日志或保存在界面状态。 */
    private data class WireResponse(val code: Int, val successful: Boolean, val message: String, val body: String, val credential: String?)
}

/** 保留公开API的error优先、message次之和HTTP原因兜底；非法错误JSON不改变HTTP状态类别。 */
private fun defaultErrorMessage(body: String, fallback: String): String = runCatching {
    val value = JsonParser.parseString(body).asJsonObject
    value.get("error")?.asString ?: value.get("message")?.asString
}.getOrNull().orEmpty().ifBlank { fallback.ifBlank { "Request failed" } }

/** 只保留服务端固定原因标识；非法错误正文不改变HTTP分类，也不把任意对象变成业务状态。 */
private fun businessReason(body: String): String? = runCatching {
    JsonParser.parseString(body).asJsonObject.get("reasonCode")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString?.takeIf { it.matches(Regex("[A-Z_]{1,40}")) }
}.getOrNull()
