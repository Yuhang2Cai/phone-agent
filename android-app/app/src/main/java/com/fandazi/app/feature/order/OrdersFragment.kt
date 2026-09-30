// 订单模块：管理页面交互和状态展示。
package com.fandazi.app.feature.order

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentOrdersBinding
import com.fandazi.app.feature.ordering.Order
import com.fandazi.app.feature.ordering.OrderStatus
import com.fandazi.app.feature.ordering.OrderingViewModel
import com.fandazi.app.feature.ordering.OrderingViewModelFactory
import kotlinx.coroutines.launch

class OrdersFragment : Fragment(R.layout.fragment_orders) {
    private val viewModel: OrderingViewModel by activityViewModels {
        val activity = requireActivity() as MainActivity
        OrderingViewModelFactory(activity.orderingRepository(), activity.cartStateStore(), activity.cartOwner())
    }
    private var filter = OrderFilter.ALL
    private var expandedOrderId: Long? = null

// 在视图创建后绑定控件、事件与初始状态。
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentOrdersBinding.bind(view)
        filter = savedInstanceState?.getString(KEY_FILTER)?.let { runCatching { OrderFilter.valueOf(it) }.getOrNull() } ?: OrderFilter.ALL
        expandedOrderId = if (savedInstanceState?.containsKey(KEY_EXPANDED) == true) {
            savedInstanceState.getLong(KEY_EXPANDED).takeIf { it > 0 }
        } else {
            viewModel.uiState.value.currentOrder?.takeIf { it.status != OrderStatus.PENDING_PAYMENT }?.id
        }
        val filters = mapOf(
            binding.filterAll to OrderFilter.ALL,
            binding.filterPending to OrderFilter.PENDING_PAYMENT,
            binding.filterDelivering to OrderFilter.DELIVERING,
            binding.filterCompleted to OrderFilter.COMPLETED
        )
        filters.forEach { (button, value) ->
            button.setOnClickListener {
                filter = value
                renderFilters(filters)
                renderOrders(binding, viewModel.uiState.value.orders.ifEmpty { listOfNotNull(viewModel.uiState.value.currentOrder) })
            }
        }
        renderFilters(filters)
        viewModel.refreshOrders()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderOrders(binding, state.orders.ifEmpty { listOfNotNull(state.currentOrder) })
                    binding.orderStatus.text = state.error.orEmpty()
                }
            }
        }
    }

// 渲染filters。
    private fun renderFilters(filters: Map<TextView, OrderFilter>) {
        filters.forEach { (button, value) ->
            button.setBackgroundResource(if (value == filter) R.drawable.search_button_background else R.drawable.dish_option_unselected)
            button.setTextColor(requireContext().getColor(if (value == filter) R.color.surface_card else R.color.brand_ink))
        }
    }

// 渲染orders。
    private fun renderOrders(binding: FragmentOrdersBinding, orders: List<Order>) {
        val visible = OrderPresentation.filter(orders, filter)
        binding.orderList.removeAllViews()
        binding.orderStatus.isVisible = visible.isEmpty() || viewModel.uiState.value.error != null
        if (visible.isEmpty()) binding.orderStatus.text = "这里还没有${OrderPresentation.filterLabel(filter)}订单"
        visible.forEachIndexed { index, order ->
            val card = LayoutInflater.from(requireContext()).inflate(R.layout.item_order_card, binding.orderList, false)
            card.findViewById<TextView>(R.id.order_merchant).text = order.merchantName ?: "饭搭子订单"
            card.findViewById<TextView>(R.id.order_items).text = order.items.joinToString("、") { "${it.dishName} ×${it.quantity}" }
            card.findViewById<TextView>(R.id.order_number).text = "订单 #${order.id} · ${displayDate(order.createdAt)}"
            card.findViewById<TextView>(R.id.order_status_badge).text = OrderPresentation.statusLabel(order.status)
            card.findViewById<TextView>(R.id.order_total).text = "¥${order.total}"
            card.findViewById<ImageView>(R.id.order_image).setImageResource(listOf(R.drawable.home_merchant_3, R.drawable.home_merchant_1, R.drawable.home_merchant_2)[index % 3])
            val timeline = card.findViewById<LinearLayout>(R.id.order_timeline)
            val actions = card.findViewById<LinearLayout>(R.id.order_actions)
            val expanded = expandedOrderId == order.id
            timeline.isVisible = expanded
            actions.isVisible = expanded || order.status == OrderStatus.PENDING_PAYMENT
            if (expanded) renderTimeline(timeline, order)
            card.setOnClickListener {
                if (order.status == OrderStatus.PENDING_PAYMENT) {
                    openPendingOrder(it, order)
                } else {
                    expandedOrderId = if (expandedOrderId == order.id) null else order.id
                    renderOrders(binding, orders)
                }
            }
            val primary = card.findViewById<TextView>(R.id.order_primary_action)
            primary.text = if (order.status == OrderStatus.PENDING_PAYMENT) "继续支付" else "联系骑手"
            primary.setOnClickListener {
                if (order.status == OrderStatus.PENDING_PAYMENT) {
                    openPendingOrder(it, order)
                }
            }
            binding.orderList.addView(card)
        }
    }

// 渲染timeline。
    private fun renderTimeline(container: LinearLayout, order: Order) {
        container.removeAllViews()
        OrderPresentation.timeline(order).forEach { (status, complete) ->
            container.addView(TextView(requireContext()).apply {
                text = "${if (complete) "●" else "○"}  ${OrderPresentation.statusLabel(status)}\n      ${timelineHint(status)}"
                contentDescription = "配送进度：${OrderPresentation.statusLabel(status)}，${if (complete) "已完成" else "待进行"}"
                textSize = 14f
                setTextColor(requireContext().getColor(if (complete) R.color.brand_orange else R.color.brand_muted))
                setPadding(4.dp, 7.dp, 4.dp, 7.dp)
            })
        }
    }

// 处理 `timelineHint` 相关逻辑。
    private fun timelineHint(status: OrderStatus): String = when (status) {
        OrderStatus.MERCHANT_ACCEPTED -> "商家已确认订单，正在准备"
        OrderStatus.PREPARING -> "餐品正在制作中"
        OrderStatus.RIDER_PICKED_UP -> "骑手已取餐"
        OrderStatus.DELIVERING -> "骑手正在配送"
        OrderStatus.DELIVERED -> "餐品已送达"
        else -> ""
    }

// 处理 `displayDate` 相关逻辑。
    private fun displayDate(createdAt: String?): String {
        val date = createdAt?.substringBefore('T')?.takeIf { it.length >= 10 } ?: return "今天"
        return "${date.substring(5, 7)}-${date.substring(8, 10)}"
    }

// 打开pending、订单。
    private fun openPendingOrder(view: View, order: Order) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || parentFragmentManager.isStateSaved) return
        val controller = view.findNavController()
        if (controller.currentDestination?.id != R.id.ordersFragment) return
        view.isEnabled = false
        viewModel.selectOrderSnapshot(order)
        controller.navigate(R.id.paymentFragment)
    }

// 处理 `onSaveInstanceState` 相关逻辑。
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(KEY_FILTER, filter.name)
        outState.putLong(KEY_EXPANDED, expandedOrderId ?: -1L)
        super.onSaveInstanceState(outState)
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val KEY_FILTER = "order_filter"
        const val KEY_EXPANDED = "expanded_order_id"
    }
}
