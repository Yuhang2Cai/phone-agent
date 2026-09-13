package com.fandazi.app.feature.order

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentPaymentBinding
import com.fandazi.app.feature.ordering.OrderStatus
import com.fandazi.app.feature.ordering.OrderingViewModel
import com.fandazi.app.feature.ordering.OrderingViewModelFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Payment is intentionally a no-agent screen: payment authority must never be offered by a floating entry point. */
class PaymentFragment : Fragment(R.layout.fragment_payment) {
    private val viewModel: OrderingViewModel by activityViewModels {
        OrderingViewModelFactory((requireActivity() as MainActivity).orderingRepository())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentPaymentBinding.bind(view)
        binding.createOrderButton.setOnClickListener { viewModel.createOrder() }
        binding.payButton.setOnClickListener { viewModel.payOrder() }
        binding.ordersButton.setOnClickListener { view.findNavController().navigate(R.id.ordersFragment) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val address = state.selectedAddress
                    val quote = state.quote
                    val order = state.currentOrder
                    binding.paymentSummary.text = buildString {
                        append("配送地址：${address?.detail ?: "正在读取默认地址"}\n")
                        append("校验金额：${quote?.total?.let { "¥$it" } ?: "待校验"}\n")
                        append("订单状态：${order?.status ?: "未创建"}")
                    }
                    binding.createOrderButton.isEnabled = quote != null && address != null && order == null
                    binding.payButton.isEnabled = order?.status == OrderStatus.PENDING_PAYMENT
                    binding.paymentStatus.text = state.error ?: order?.timeline?.joinToString(" → ") ?: "请先创建订单，再完成模拟支付"
                }
            }
        }
    }
}
