package com.fandazi.app.feature.agent

import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.TimeUnit

interface AgentRepository {
    suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult>
    fun streamEvents(sessionId: String, afterEventId: Long?): Flow<AgentEvent>
    suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest): ApiResult<Unit>
}

/**
 * Talks only to the public Spring Boot /api surface. The app authentication token is attached by
 * the normal bearer interceptor; no Agent Service credential or internal token exists in this APK.
 */
class HttpAgentRepository(
    private val baseUrl: String,
    private val tokenProvider: () -> String?,
    private val gson: Gson = Gson(),
    client: OkHttpClient? = null,
    private val reconnectDelayMillis: Long = 500L
) : AgentRepository {
    private val client = (client ?: OkHttpClient.Builder()).newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder().apply {
                tokenProvider()?.takeIf(String::isNotBlank)?.let { header("Authorization", "Bearer $it") }
            }.build()
            chain.proceed(request)
        }
        .build()

    override suspend fun sendMessage(sessionId: String, text: String, mode: AgentMode): ApiResult<AgentMessageResult> =
        executeJson(
            Request.Builder()
                .url(url("agent/sessions/$sessionId/messages"))
                .post(gson.toJson(mapOf("text" to text, "mode" to mode.name)).toRequestBody(JSON))
                .build()
        ) { body -> parseMessage(body) }

    override fun streamEvents(sessionId: String, afterEventId: Long?): Flow<AgentEvent> = callbackFlow {
        val streamJob = launch(Dispatchers.IO) {
            var cursor = afterEventId
            while (currentCoroutineContext().isActive) {
                val request = Request.Builder().url(url("agent/sessions/$sessionId/events")).apply {
                    if (cursor != null) header("Last-Event-ID", cursor.toString())
                    header("Accept", "text/event-stream")
                }.build()
                try {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw AgentStreamException(response.code, errorMessage(response.body?.string().orEmpty(), response.message))
                        var id: Long? = null
                        val data = StringBuilder()
                        response.body?.charStream()?.buffered()?.useLines { lines ->
                            lines.forEach { line ->
                                when {
                                    line.startsWith("id:") -> id = line.removePrefix("id:").trim().toLongOrNull()
                                    line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                                    line.isBlank() && data.isNotBlank() -> {
                                        parseEvent(id, data.toString())?.let { event ->
                                            cursor = event.id ?: cursor
                                            trySend(event)
                                        }
                                        id = null
                                        data.clear()
                                    }
                                }
                            }
                        }
                        if (data.isNotBlank()) parseEvent(id, data.toString())?.let { event ->
                            cursor = event.id ?: cursor
                            trySend(event)
                        }
                    }
                    if (currentCoroutineContext().isActive) delay(reconnectDelayMillis)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AgentStreamException) {
                    close(failure)
                    return@launch
                } catch (_: IOException) {
                    if (currentCoroutineContext().isActive) delay(reconnectDelayMillis)
                }
            }
        }
        awaitClose { streamJob.cancel() }
    }

    override suspend fun grantThisTaskAuto(request: ThisTaskAutoRequest): ApiResult<Unit> = executeJson(
        Request.Builder().url(url("automation/task-grants"))
            .post(
                gson.toJson(
                    mapOf(
                        "sessionId" to request.sessionId,
                        "addressId" to request.addressId,
                        "maxAmount" to request.maxAmount,
                        "expiresAt" to request.expiresAt.toString()
                    )
                ).toRequestBody(JSON)
            ).build()
    ) { Unit }

    private suspend fun <T> executeJson(request: Request, parser: (String) -> T): ApiResult<T> = try {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) ApiResult.Success(parser(body))
            else ApiResult.Failure(ApiError.Http(response.code, errorMessage(body, response.message)))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        ApiResult.Failure(ApiError.Network(error.message ?: "Network request failed"))
    } catch (error: Exception) {
        ApiResult.Failure(ApiError.Network(error.message ?: "Unable to read server response"))
    }

    private fun parseMessage(body: String): AgentMessageResult {
        val value = JsonParser.parseString(body).asJsonObject
        return AgentMessageResult(
            status = runCatching { AgentStatus.valueOf(value.string("status").orEmpty()) }.getOrDefault(AgentStatus.FAILED),
            message = value.string("message").orEmpty(),
            nextAction = value.string("nextAction"),
            candidateCart = value.objectOrNull("candidateCart")?.toCandidateCart(),
            events = value.array("events").mapNotNull { it.asJsonObjectOrNull()?.toEvent() }
        )
    }

    private fun parseEvent(id: Long?, raw: String): AgentEvent? = runCatching {
        val value = JsonParser.parseString(raw).asJsonObject
        value.toEvent(id)
    }.getOrNull()

    private fun JsonObject.toEvent(fallbackId: Long? = null) = AgentEvent(
        id = string("id")?.toLongOrNull() ?: fallbackId,
        type = string("type").orEmpty(),
        summary = string("summary").orEmpty(),
        toolName = string("toolName"),
        status = string("status")
    )

    private fun JsonObject.toCandidateCart(): CandidateCart? {
        val merchant = string("merchantName") ?: string("merchant") ?: array("merchants").firstOrNull()
            ?.asJsonObjectOrNull()?.string("name") ?: "已选商家"
        val addressId = string("addressId")?.toLongOrNull()
            ?: get("addressId")?.takeUnless { it.isJsonNull }?.asLong
            ?: return null
        val items = (array("items").takeIf { it.size() > 0 } ?: array("lines")).mapNotNull { item ->
            item.asJsonObjectOrNull()?.let {
                CandidateCartItem(
                    name = it.string("name") ?: it.string("dishName") ?: return@let null,
                    specification = it.string("specification") ?: it.string("spec") ?: "",
                    price = it.priceText("price").takeUnless { value -> value == "—" } ?: it.priceText("lineTotal"),
                    quantity = it.string("quantity")?.toIntOrNull() ?: 1
                )
            }
        }
        return CandidateCart(
            merchantName = merchant,
            items = items,
            address = string("address") ?: string("defaultAddress") ?: "默认地址（ID $addressId）",
            deliveryFee = priceText("deliveryFee"),
            discount = priceText("discount"),
            total = priceText("total"),
            maxAmount = priceText("maxAmount"),
            addressId = addressId
        )
    }

    private fun JsonObject.priceText(name: String): String {
        val raw = string(name).orEmpty()
        if (raw.isBlank()) return "—"
        return if (raw.startsWith("¥") || raw.startsWith("-¥")) raw else "¥$raw"
    }

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.objectOrNull(name: String): JsonObject? = get(name)?.asJsonObjectOrNull()
    private fun JsonObject.array(name: String): JsonArray = getAsJsonArray(name) ?: JsonArray()
    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null
    private fun errorMessage(body: String, fallback: String): String = runCatching {
        JsonParser.parseString(body).asJsonObject.string("error") ?: JsonParser.parseString(body).asJsonObject.string("message")
    }.getOrNull().orEmpty().ifBlank { fallback.ifBlank { "Request failed" } }
    private fun url(path: String) = requireNotNull(baseUrl.toHttpUrl().resolve(path)) { "Invalid API base URL" }

    private class AgentStreamException(val code: Int, message: String) : IOException(message)

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}
