// 支付页面：独立处理支付交互与结果展示。
package com.fandazi.app.feature.order

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.navigation.navOptions
import androidx.core.view.isVisible
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentPaymentBinding
import com.fandazi.app.feature.ordering.OrderStatus
import com.fandazi.app.feature.ordering.OrderingViewModel
import com.fandazi.app.feature.ordering.OrderingViewModelFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** 支付页面不展示助手入口，避免悬浮入口取得支付操作权限。 */
class PaymentFragment : Fragment(R.layout.fragment_payment) {
    private val viewModel: OrderingViewModel by activityViewModels {
        val activity = requireActivity() as MainActivity
        OrderingViewModelFactory(activity.orderingRepository(), activity.cartStateStore(), activity.cartOwner())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentPaymentBinding.bind(view)
        if (viewModel.uiState.value.cartDraft != null && viewModel.uiState.value.selectedAddress == null) {
            viewModel.prepareCheckout()
        }
        binding.paymentBack.setOnClickListener { view.findNavController().navigateUp() }
        var method = 0
        fun renderMethods() {
            listOf(binding.paymentBalance, binding.paymentBank, binding.paymentQuick).forEachIndexed { index, item ->
                item.setBackgroundResource(if (index == method) R.drawable.dish_option_selected else R.drawable.dish_option_unselected)
                val (icon, name, description) = when (index) {
                    0 -> Triple("👛", "模拟余额", "使用演示余额支付，方便快捷")
                    1 -> Triple("💳", "演示银行卡", "使用演示银行卡支付")
                    else -> Triple("⚡", "模拟免密", "演示一键免密支付，快速完成")
                }
                item.text = "$icon  $name\n      $description                         ${if (index == method) "●" else "○"}"
            }
        }
        binding.paymentBalance.setOnClickListener { method = 0; renderMethods() }
        binding.paymentBank.setOnClickListener { method = 1; renderMethods() }
        binding.paymentQuick.setOnClickListener { method = 2; renderMethods() }
        renderMethods()
        binding.paymentPrimary.setOnClickListener {
            if (viewModel.uiState.value.currentOrder == null) viewModel.createOrder() else viewModel.payOrder()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val address = state.selectedAddress
                    val quote = state.quote
                    val order = state.currentOrder
                    if (state.checkoutReady) viewModel.consumeCheckoutReady()
                    val lines = order?.items ?: state.cartLines
                    val first = lines.firstOrNull()
                    binding.paymentDishName.text = first?.dishName ?: "本次订单"
                    binding.paymentDishOptions.text = first?.let { "${it.optionSummary.ifBlank { "标准规格" }} · ${lines.sumOf { line -> line.quantity }} 份" }.orEmpty()
                    binding.paymentAmountTop.text = "¥${order?.total ?: quote?.total ?: "--"}"
                    binding.paymentAddress.text = "📍  ${address?.recipient ?: order?.recipient ?: "正在读取地址"}  ${address?.detail ?: order?.detail.orEmpty()}"
                    binding.paymentSummary.text = if (quote == null && order == null) "正在重新校验价格、库存与地址…" else {
                        val subtotal = order?.subtotal ?: quote?.subtotal
                        val fee = order?.deliveryFee ?: quote?.deliveryFee
                        val discount = order?.discount ?: quote?.discount
                        val total = order?.total ?: quote?.total
                        "商品小计　¥$subtotal\n配送费　　¥$fee\n优惠　　　-¥$discount\n合计　　　¥$total"
                    }
                    val pending = order?.status == OrderStatus.PENDING_PAYMENT
                    binding.paymentTitle.text = if (order == null) "确认订单" else "订单支付"
                    binding.paymentMethods.isVisible = order != null
                    binding.paymentNotice.isVisible = order != null
                    binding.paymentPrimary.isEnabled = !state.isSubmittingOrder && if (order == null) quote != null && address != null else pending
                    binding.paymentPrimary.alpha = if (binding.paymentPrimary.isEnabled) 1f else .55f
                    binding.paymentPrimary.text = when {
                        state.isSubmittingOrder -> "处理中…"
                        order == null -> "创建待支付订单"
                        else -> "支付 ¥${order.total}"
                    }
                    binding.paymentPrimary.contentDescription = binding.paymentPrimary.text
                    binding.paymentStatus.text = state.error.orEmpty()
                    if (order != null && order.status != OrderStatus.PENDING_PAYMENT &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        !parentFragmentManager.isStateSaved &&
                        view.findNavController().currentDestination?.id == R.id.paymentFragment
                    ) {
                        view.findNavController().navigate(R.id.ordersFragment, null, navOptions {
                            launchSingleTop = true
                            popUpTo(R.id.paymentFragment) { inclusive = true }
                        })
                    }
                }
            }
        }
    }
}
