// 团购订单模块：展示真实后端订单和团购券入口。
package com.fandazi.app.feature.order

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.time.BusinessDisplay
import com.fandazi.app.feature.voucher.GroupOrderSummary
import com.fandazi.app.feature.voucher.OrderSegment
import com.fandazi.app.feature.voucher.VoucherPresentation
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** UI-10：订单列表；业务金额、订单状态和券数量均来自后端接口。 */
class OrdersFragment : Fragment(R.layout.fragment_orders) {
    private val repository by lazy { (requireActivity().application as FandaziApplication).voucherRepository }
    /** 同一视图只允许一个列表读取；快速重试不并发覆盖错误或真实列表。 */
    private var loading: Job? = null
    // 分段保存显示选择，业务集合仅来自最近成功接口；读取失败时清空，避免旧资格继续可点。
    private var orders: List<GroupOrderSummary> = emptyList()
    private var segment = OrderSegment.ALL

    /** 绑定三个状态的真实操作，重试按钮原地发起同一个订单列表请求。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<TabLayout>(R.id.orders_segments).apply {
            setTabTextColors(resources.getColor(R.color.group_buy_muted, null), resources.getColor(R.color.group_buy_orange, null))
            setSelectedTabIndicatorColor(resources.getColor(R.color.group_buy_orange, null))
            // 固定分段是界面结构；每次点击只筛选后端枚举，不改变订单状态或资格。
            listOf("全部", "待使用", "已完成", "退款/售后").forEach { addTab(newTab().setText(it)) }
            getTabAt(segment.ordinal)?.select()
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    segment = OrderSegment.entries[tab.position]
                    if (orders.isNotEmpty()) renderSegment(view)
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        view.findViewById<View>(R.id.orders_empty_browse).setOnClickListener { it.findNavController().navigate(R.id.homeFragment) }
        view.findViewById<View>(R.id.orders_error_retry).setOnClickListener { load(view) }
        view.findViewById<View>(R.id.orders_network_retry).setOnClickListener { load(view) }
        // onResume 负责首次与返回时刷新，避免同一次页面进入发送两份列表请求。
    }

    /** 返回订单页时刷新支付、券和评价后的状态，避免旧视图持有过期资格。 */
    override fun onResume() {
        super.onResume()
        view?.let(::load)
    }

    /** 列表、空态、普通错误和网络错误互斥，底栏仍由主页面管理。 */
    private fun load(view: View) {
        if (loading?.isActive == true) return
        val status = view.findViewById<TextView>(R.id.order_status)
        val list = view.findViewById<android.widget.LinearLayout>(R.id.order_list)
        setState(view, "loading")
        status.isVisible = true
        list.removeAllViews()
        orders = emptyList()
        status.text = "正在加载订单…"
        loading = viewLifecycleOwner.lifecycleScope.launch {
            when (val result = repository.listOrders()) {
                is ApiResult.Success -> {
                    orders = result.value
                    setState(view, if (orders.isEmpty()) "empty" else "list")
                    renderSegment(view)
                }
                is ApiResult.Failure -> {
                    // 普通身份过期登录后返回订单入口，不能把 401 伪装为空列表。
                    if ((result.error as? ApiError.Http)?.code == 401) {
                        (requireActivity() as MainActivity).openPurchaseLogin(R.id.ordersFragment, Bundle(), true)
                        return@launch
                    }
                    val networkError = result.error is ApiError.Network || result.error is ApiError.Timeout
                    setState(view, if (networkError) "network" else "error")
                    view.findViewById<TextView>(R.id.orders_error_description).text = "加载暂时失败，请稍后重试"
                }
            }
        }
    }

    /** 分段为空时仍保留状态标签可切换；整个账号无订单则展示既有浏览空态。 */
    private fun renderSegment(view: View) {
        val filtered = orders.filter { VoucherPresentation.matches(it, segment) }
        val status = view.findViewById<TextView>(R.id.order_status)
        renderOrders(view.findViewById(R.id.order_list), status, filtered)
        if (orders.isNotEmpty() && filtered.isEmpty()) {
            status.isVisible = true
            status.text = "该状态暂无订单"
        }
    }

    /** 一次只展示一种状态，防止错误层遮挡可点击订单和底部导航。 */
    private fun setState(view: View, state: String) {
        view.findViewById<View>(R.id.orders_content).isVisible = state == "list" || state == "loading"
        view.findViewById<View>(R.id.orders_empty_state).isVisible = state == "empty"
        view.findViewById<View>(R.id.orders_error_state).isVisible = state == "error"
        view.findViewById<View>(R.id.orders_network_state).isVisible = state == "network"
    }

    /** 将后端订单逐条渲染为可点击卡片，避免复用固定的演示订单文本。 */
    private fun renderOrders(container: android.widget.LinearLayout, status: TextView, orders: List<GroupOrderSummary>) {
        container.removeAllViews()
        status.isVisible = false
        orders.forEach { order ->
            val card = LayoutInflater.from(requireContext()).inflate(R.layout.item_group_order_card, container, false)
            // 无障碍身份绑定本条真实公开编号；辅助服务和端到端测试不依赖重复的门店/套餐文字定位。
            card.contentDescription = "order-card-${order.orderId}"
            // 订单卡以响应中的实际套餐名称为标题，不能用固定示例或仅用截断编号替代业务内容。
            card.findViewById<TextView>(R.id.group_order_title).text = order.storeName
            card.findViewById<TextView>(R.id.group_order_deal).text = order.dealTitle
            card.findViewById<TextView>(R.id.group_order_state).text = VoucherPresentation.orderStatus(order.status)
            card.findViewById<TextView>(R.id.group_order_time).text = BusinessDisplay.time(order.createdAt)
            card.findViewById<TextView>(R.id.group_order_summary).text = "数量 ${order.quantity} · ${order.voucherCount} 张团购券"
            card.findViewById<TextView>(R.id.group_order_total).text = VoucherPresentation.amount(order)
            card.setOnClickListener { it.findNavController().navigate(R.id.groupOrderDetailFragment, Bundle().apply { putString("orderId", order.orderId) }) }
            container.addView(card)
        }
    }

}
