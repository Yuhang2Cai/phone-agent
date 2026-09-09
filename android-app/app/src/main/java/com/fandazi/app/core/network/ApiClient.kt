package com.fandazi.app.core.network

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.net.SocketTimeoutException
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

class ApiClient(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    okHttpClient: OkHttpClient? = null
) {
    private val client = (okHttpClient ?: OkHttpClient.Builder()).newBuilder()
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

    suspend fun get(path: String): ApiResult<String> = request(
        Request.Builder().url(url(path)).get().build(),
        retryOnce = true
    )

    suspend fun postJson(path: String, json: String): ApiResult<String> = request(
        Request.Builder()
            .url(url(path))
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
            .build(),
        retryOnce = false
    )

    private suspend fun request(request: Request, retryOnce: Boolean): ApiResult<String> = withContext(Dispatchers.IO) {
        val firstResult = execute(request)
        if (retryOnce && firstResult is ApiResult.Failure && firstResult.error.isRetryableNetworkFailure()) {
            execute(request)
        } else {
            firstResult
        }
    }

    private fun execute(request: Request): ApiResult<String> = try {
        client.newCall(request).execute().use(::toResult)
    } catch (_: SocketTimeoutException) {
        ApiResult.Failure(ApiError.Timeout)
    } catch (exception: IOException) {
        ApiResult.Failure(ApiError.Network(exception.message ?: "Network request failed"))
    }

    private fun toResult(response: Response): ApiResult<String> {
        val body = response.body?.string().orEmpty()
        return if (response.isSuccessful) {
            ApiResult.Success(body)
        } else {
            ApiResult.Failure(ApiError.Http(response.code, messageFrom(body, response.message)))
        }
    }

    private fun url(path: String) = requireNotNull(baseUrl.toHttpUrl().resolve(path)) {
        "Unable to resolve API path: $path"
    }

    private fun messageFrom(body: String, fallback: String): String = runCatching {
        val json = JsonParser.parseString(body).asJsonObject
        json.get("error")?.asString ?: json.get("message")?.asString
    }.getOrNull().orEmpty().ifBlank { fallback.ifBlank { "Request failed" } }

    private fun ApiError.isRetryableNetworkFailure(): Boolean = this is ApiError.Network || this is ApiError.Timeout

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 20L
        const val WRITE_TIMEOUT_SECONDS = 20L
    }
}
