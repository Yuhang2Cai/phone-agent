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
import androidx.navigation.findNavController
import com.fandazi.app.R

/**
 * 展示团购套餐的固定详情结构。
 *
 * 当前任务先完成 UI-06 与导航边界：套餐内容使用稳定的演示数据渲染。真实价格、库存和可用规则
 * 在接入接口后必须重新向后端查询，不能信任页面展示值。
 */
class DealDetailFragment : Fragment(R.layout.fragment_deal_detail) {

    /** 当前页面使用公开套餐 ID，避免把数据库主键暴露给 Android 客户端。 */
    private val dealPublicId: String
        get() = arguments?.getString("dealPublicId").orEmpty()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 顶部仅保留返回动作，严格遵守确认稿中移除搜索、收藏、分享和更多按钮的约束。
        view.findViewById<View>(R.id.deal_back).setOnClickListener {
            view.findNavController().navigateUp()
        }

        // 套餐选项与明细属于可变业务内容，通过结构化列表生成，后续可直接替换为接口结果。
        renderDealOptions(view.findViewById(R.id.deal_option_list))
        renderDealDetails(view.findViewById(R.id.deal_detail_list))

        view.findViewById<View>(R.id.deal_purchase).setOnClickListener {
            // 下单确认页将在 Task 6 接入；当前只保留公开 ID 边界，不回落到旧外卖购物车。
            val message = if (dealPublicId.isBlank()) {
                "套餐信息无效，请返回后重试"
            } else {
                "正在准备下单确认页"
            }
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 渲染横向套餐切换项。
     * 第一项使用橙色描边表示当前选择，其余项保持中性边框，尺寸固定以避免滚动时跳动。
     */
    private fun renderDealOptions(container: LinearLayout) {
        val options = listOf(
            DealOption("经典2-3人餐", "¥56.5"),
            DealOption("双人餐", "¥44"),
            DealOption("品质3-4人餐", "¥82.8"),
        )

        options.forEachIndexed { index, option ->
            val item = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                background = ContextCompat.getDrawable(
                    requireContext(),
                    if (index == 0) R.drawable.deal_option_selected else R.drawable.deal_option_default,
                )
                layoutParams = LinearLayout.LayoutParams(152.dp, 84.dp).apply {
                    marginEnd = 10.dp
                }
            }
            item.addView(createOptionText(option.title, 15f, Typeface.BOLD))
            item.addView(createOptionText(option.price, 14f, Typeface.NORMAL))
            container.addView(item)
        }
    }

    /**
     * 按“分类标题 → 菜品行”生成团购明细。
     * 分类和数量由结构化数据驱动，后续接入接口时无需改动页面层级。
     */
    private fun renderDealDetails(container: LinearLayout) {
        val groups = listOf(
            DealGroup("农场特色菜", listOf("香辣巴骨肉" to "1份")),
            DealGroup(
                "厨师长推荐风味菜 3选1",
                listOf("遂宁特产白砍鹅" to "1份", "砂锅红烧黑猪肉" to "1份", "小炒黑猪肉" to "1份"),
            ),
            DealGroup("时令蔬菜", listOf("炒时蔬" to "1份")),
            DealGroup("蒸菜", listOf("砂锅黑猪肉水饺" to "1份")),
            DealGroup("其他", listOf("一次性餐具" to "3份", "米饭" to "3份", "纸巾" to "1份")),
        )

        groups.forEach { group ->
            container.addView(TextView(requireContext()).apply {
                text = group.title
                setTextColor(ContextCompat.getColor(requireContext(), R.color.group_buy_text))
                textSize = 17f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 14.dp, 0, 6.dp)
            })
            group.items.forEach { (name, quantity) ->
                container.addView(createDetailRow(name, quantity))
            }
        }
    }

    /** 创建套餐选项中的单行文字，统一颜色和垂直间距。 */
    private fun createOptionText(content: String, size: Float, style: Int): TextView =
        TextView(requireContext()).apply {
            text = content
            textSize = size
            setTextColor(ContextCompat.getColor(requireContext(), R.color.group_buy_text))
            setTypeface(typeface, style)
            setPadding(0, 2.dp, 0, 2.dp)
        }

    /** 创建左右对齐的菜品与数量行，保证长名称仍优先占用可用宽度。 */
    private fun createDetailRow(name: String, quantity: String): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp, 0, 8.dp)
            addView(TextView(context).apply {
                text = "•  $name"
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.group_buy_text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(context).apply {
                text = quantity
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.group_buy_muted))
            })
        }

    /** 将设计稿 dp 数值转换为当前设备像素，确保动态视图与 XML 尺寸口径一致。 */
    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    /** 横向可选套餐的最小展示数据。 */
    private data class DealOption(val title: String, val price: String)

    /** 团购明细分组，items 保存菜品名称和份数。 */
    private data class DealGroup(val title: String, val items: List<Pair<String, String>>)
}
