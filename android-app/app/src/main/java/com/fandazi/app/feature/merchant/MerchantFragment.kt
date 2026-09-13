package com.fandazi.app.feature.merchant

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
import com.fandazi.app.databinding.FragmentMerchantBinding
import com.fandazi.app.feature.agent.openAgentAssistant
import com.fandazi.app.feature.agent.AgentSurface
import com.fandazi.app.feature.ordering.OrderingViewModel
import com.fandazi.app.feature.ordering.OrderingViewModelFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MerchantFragment : Fragment(R.layout.fragment_merchant) {
    private val viewModel: OrderingViewModel by activityViewModels {
        OrderingViewModelFactory((requireActivity() as MainActivity).orderingRepository())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentMerchantBinding.bind(view)
        binding.agentFab.setOnClickListener { openAgentAssistant(AgentSurface.MERCHANT) }
        binding.checkoutButton.setOnClickListener {
            viewModel.selectDefaultAddress()
            view.findNavController().navigate(R.id.paymentFragment)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.title.text = state.selectedMerchant?.name ?: getString(R.string.merchant_page)
                    binding.menuItems.removeAllViews()
                    state.menu.forEach { dish ->
                        TextView(requireContext()).apply {
                            val option = dish.options.firstOrNull { it.available }
                            text = buildString {
                                append("${dish.name}  ¥${dish.price}\n")
                                append(dish.description.ifBlank { dish.category })
                                if (option != null) append("\n可选：${option.groupName} - ${option.optionName}")
                            }
                            textSize = 16f
                            setPadding(0, 18, 0, 18)
                            setOnClickListener {
                                viewModel.selectDish(dish.id)
                                option?.let { viewModel.selectOption(dish.id, it.id) }
                                viewModel.addSelectedDish()
                            }
                            binding.menuItems.addView(this)
                        }
                    }
                    val quote = state.quote
                    binding.cartSummary.text = when {
                        state.error != null -> state.error
                        quote != null -> "购物车已校验：${quote.lines.joinToString { "${it.dishName} ×${it.quantity}" }}，合计 ¥${quote.total}"
                        state.isLoading -> "正在同步购物车..."
                        else -> "点选菜品后会创建候选购物车并实时校验金额"
                    }
                }
            }
        }
    }
}
