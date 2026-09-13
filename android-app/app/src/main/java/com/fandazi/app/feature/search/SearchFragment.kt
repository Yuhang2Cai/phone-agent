package com.fandazi.app.feature.search

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
import com.fandazi.app.databinding.FragmentSearchBinding
import com.fandazi.app.feature.agent.openAgentAssistant
import com.fandazi.app.feature.agent.AgentSurface
import com.fandazi.app.feature.ordering.OrderingViewModel
import com.fandazi.app.feature.ordering.OrderingViewModelFactory
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SearchFragment : Fragment(R.layout.fragment_search) {
    private val viewModel: OrderingViewModel by activityViewModels {
        OrderingViewModelFactory((requireActivity() as MainActivity).orderingRepository())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSearchBinding.bind(view)
        binding.agentFab.setOnClickListener { openAgentAssistant(AgentSurface.SEARCH) }
        binding.searchButton.setOnClickListener {
            viewModel.search(binding.searchInput.text?.toString().orEmpty().ifBlank { "清淡" })
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.searchResults.removeAllViews()
                    state.merchants.forEach { merchant ->
                        TextView(requireContext()).apply {
                            text = "${merchant.name}\n${merchant.category} · ${merchant.deliveryMinutes}分钟 · 起送 ¥${merchant.minimumOrder}"
                            textSize = 17f
                            setPadding(0, 18, 0, 18)
                            setOnClickListener {
                                viewModel.openMerchant(merchant.id)
                                view.findNavController().navigate(R.id.merchantFragment)
                            }
                            binding.searchResults.addView(this)
                        }
                    }
                    binding.searchStatus.text = state.error ?: if (state.isLoading) "正在搜索真实商家..." else "选择一家商家查看菜单"
                }
            }
        }
    }
}
