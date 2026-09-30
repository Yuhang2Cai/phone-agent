package com.fandazi.app.feature.deal

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.launch

/**
 * 展示后端返回的团购套餐详情。
 *
 * 页面通过公开套餐 ID 查询详情，并用同一门店的真实套餐列表生成横向切换项；因此标题、金额、
 * 规则和明细都跟随用户点击的套餐变化，不依赖固定下标或默认套餐文案。
 */
class DealDetailFragment : Fragment(R.layout.fragment_deal_detail) {
    private var selectedDeal: DealPackage? = null
    private var availableDeals: List<DealPackage> = emptyList()

    /** 当前页面由导航传入的公开套餐 ID。 */
    private val dealPublicId: String
        get() = arguments?.getString("dealPublicId").orEmpty()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 套餐详情页只保留返回操作，不重新引入已从设计稿移除的顶部功能按钮。
        view.findViewById<View>(R.id.deal_back).setOnClickListener {
            view.findNavController().navigateUp()
        }
        view.findViewById<View>(R.id.deal_purchase).setOnClickListener {
            if (selectedDeal == null) {
                Toast.makeText(requireContext(), "套餐信息仍在加载，请稍候", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "正在准备下单确认页", Toast.LENGTH_SHORT).show()
            }
        }

        loadDeal(view)
    }

    /**
     * 按门店公开 ID 查询真实套餐列表，再按用户点击的套餐公开 ID精确定位当前项。
     * 列表查询失败或找不到指定套餐时都展示错误状态，不用第一条或默认套餐顶替当前结果。
     */
    private fun loadDeal(view: View) {
        if (dealPublicId.isBlank()) {
            showLoadError(view, "套餐信息无效")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val repository = (requireActivity().application as FandaziApplication).groupBuyingRepository
            val storePublicId = arguments?.getString("storePublicId").orEmpty()
            when (val list = repository.loadStoreDeals(storePublicId)) {
                is ApiResult.Success -> {
                    availableDeals = list.value
                    selectedDeal = availableDeals.firstOrNull { it.publicId == dealPublicId }
                    if (selectedDeal == null) {
                        showLoadError(view, "未找到当前套餐")
                    } else {
                        renderSelectedDeal(view)
                        renderDealOptions(view)
                    }
                }
                is ApiResult.Failure -> showLoadError(view, "套餐详情加载失败，请稍后重试")
            }
        }
    }

    /** 将当前接口对象渲染到标题、规则、价格和明细区域。 */
    private fun renderSelectedDeal(view: View) {
        val deal = selectedDeal ?: return
        view.findViewById<TextView>(R.id.deal_title).apply {
            text = deal.title
            // 使用公开 ID作为无障碍和自动化验收标识，确认当前内容确实跟随套餐切换。
            contentDescription = "selected-deal-${deal.publicId}"
        }
        view.findViewById<TextView>(R.id.deal_meta).text = "库存${deal.inventory}份  ·  每人限购${deal.purchaseLimit}份"
        view.findViewById<TextView>(R.id.deal_rule).text = "${deal.validFrom} 至 ${deal.validTo}可用  ·  购买后请按门店规则使用"
        view.findViewById<TextView>(R.id.deal_price).apply {
            text = deal.priceText()
            contentDescription = "deal-price-${deal.priceCents}"
        }
        renderDealDetails(view.findViewById(R.id.deal_detail_list), deal)
    }

    /** 根据真实套餐列表生成可点击选项，点击后重新绑定当前套餐而不是只改变样式。 */
    private fun renderDealOptions(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.deal_option_list)
        container.removeAllViews()
        availableDeals.forEach { deal ->
            val item = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                layoutParams = LinearLayout.LayoutParams(152.dp, 84.dp).apply { marginEnd = 10.dp }
                contentDescription = "deal-option-${deal.publicId}"
                setOnClickListener {
                    selectedDeal = deal
                    renderSelectedDeal(view)
                    renderDealOptions(view)
                }
            }
            item.background = ContextCompat.getDrawable(
                requireContext(),
                if (deal.publicId == selectedDeal?.publicId) R.drawable.deal_option_selected else R.drawable.deal_option_default,
            )
            item.addView(createOptionText(deal.title, 15f, Typeface.BOLD))
            item.addView(createOptionText(deal.priceText(), 14f, Typeface.NORMAL))
            container.addView(item)
        }
    }

    /** 将接口描述、有效期和库存组成可审计的套餐明细，不补写接口不存在的菜品。 */
    private fun renderDealDetails(container: LinearLayout, deal: DealPackage) {
        container.removeAllViews()
        addDetailGroup(container, "套餐内容", listOf("说明" to deal.description))
        addDetailGroup(
            container,
            "使用信息",
            listOf(
                "有效期" to "${deal.validFrom} 至 ${deal.validTo}",
                "剩余库存" to "${deal.inventory}份",
                "购买限制" to "每人限购${deal.purchaseLimit}份",
            ),
        )
    }

    /** 按分类标题和字段值生成详情区域，保证接口数据的结构在 UI 中可追踪。 */
    private fun addDetailGroup(container: LinearLayout, title: String, rows: List<Pair<String, String>>) {
        container.addView(TextView(requireContext()).apply {
            text = title
            textSize = 17f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.group_buy_text))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 14.dp, 0, 6.dp)
        })
        rows.forEach { (label, value) ->
            container.addView(createDetailRow(label, value))
        }
    }

    /** 创建套餐详情的一行标签和值。 */
    private fun createDetailRow(label: String, value: String): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp, 0, 8.dp)
            addView(TextView(context).apply {
                text = "•  $label"
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.group_buy_text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.group_buy_muted))
            })
        }

    /** 创建横向选项中的文字，统一字号与颜色。 */
    private fun createOptionText(content: String, size: Float, style: Int): TextView =
        TextView(requireContext()).apply {
            text = content
            textSize = size
            setTextColor(ContextCompat.getColor(requireContext(), R.color.group_buy_text))
            setTypeface(typeface, style)
            setPadding(0, 2.dp, 0, 2.dp)
        }

    /** 请求失败时明确展示状态，不把经典套餐作为错误兜底。 */
    private fun showLoadError(view: View, message: String) {
        view.findViewById<TextView>(R.id.deal_title).text = message
        view.findViewById<TextView>(R.id.deal_meta).text = ""
        view.findViewById<TextView>(R.id.deal_rule).text = ""
        view.findViewById<LinearLayout>(R.id.deal_option_list).removeAllViews()
        view.findViewById<LinearLayout>(R.id.deal_detail_list).removeAllViews()
    }

    /** 将设计稿 dp 数值转换为当前设备像素。 */
    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
