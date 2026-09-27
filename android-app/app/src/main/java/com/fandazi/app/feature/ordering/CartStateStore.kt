// 下单流程模块：保存和恢复流程状态。
package com.fandazi.app.feature.ordering

import android.content.Context
import com.google.gson.Gson

data class CartSnapshot(
    val owner: String,
    val merchant: Merchant?,
    val draft: CartDraft?,
    val quote: CartQuote?,
    val lines: List<CartLine>? = null,
    val currentOrder: Order? = null,
    val orderRequestKey: String? = null,
    val paymentRequestKey: String? = null,
    val activeOrderUsesCurrentCart: Boolean = false
)

interface CartStateStore {
// 读取当前数据。
    fun read(owner: String): CartSnapshot?
// 处理 `write` 相关逻辑。
    fun write(snapshot: CartSnapshot)
// 清除当前数据。
    fun clear(owner: String)
}

object NoOpCartStateStore : CartStateStore {
// 读取当前数据。
    override fun read(owner: String) = null
// 处理 `write` 相关逻辑。
    override fun write(snapshot: CartSnapshot) = Unit
// 清除当前数据。
    override fun clear(owner: String) = Unit
}

class PreferencesCartStateStore(context: Context, private val gson: Gson = Gson()) : CartStateStore {
    private val preferences = context.getSharedPreferences("ordering_cart", Context.MODE_PRIVATE)

// 读取当前数据。
    override fun read(owner: String): CartSnapshot? = runCatching {
        preferences.getString(key(owner), null)?.let { gson.fromJson(it, CartSnapshot::class.java) }
    }.getOrNull()?.takeIf { it.owner == owner }

// 处理 `write` 相关逻辑。
    override fun write(snapshot: CartSnapshot) {
        preferences.edit().putString(key(snapshot.owner), gson.toJson(snapshot)).apply()
    }

// 清除当前数据。
    override fun clear(owner: String) {
        preferences.edit().remove(key(owner)).apply()
    }

// 处理 `key` 相关逻辑。
    private fun key(owner: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(owner.toByteArray(Charsets.UTF_8))
        return "cart_${digest.joinToString("") { "%02x".format(it) }}"
    }
}
