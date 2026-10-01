// 浏览展示模块：将服务器目录投影为 UI01/UI02 共用卡片；依赖原 XML、真实图片加载器和导航。
package com.fandazi.app.feature.store

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.navigation.findNavController
import com.fandazi.app.R
import com.fandazi.app.core.image.AfterSaleImageLoader
import java.util.Locale

/** UI 的共享绑定函数，不负责网络查询、本地排序、补写门店或套餐事实。 */
object CatalogRenderer {
    /** 按服务器顺序绑定卡片；缺摘要显示无套餐，点击门店传完整 UUID 和已取得的位置。 */
    fun stores(container: LinearLayout, stores: List<CatalogStore>, query: CatalogQuery, retry: () -> Unit) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        stores.forEach { store ->
            val card = inflater.inflate(R.layout.item_group_buy_store_card, container, false)
            card.findViewById<TextView>(R.id.store_name).text = store.name
            card.findViewById<TextView>(R.id.store_rating).text = store.rating?.let { "★ $it" } ?: "暂无评分"
            card.findViewById<TextView>(R.id.store_reviews).text = "${store.reviewCount}条评价"
            card.findViewById<TextView>(R.id.store_distance).text = distance(store.distanceMeters)
            AfterSaleImageLoader.load(card.findViewById(R.id.store_image), store.imageUrl, store.imageAlt, retry)
            val deal = store.featuredDeal
            card.findViewById<TextView>(R.id.deal_name).text = deal?.title ?: "暂无可售套餐"
            card.findViewById<TextView>(R.id.deal_price).text = deal?.priceText().orEmpty()
            card.findViewById<TextView>(R.id.deal_original_price).apply {
                text = deal?.originalPriceText().orEmpty()
                paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            }
            card.findViewById<TextView>(R.id.deal_discount).text = deal?.let { discount(it.priceCents, it.originalPriceCents) }.orEmpty()
            CatalogImages.thumbnail(card.findViewById(R.id.deal_image), deal?.imageUrl, deal?.imageAlt, retry)
            card.contentDescription = "store-card-${store.publicId}"
            card.setOnClickListener {
                card.findNavController().navigate(R.id.storeFragment, Bundle().apply {
                    putString("storePublicId", store.publicId)
                    // 已取得的完整位置对可继续用于详情；不传未知位置的默认零值。
                    if (query.latitude != null && query.longitude != null) {
                        putDouble("latitude", query.latitude); putDouble("longitude", query.longitude)
                    }
                })
            }
            container.addView(card)
        }
    }

    /** 状态占据结果区域，只有错误才可点击重试；空结果不会被误标成网络失败。 */
    fun status(container: LinearLayout, message: String, retry: (() -> Unit)? = null) {
        container.removeAllViews()
        container.addView(TextView(container.context).apply {
            text = message; textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.group_buy_muted))
            val density = resources.displayMetrics.density
            setPadding((12 * density).toInt(), (24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt())
            retry?.let { setOnClickListener { it() } }
        })
    }

    /** 选中态使用设计规定的橙色及下划线，字号不随状态改变，避免横向布局跳动。 */
    fun selection(view: TextView, selected: Boolean) {
        view.setTextColor(ContextCompat.getColor(view.context, if (selected) R.color.group_buy_orange else R.color.group_buy_text))
        view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        val density = view.resources.displayMetrics.density
        val underline = if (selected) GradientDrawable().apply {
            setColor(ContextCompat.getColor(view.context, R.color.group_buy_orange)); setSize((26 * density).toInt(), (3 * density).toInt())
        } else null
        view.setCompoundDrawablesWithIntrinsicBounds(null, null, null, underline)
        view.compoundDrawablePadding = (8 * density).toInt()
    }

    /** 未知距离显示语义提示，不伪装为 0 米；值只格式化服务器提供的整数米。 */
    fun distance(meters: Long?): String = meters?.let { if (it < 1000) "${it}m" else String.format(Locale.ROOT, "%.1fkm", it / 1000.0) } ?: "未定位"
    /** 折扣只用于展示；价格校验仍使用整数分。 */
    fun discount(price: Int, original: Int): String = if (original <= 0) "" else String.format(Locale.ROOT, "%.1f折", price * 10.0 / original)
    /** 整数分精确格式化，不通过 Double 参与金额。 */
    fun money(cents: Int): String = "¥" + java.math.BigDecimal.valueOf(cents.toLong(), 2).stripTrailingZeros().toPlainString()
}
