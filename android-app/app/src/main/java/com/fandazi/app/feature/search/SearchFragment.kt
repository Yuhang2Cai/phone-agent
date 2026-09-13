package com.fandazi.app.feature.search

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import android.content.Context
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
import com.fandazi.app.feature.ordering.SearchStatus
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class SearchFragment : Fragment(R.layout.fragment_search) {
    private val viewModel: OrderingViewModel by activityViewModels {
        OrderingViewModelFactory((requireActivity() as MainActivity).orderingRepository())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSearchBinding.bind(view)
        binding.searchInput.requestFocus()
        (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
        binding.agentFab.setOnClickListener { openAgentAssistant(AgentSurface.SEARCH) }
        binding.filterButton.setOnClickListener { viewModel.toggleOnlyOpen() }
        binding.searchButton.setOnClickListener {
            viewModel.search(binding.searchInput.text?.toString().orEmpty().ifBlank { "清淡" })
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    binding.searchResults.removeAllViews()
                    val merchants = state.merchants.filter { !state.onlyOpen || it.open }
                    merchants.forEach { merchant ->
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
                    binding.filterButton.text = if (state.onlyOpen) "筛选：营业中" else "筛选：全部"
                    binding.searchStatus.text = state.error ?: when (state.searchStatus) {
                        SearchStatus.Idle -> "输入关键词，搜索商家或菜品"
                        SearchStatus.Searching -> "正在搜索真实商家..."
                        SearchStatus.Empty -> "没有找到匹配的商家，换个关键词试试"
                        SearchStatus.Results -> "选择一家商家查看菜单"
                        SearchStatus.NetworkError -> "网络异常，请重试"
                        SearchStatus.AuthRequired -> "登录后可查看个性化结果"
                    }
                }
            }
        }
    }
}
