// AI 卡片渲染器：把后端返回的版本化字段转换为聊天页中的可交互卡片。
package com.fandazi.app.feature.agent

import android.graphics.Typeface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.fandazi.app.R

/**
 * 负责渲染助手返回的门店、套餐、订单和通用信息卡片。
 *
 * 渲染器不保存业务样例，也不根据本地下标生成门店或套餐；卡片标题、价格、地址和状态均来自后端。
 * 只有字段标签、颜色和点击行为属于客户端静态 UI 规范。
 */
object AgentCardRenderer {
    /** 将卡片列表追加到聊天内容容器，并为有公开 ID 的卡片注册受控点击行为。 */
    fun render(fragment: Fragment, container: LinearLayout, cards: List<AgentCardPayload>, onNavigate: (AgentNavigationRequest) -> Unit) {
        cards.forEach { card ->
            val view = LinearLayout(fragment.requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp(fragment), 14.dp(fragment), 16.dp(fragment), 14.dp(fragment))
                background = ContextCompat.getDrawable(context, R.drawable.agent_bot_bubble)
                contentDescription = "agent-card-${card.type}-${card.storePublicId ?: card.dealPublicId ?: card.orderPublicId.orEmpty()}"
            }
            val title = TextView(fragment.requireContext()).apply {
                text = card.fields["name"] ?: card.fields["title"] ?: card.type
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(ContextCompat.getColor(context, R.color.group_buy_text))
            }
            view.addView(title)
            card.fields.filterKeys { it !in setOf("name", "title") }.forEach { (key, value) ->
                if (value.isBlank()) return@forEach
                view.addView(TextView(fragment.requireContext()).apply {
                    text = "${label(key)}：$value"
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(context, R.color.group_buy_muted))
                    setPadding(0, 6.dp(fragment), 0, 0)
                })
            }
            when (card.type) {
                "store" -> card.storePublicId?.let { id -> view.setOnClickListener { onNavigate(AgentNavigationRequest.Store(id)) } }
                "deal" -> if (!card.storePublicId.isNullOrBlank() && !card.dealPublicId.isNullOrBlank()) {
                    view.setOnClickListener { onNavigate(AgentNavigationRequest.Deal(card.storePublicId, card.dealPublicId)) }
                }
            }
            container.addView(view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 12.dp(fragment) })
        }
    }

    /** 将协议字段名转换为页面展示标签，字段值仍然完全来自服务端。 */
    private fun label(key: String): String = when (key) {
        "address" -> "地址"
        "category" -> "品类"
        "averageSpendCents" -> "人均"
        "openingHours" -> "营业时间"
        "priceCents" -> "价格"
        "originalPriceCents" -> "原价"
        "inventory" -> "剩余库存"
        "purchaseLimit" -> "限购"
        "description" -> "说明"
        "status" -> "状态"
        else -> key
    }

    private fun Int.dp(fragment: Fragment): Int = (this * fragment.resources.displayMetrics.density).toInt()
}
