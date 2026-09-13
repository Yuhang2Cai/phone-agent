package com.fandazi.app.feature.order

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentOrdersBinding
import com.fandazi.app.feature.agent.openAgentAssistant
import com.fandazi.app.feature.agent.AgentSurface
import com.fandazi.app.feature.ordering.OrderingViewModel
import com.fandazi.app.feature.ordering.OrderingViewModelFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class OrdersFragment : Fragment(R.layout.fragment_orders) {
    private val viewModel: OrderingViewModel by activityViewModels {
        OrderingViewModelFactory((requireActivity() as MainActivity).orderingRepository())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentOrdersBinding.bind(view)
        binding.agentFab.setOnClickListener { openAgentAssistant(AgentSurface.ORDERS) }
        binding.mockPaymentButton.setOnClickListener { view.findNavController().navigate(R.id.paymentFragment) }
        viewModel.refreshOrders()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.orderList.removeAllViews()
                    val orders = state.orders.ifEmpty { listOfNotNull(state.currentOrder) }
                    orders.forEach { order ->
                        TextView(requireContext()).apply {
                            text = "订单 #${order.id} · ${order.status}\n${order.items.joinToString { "${it.dishName} ×${it.quantity}" }}\n${order.timeline.joinToString(" → ")}"
                            textSize = 16f
                            setPadding(0, 18, 0, 18)
                            setOnClickListener { viewModel.selectOrder(order.id) }
                            binding.orderList.addView(this)
                        }
                    }
                    binding.orderStatus.text = state.error ?: state.currentOrder?.timeline?.joinToString(" → ") ?: "暂无订单，先完成一笔模拟支付"
                }
            }
        }
    }
}
