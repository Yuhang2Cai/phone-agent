// 团购搜索页：管理 UI-02 的查询、分类、排序和门店结果展示。
package com.fandazi.app.feature.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentSearchBinding

/**
 * 团购搜索与筛选结果页。
 *
 * 当前切片先提供确定性视觉和交互骨架；查询参数会完整保留，后续接入 Store API 时无需修改页面结构。
 */
class SearchFragment : Fragment(R.layout.fragment_search) {
    private var selectedCategory = "推荐"
    private var selectedSort = "人均"

    /** 初始化查询词，并绑定分类、排序与结果卡片。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSearchBinding.bind(view)
        val initialQuery = arguments?.getString("initialQuery").orEmpty()
        binding.searchInput.setText(initialQuery.takeUnless { it in setOf("推荐", "人均", "距离", "筛选") }.orEmpty())
        selectedCategory = initialQuery.takeIf { it in CATEGORIES } ?: if (initialQuery == "火锅") "火锅" else "推荐"
        selectedSort = initialQuery.takeIf { it in SORTS } ?: "人均"

        binding.searchBack.setOnClickListener { view.findNavController().navigateUp() }
        binding.clearSearch.setOnClickListener { binding.searchInput.text?.clear() }
        binding.searchInput.doAfterTextChanged { renderResults(binding) }
        binding.searchButton.setOnClickListener { renderResults(binding) }
        renderCategories(binding)
        renderFilters(binding)
        renderResults(binding)
    }

    /** 横向分类仅改变查询条件，不创建新的页面或业务意图。 */
    private fun renderCategories(binding: FragmentSearchBinding) {
        binding.searchCategories.removeAllViews()
        CATEGORIES.forEach { category ->
            val view = TextView(requireContext()).apply {
                text = category
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setTextColor(ContextCompat.getColor(requireContext(), if (category == selectedCategory) R.color.group_buy_orange else R.color.group_buy_text))
                setTypeface(typeface, if (category == selectedCategory) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setOnClickListener { selectedCategory = category; renderCategories(binding); renderResults(binding) }
            }
            binding.searchCategories.addView(view, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = 22.dp })
        }
    }

    /** 排序栏只展示首版允许的人均、距离和筛选入口。 */
    private fun renderFilters(binding: FragmentSearchBinding) {
        binding.filterRow.removeAllViews()
        SORTS.forEach { sort ->
            val view = TextView(requireContext()).apply {
                text = if (sort == "筛选") "筛选 ⏷" else "$sort⌄"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                minHeight = 44.dp
                setTextColor(ContextCompat.getColor(requireContext(), if (sort == selectedSort) R.color.group_buy_orange else R.color.group_buy_text))
                setOnClickListener { selectedSort = sort; renderFilters(binding); renderResults(binding) }
            }
            binding.filterRow.addView(view, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 44.dp).apply { marginStart = 24.dp })
        }
    }

    /**
     * 根据当前条件重建可点击卡片。
     * 演示数据不伪造配送状态；距离仅作为定位已授权场景下的展示样例。
     */
    private fun renderResults(binding: FragmentSearchBinding) {
        val keyword = binding.searchInput.text?.toString().orEmpty().trim()
        val stores = previews.filter { preview ->
            keyword.isBlank() || preview.name.contains(keyword) || preview.deal.contains(keyword) || selectedCategory == "推荐" || preview.category == selectedCategory
        }.sortedWith(compareBy { if (selectedSort == "距离") it.distanceValue else it.priceValue })
        binding.resultCount.text = "找到相关团购 ${stores.size} 个"
        binding.searchStatus.text = if (selectedSort == "距离") "距离排序需要可用定位；无权限时保持默认顺序" else ""
        binding.searchResults.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        stores.forEach { preview ->
            val card = inflater.inflate(R.layout.item_group_buy_store_card, binding.searchResults, false)
            card.findViewById<ImageView>(R.id.store_image).setImageResource(preview.image)
            card.findViewById<ImageView>(R.id.deal_image).setImageResource(preview.dealImage)
            card.findViewById<TextView>(R.id.store_name).text = preview.name
            card.findViewById<TextView>(R.id.store_rating).text = "★ ${preview.rating}"
            card.findViewById<TextView>(R.id.store_reviews).text = "${preview.reviews}条评价"
            card.findViewById<TextView>(R.id.store_distance).text = preview.distance
            card.findViewById<TextView>(R.id.deal_name).text = preview.deal
            card.findViewById<TextView>(R.id.deal_price).text = "¥${preview.price}"
            card.findViewById<TextView>(R.id.deal_original_price).text = "¥${preview.originalPrice}"
            card.findViewById<TextView>(R.id.deal_discount).text = preview.discount
            card.setOnClickListener {
                view?.findNavController()?.navigate(R.id.storeFragment, Bundle().apply { putString("storePublicId", preview.publicId) })
            }
            binding.searchResults.addView(card)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    /** 搜索结果页使用的稳定预览模型。 */
    private data class Preview(val publicId: String, val name: String, val category: String, val rating: String, val reviews: String, val distance: String, val distanceValue: Int, val deal: String, val price: String, val priceValue: Int, val originalPrice: String, val discount: String, val image: Int, val dealImage: Int)

    private companion object {
        val CATEGORIES = listOf("推荐", "奶茶咖啡", "快餐小吃", "火锅", "烧烤烤肉", "地方菜系")
        val SORTS = listOf("人均", "距离", "筛选")
        val previews = listOf(
            Preview("store-001", "巴蜀老火锅", "火锅", "4.8", "3267", "2.1km", 21, "经典四人火锅套餐", "198", 198, "302", "6.6折", R.drawable.home_merchant_2, R.drawable.home_merchant_1),
            Preview("store-002", "蜀香园火锅", "火锅", "4.7", "1894", "3.5km", 35, "甄选牛肉双人套餐", "128", 128, "196", "6.5折", R.drawable.home_merchant_1, R.drawable.home_merchant_2),
            Preview("store-003", "海底捞火锅", "火锅", "4.9", "8921", "4.8km", 48, "双人经典套餐", "268", 268, "336", "8.0折", R.drawable.home_merchant_3, R.drawable.home_merchant_1),
            Preview("store-004", "捞王锅物料理", "火锅", "4.6", "3721", "6.3km", 63, "甄选和牛三人套餐", "298", 298, "428", "7.0折", R.drawable.home_merchant_2, R.drawable.home_merchant_3)
        )
    }
}
