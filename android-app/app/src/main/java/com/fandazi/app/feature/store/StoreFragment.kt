// 门店详情：在同一导航目的地内渲染优惠、菜品和评价三种完整内容状态。
package com.fandazi.app.feature.store

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.deal.DealPackage
import com.fandazi.app.databinding.FragmentStoreBinding
import kotlinx.coroutines.launch

/**
 * 到店团购门店详情页。
 *
 * 三个 Tab 只切换内容，不创建重复导航页面；套餐点击进入独立套餐详情。
 */
class StoreFragment : Fragment(R.layout.fragment_store) {
    private var selectedTab = StoreTab.DEALS
    private var currentDeals: List<DealPackage> = emptyList()

    /** 绑定返回、Tab 切换和首屏优惠内容。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentStoreBinding.bind(view)
        binding.storeBack.setOnClickListener { view.findNavController().navigateUp() }
        binding.storeTabDeals.setOnClickListener { selectedTab = StoreTab.DEALS; render(binding) }
        binding.storeTabDishes.setOnClickListener { selectedTab = StoreTab.DISHES; render(binding) }
        binding.storeTabReviews.setOnClickListener { selectedTab = StoreTab.REVIEWS; render(binding) }
        render(binding)
        loadDeals(binding)
    }

    /**
     * 从后端按当前门店公开 ID 查询可售套餐。
     * 页面不再维护另一份套餐标题和价格，接口失败时明确显示错误，不用旧数据伪装成功。
     */
    private fun loadDeals(binding: FragmentStoreBinding) {
        val storePublicId = arguments?.getString("storePublicId").orEmpty()
        if (storePublicId.isBlank()) {
            showDealLoadError(binding, "门店信息无效")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = (requireActivity().application as FandaziApplication).groupBuyingRepository.loadStoreDeals(storePublicId)) {
                is ApiResult.Success -> {
                    currentDeals = result.value
                    render(binding)
                }
                is ApiResult.Failure -> showDealLoadError(binding, "套餐加载失败，请稍后重试")
            }
        }
    }

    /** 显示可定位的加载失败状态，避免空白页面让用户误以为没有套餐。 */
    private fun showDealLoadError(binding: FragmentStoreBinding, message: String) {
        currentDeals = emptyList()
        binding.storeContent.removeAllViews()
        binding.storeContent.addView(TextView(requireContext()).apply {
            text = message
            textSize = 15f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.group_buy_muted))
            setPadding(12.dp, 24.dp, 12.dp, 24.dp)
        })
    }

    /** 根据当前 Tab 更新选中样式，并重建对应内容列表。 */
    private fun render(binding: FragmentStoreBinding) {
        val tabs = mapOf(
            StoreTab.DEALS to binding.storeTabDeals,
            StoreTab.DISHES to binding.storeTabDishes,
            StoreTab.REVIEWS to binding.storeTabReviews
        )
        tabs.forEach { (tab, textView) ->
            val selected = tab == selectedTab
            textView.setTextColor(ContextCompat.getColor(requireContext(), if (selected) R.color.group_buy_orange else R.color.group_buy_text))
            textView.setTypeface(textView.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        binding.storeContent.removeAllViews()
        when (selectedTab) {
            StoreTab.DEALS -> renderDeals(binding)
            StoreTab.DISHES -> renderDishes(binding)
            StoreTab.REVIEWS -> renderReviews(binding)
        }
    }

    /** 优惠页列出固定套餐，并把公开套餐 ID 传给套餐详情。 */
    private fun renderDeals(binding: FragmentStoreBinding) {
        val inflater = LayoutInflater.from(requireContext())
        currentDeals.forEachIndexed { index, deal ->
            val row = inflater.inflate(R.layout.item_store_deal, binding.storeContent, false)
            row.findViewById<ImageView>(R.id.deal_cover).setImageResource(IMAGES[index % IMAGES.size])
            row.findViewById<TextView>(R.id.deal_title).text = deal.title
            row.findViewById<TextView>(R.id.deal_description).text = deal.description
            row.findViewById<TextView>(R.id.deal_price).text = deal.priceText()
            row.findViewById<TextView>(R.id.deal_old_price).text = deal.originalPriceText()
            row.findViewById<TextView>(R.id.deal_discount).text = discountText(deal)
            val openDeal = View.OnClickListener {
                val arguments = Bundle().apply {
                    // 同时传递门店和套餐公开 ID，详情页可以从真实门店套餐列表中精确定位当前项。
                    putString("storePublicId", this@StoreFragment.arguments?.getString("storePublicId").orEmpty())
                    putString("dealPublicId", deal.publicId)
                }
                requireView().findNavController().navigate(R.id.dealDetailFragment, arguments)
            }
            row.setOnClickListener(openDeal)
            row.findViewById<View>(R.id.deal_action).setOnClickListener(openDeal)
            binding.storeContent.addView(row)
        }
    }

    /** 根据接口金额计算折扣展示，避免把折扣文案和套餐下标绑定。 */
    private fun discountText(deal: DealPackage): String {
        if (deal.originalPriceCents <= 0) return ""
        return String.format(java.util.Locale.ROOT, "%.1f折", deal.priceCents * 10.0 / deal.originalPriceCents)
    }

    /** 菜品页仅供浏览，不创建加购或购物车操作。 */
    private fun renderDishes(binding: FragmentStoreBinding) {
        val inflater = LayoutInflater.from(requireContext())
        dishes.forEachIndexed { index, dish ->
            val row = inflater.inflate(R.layout.item_store_dish, binding.storeContent, false)
            row.findViewById<ImageView>(R.id.dish_cover).setImageResource(IMAGES[index % IMAGES.size])
            row.findViewById<TextView>(R.id.dish_title).text = dish.title
            row.findViewById<TextView>(R.id.dish_description).text = dish.description
            row.findViewById<TextView>(R.id.dish_price).text = "¥${dish.price}"
            binding.storeContent.addView(row)
        }
    }

    /** 评价页先展示评分摘要，再展示用户评价列表。 */
    private fun renderReviews(binding: FragmentStoreBinding) {
        val summary = TextView(requireContext()).apply {
            text = "4.7  ★★★★★\n326条评价    5星 238  ·  4星 62  ·  3星 18"
            textSize = 18f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.group_buy_orange))
            setPadding(12.dp, 12.dp, 12.dp, 16.dp)
        }
        binding.storeContent.addView(summary)
        val inflater = LayoutInflater.from(requireContext())
        reviews.forEachIndexed { index, review ->
            val row = inflater.inflate(R.layout.item_store_review, binding.storeContent, false)
            row.findViewById<ImageView>(R.id.review_avatar).setImageResource(IMAGES[index % IMAGES.size])
            row.findViewById<TextView>(R.id.review_name).text = review.name
            row.findViewById<TextView>(R.id.review_date).text = review.date
            row.findViewById<TextView>(R.id.review_content).text = review.content
            binding.storeContent.addView(row)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    /** 门店页允许的三个内容状态。 */
    private enum class StoreTab { DEALS, DISHES, REVIEWS }
    private data class Dish(val title: String, val description: String, val price: String)
    private data class Review(val name: String, val date: String, val content: String)

    private companion object {
        val IMAGES = listOf(R.drawable.home_merchant_1, R.drawable.home_merchant_2, R.drawable.home_merchant_3)
        val dishes = listOf(
            Dish("香辣巴骨肉", "遂宁经典，香辣过瘾，肉质紧实", "58"), Dish("酸辣黑猪肉刨汤", "酸辣开胃，汤鲜味浓", "42"),
            Dish("农家小炒肉", "精选黑猪肉，香辣下饭", "36"), Dish("家常麻婆豆腐", "豆香浓郁，麻辣鲜香", "22"),
            Dish("干煸四季豆", "外酥里嫩，咸香入味", "28"), Dish("肉末蒸蛋", "嫩滑细腻，老少皆宜", "18")
        )
        val reviews = listOf(
            Review("小雨吃饭啦", "2024-12-28", "味道真的很正宗！酸辣黑猪肉太下饭了，分量也足。"),
            Review("山野旅人", "2024-12-18", "菜品味道不错，尤其是香辣巴骨肉，辣而不燥。"),
            Review("糖醋小丸子", "2024-12-08", "家庭聚餐选的这家店，菜的口味很棒，价格也实惠。")
        )
    }
}
