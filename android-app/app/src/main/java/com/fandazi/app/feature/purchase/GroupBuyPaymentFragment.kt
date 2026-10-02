// UI-08 支付模块：按本人订单身份读取真实摘要并执行模拟支付，不接入真实渠道。
package com.fandazi.app.feature.purchase

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewConfiguration
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.image.AfterSaleImageLoader
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/** 订单加载成功前禁用支付，401 登录返回原订单并再次读取。 */
class GroupBuyPaymentFragment : Fragment(R.layout.fragment_group_buy_payment) {
    private val viewModel: PurchaseViewModel by viewModels { PurchaseViewModelFactory((requireActivity().application as FandaziApplication).purchaseRepository) }
    private val orderId get() = requireArguments().getString("orderId").orEmpty()
    private var routed = false
    // 记录用户支付手势起点，避免快速响应后双击第二下误触成功页的返回订单按钮。
    private var paymentClickUptime: Long? = null

    /** 返回、支付和重试入口绑定当前订单，成功导航只消费一次。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.group_payment_back).setOnClickListener { findNavController().navigateUp() }
        view.findViewById<View>(R.id.group_payment_action).setOnClickListener {
            if (!viewModel.state.value.loading) {
                if (viewModel.state.value.order == null) viewModel.loadOrder(orderId) else {
                    paymentClickUptime = SystemClock.uptimeMillis()
                    viewModel.mockPay()
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val order = state.order
                    view.findViewById<TextView>(R.id.group_payment_action).apply {
                        isEnabled = !state.loading && (order == null || order.status == "PENDING_PAYMENT")
                        alpha = if (isEnabled) 1f else .5f
                        text = if (state.loading) "正在处理" else if (order == null) "重试" else "确认支付"
                    }
                    view.findViewById<TextView>(R.id.group_payment_status).text = state.error?.let(::purchaseError).orEmpty()
                    order?.let {
                        view.findViewById<TextView>(R.id.group_payment_order).text = it.dealTitle
                        view.findViewById<TextView>(R.id.group_payment_store).text = it.storeName
                        view.findViewById<TextView>(R.id.group_payment_quantity).text = "数量 × ${it.quantity}"
                        view.findViewById<TextView>(R.id.group_payment_amount).text = purchaseMoney(it.totalCents)
                        view.findViewById<TextView>(R.id.group_payment_summary_amount).text = purchaseMoney(it.totalCents)
                        AfterSaleImageLoader.load(view.findViewById(R.id.group_payment_image), it.imageUrl, it.imageAlt) { viewModel.loadOrder(orderId) }
                    }
                    if ((state.error as? ApiError.Http)?.code == 401 && !routed) {
                        routed = true
                        (requireActivity() as MainActivity).openPurchaseLogin(R.id.groupBuyPaymentFragment, Bundle().apply { putString("orderId", orderId) }, true)
                    }
                    if (order?.status == "PAID" && !routed) {
                        routed = true
                        // 支付事实仍立即采纳；仅等当前双击手势结束才换页，不延迟请求或伪造结果。
                        // 生命周期取消等待可防止用户返回后，旧页面再跳转到成功页。
                        viewLifecycleOwner.lifecycleScope.launch {
                            val remaining = paymentClickUptime?.let {
                                ViewConfiguration.getDoubleTapTimeout() + 100L - (SystemClock.uptimeMillis() - it)
                            } ?: 0L
                            delay(remaining.coerceAtLeast(0L))
                            if (viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                findNavController().navigate(R.id.paymentSuccessFragment, Bundle().apply { putString("orderId", orderId) })
                            }
                        }
                    }
                }
            }
        }
        if (viewModel.state.value.order == null) viewModel.loadOrder(orderId)
    }
}
