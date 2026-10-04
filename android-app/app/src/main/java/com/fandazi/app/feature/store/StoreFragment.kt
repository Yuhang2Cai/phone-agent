// 门店详情模块：按 UI03/UI04/UI05 查询并展示真实优惠、菜单、评价，依赖统一目录与套餐仓库。
package com.fandazi.app.feature.store

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.core.image.CatalogImageMetadataRefresh
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.databinding.FragmentStoreBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 三 Tab 留在同一门店路由，服务失败可原地重试，所有内容保持当前完整公开 UUID。 */
class StoreFragment : Fragment(R.layout.fragment_store) {
    private var selectedTab = "优惠"
    private var summary: CatalogStore? = null
    private var contentJob: Job? = null
    private val application get() = requireActivity().application as FandaziApplication
    private val storeId get() = arguments?.getString("storePublicId").orEmpty()
    private val query get() = CatalogQuery(latitude = arguments?.takeIf { it.containsKey("latitude") }?.getDouble("latitude"),
        longitude = arguments?.takeIf { it.containsKey("longitude") }?.getDouble("longitude"))

    /** 首屏同步显示加载状态，再读取门店摘要和用户当前 Tab，返回仍保留选择。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentStoreBinding.bind(view)
        selectedTab = savedInstanceState?.getString("selectedTab") ?: selectedTab
        binding.storeBack.setOnClickListener { view.findNavController().navigateUp() }
        listOf(binding.storeTabDeals to "优惠", binding.storeTabDishes to "菜品", binding.storeTabReviews to "评价")
            .forEach { (tab, name) -> tab.setOnClickListener { selectedTab = name; loadTab(binding) } }
        loadSummary(binding)
        loadTab(binding)
    }

    /** 读取服务器评分/数量/距离和图片；失败仅标出错误并保留公开 ID 的重试入口。 */
    private fun loadSummary(binding: FragmentStoreBinding) {
        binding.storeTitle.text = "正在加载门店"
        binding.storeMeta.text = ""
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = application.catalogRepository.store(storeId, query)) {
                is ApiResult.Success -> {
                    summary = result.value
                    val store = result.value
                    binding.storeTitle.text = store.name
                    binding.storeMeta.text = "${store.rating?.let { "★ $it" } ?: "暂无评分"}  (${store.reviewCount}条评价)  ${CatalogRenderer.distance(store.distanceMeters)}"
                    CatalogImageMetadataRefresh.bind(binding.storeCover,store.imageUrl,store.imageAlt) {
                        // 只读取同门店照片，缺图点击不重新加载评分、评价或当前Tab。
                        when(val refreshed=application.catalogRepository.store(storeId,query)) {
                            is ApiResult.Success -> refreshed.value.imageUrl to refreshed.value.imageAlt
                            is ApiResult.Failure -> null
                        }
                    }
                    binding.storeTitle.setOnClickListener(null)
                    if (selectedTab == "评价") loadTab(binding)
                }
                is ApiResult.Failure -> {
                    summary = null
                    binding.storeTitle.text = "门店加载失败，点击重试"
                    binding.storeTitle.setOnClickListener { loadSummary(binding) }
                }
            }
        }
    }

    /** Tab 切换取消旧内容请求；每个 Tab 查询其真实公开路径，空结果与错误分别展示。 */
    private fun loadTab(binding: FragmentStoreBinding) {
        contentJob?.cancel()
        listOf(binding.storeTabDeals to "优惠", binding.storeTabDishes to "菜品", binding.storeTabReviews to "评价")
            .forEach { (tab, name) -> CatalogRenderer.selection(tab, selectedTab == name) }
        CatalogRenderer.status(binding.storeContent, "正在加载$selectedTab")
        contentJob = viewLifecycleOwner.lifecycleScope.launch {
            when (selectedTab) {
                "优惠" -> when (val result = application.groupBuyingRepository.loadStoreDeals(storeId)) {
                    is ApiResult.Failure -> error(binding)
                    is ApiResult.Success -> {
                        binding.storeContent.removeAllViews()
                        if (result.value.isEmpty()) CatalogRenderer.status(binding.storeContent, "暂无可售套餐")
                        result.value.forEach { deal ->
                            val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_store_deal, binding.storeContent, false)
                            row.findViewById<TextView>(R.id.deal_title).text = deal.title
                            row.findViewById<TextView>(R.id.deal_description).text = deal.description
                            row.findViewById<TextView>(R.id.deal_price).text = deal.priceText()
                            row.findViewById<TextView>(R.id.deal_old_price).apply {
                                // 原价为实际接口金额，删除线仅表示与当前团购价格的视觉对照，不改变金额。
                                text = deal.originalPriceText()
                                paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                            }
                            row.findViewById<TextView>(R.id.deal_discount).text = CatalogRenderer.discount(deal.priceCents, deal.originalPriceCents)
                            CatalogImageMetadataRefresh.bind(row.findViewById(R.id.deal_cover),deal.imageUrl,deal.imageAlt) {
                                // 套餐图只查询本套餐，原金额、按钮和其他条目不随图片刷新重绘。
                                when(val refreshed=application.groupBuyingRepository.loadDeal(deal.publicId)) {
                                    is ApiResult.Success -> refreshed.value.imageUrl to refreshed.value.imageAlt
                                    is ApiResult.Failure -> null
                                }
                            }
                            row.contentDescription = "store-deal-${deal.publicId}"
                            val open = View.OnClickListener {
                                requireView().findNavController().navigate(R.id.dealDetailFragment, Bundle().apply {
                                    putString("storePublicId", deal.storePublicId); putString("dealPublicId", deal.publicId)
                                })
                            }
                            row.setOnClickListener(open); row.findViewById<View>(R.id.deal_action).setOnClickListener(open)
                            binding.storeContent.addView(row)
                        }
                    }
                }
                "菜品" -> when (val result = application.catalogRepository.dishes(storeId)) {
                    is ApiResult.Failure -> error(binding)
                    is ApiResult.Success -> {
                        binding.storeContent.removeAllViews()
                        if (result.value.isEmpty()) CatalogRenderer.status(binding.storeContent, "暂无菜品")
                        result.value.forEach { dish ->
                            val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_store_dish, binding.storeContent, false)
                            row.findViewById<TextView>(R.id.dish_title).text = dish.name
                            row.findViewById<TextView>(R.id.dish_description).text = dish.description
                            row.findViewById<TextView>(R.id.dish_price).text = CatalogRenderer.money(dish.priceCents)
                            CatalogImageMetadataRefresh.bind(row.findViewById(R.id.dish_cover),dish.imageUrl,dish.name) {
                                // 后端菜单没有公开条目ID；只接受名称、描述、金额完全一致且唯一的原条目，不猜匹配或重绘集合。
                                when(val refreshed=application.catalogRepository.dishes(storeId)) {
                                    is ApiResult.Success -> refreshed.value.singleOrNull { it.name==dish.name && it.description==dish.description && it.priceCents==dish.priceCents }?.let { it.imageUrl to it.name }
                                    is ApiResult.Failure -> null
                                }
                            }
                            binding.storeContent.addView(row)
                        }
                    }
                }
                "评价" -> when (val result = application.catalogRepository.reviews(storeId)) {
                    is ApiResult.Failure -> error(binding)
                    is ApiResult.Success -> {
                        binding.storeContent.removeAllViews()
                        summary?.let { renderRating(binding.storeContent, it) }
                        if (result.value.isEmpty()) CatalogRenderer.status(binding.storeContent, "暂无评价")
                        result.value.forEach { review ->
                            val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_store_review, binding.storeContent, false)
                            // 服务器没有头像字段，用户图标明确表示缺头像，不能拿餐图冒充真实用户头像。
                            row.findViewById<ImageView>(R.id.review_avatar).setImageResource(R.drawable.ic_tab_profile)
                            row.findViewById<TextView>(R.id.review_name).text = review.reviewerName
                            row.findViewById<TextView>(R.id.review_stars).text = "★ ${review.rating}"
                            row.findViewById<TextView>(R.id.review_content).text = review.content
                            row.findViewById<TextView>(R.id.review_date).text = DateTimeFormatter.ofPattern("yyyy-MM-dd")
                                .withZone(ZoneId.of("Asia/Shanghai")).format(Instant.parse(review.createdAt))
                            binding.storeContent.addView(row)
                        }
                    }
                }
            }
        }
    }

    /** 评分分布仅使用全量评价摘要；柱条最大值取真实总数量，零评价不产生虚假五星。 */
    private fun renderRating(container: LinearLayout, store: CatalogStore) {
        val panel = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(12.dp, 12.dp, 12.dp, 20.dp)
        }
        panel.addView(TextView(requireContext()).apply {
            text = "${store.rating ?: "暂无评分"}\n${store.reviewCount}条评价"; textSize = 24f
            setTextColor(ContextCompat.getColor(context, R.color.group_buy_orange))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        val distribution = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f)
        }
        for (stars in 5 downTo 1) {
            val count = store.reviewDistribution.getOrElse(stars - 1) { 0 }
            val row = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(TextView(requireContext()).apply { text = "$stars 星"; textSize = 11f; layoutParams = LinearLayout.LayoutParams(32.dp, 24.dp) })
            row.addView(ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                max = store.reviewCount.coerceAtLeast(1); progress = count
                progressTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context, R.color.group_buy_orange))
                layoutParams = LinearLayout.LayoutParams(0, 8.dp, 1f)
            })
            row.addView(TextView(requireContext()).apply { text = count.toString(); textSize = 11f; gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(30.dp, 24.dp) })
            distribution.addView(row)
        }
        panel.addView(distribution); container.addView(panel)
    }

    /** 失败重试只重新读取当前 Tab，不恢复任何静态菜品/评价。 */
    private fun error(binding: FragmentStoreBinding) = CatalogRenderer.status(binding.storeContent, "$selectedTab 加载失败，点击重试") { loadTab(binding) }
    /** 保存用户页签，配置变化不自动返回优惠页。 */
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("selectedTab", selectedTab); super.onSaveInstanceState(outState) }
    /** 将固定布局 dp 转为实际像素，文字不随设备宽度缩放。 */
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
