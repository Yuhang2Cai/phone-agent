// 搜索模块：管理页面交互和状态展示。
package com.fandazi.app.feature.search

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.image.ImageLoader
import com.fandazi.app.databinding.FragmentSearchBinding
import com.fandazi.app.feature.agent.AgentSurface
import com.fandazi.app.feature.agent.openAgentAssistant
import com.fandazi.app.feature.ordering.Merchant
import com.fandazi.app.feature.ordering.OrderingViewModel
import com.fandazi.app.feature.ordering.OrderingViewModelFactory
import com.fandazi.app.feature.ordering.SearchStatus
import kotlinx.coroutines.launch

class SearchFragment : Fragment(R.layout.fragment_search) {
    private val viewModel: OrderingViewModel by activityViewModels {
        val activity = requireActivity() as MainActivity
        OrderingViewModelFactory(activity.orderingRepository(), activity.cartStateStore(), activity.cartOwner())
    }
    private var selectedFilter = SearchFilter.COMPREHENSIVE

// 在视图创建后绑定控件、事件与初始状态。
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSearchBinding.bind(view)
        binding.searchBack.setOnClickListener { view.findNavController().navigateUp() }
        binding.searchInput.requestFocus()
        binding.searchInput.post { (requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT) }
        binding.clearSearch.setOnClickListener { binding.searchInput.text?.clear() }
        binding.searchInput.doAfterTextChanged { viewModel.updateSearchKeyword(it?.toString().orEmpty()) }
        binding.searchButton.setOnClickListener { submitSearch(binding) }
        binding.aiSuggestion.setOnClickListener { openAgentAssistant(AgentSurface.SEARCH) }
        binding.agentFab.setOnClickListener { openAgentAssistant(AgentSurface.SEARCH) }
        renderFilters(binding)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(binding, state.merchants, state.searchStatus, state.error, state.keyword)
                    state.pendingMerchantSwitch?.let { target ->
                        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                            .setTitle("清空当前购物车？")
                            .setMessage("购物车中已有其他商家的菜品。清空后才能进入「${target.name}」。")
                            .setNegativeButton("取消") { _, _ -> viewModel.cancelMerchantSwitch() }
                            .setPositiveButton("清空并进入") { _, _ ->
                                viewModel.confirmMerchantSwitch()
                                requireView().findNavController().navigate(R.id.merchantFragment)
                            }
                            .setOnCancelListener { viewModel.cancelMerchantSwitch() }
                            .show()
                    }
                }
            }
        }
    }

// 处理 `submitSearch` 相关逻辑。
    private fun submitSearch(binding: FragmentSearchBinding) {
        viewModel.search(binding.searchInput.text?.toString().orEmpty().ifBlank { "清淡午餐" })
    }

// 渲染filters。
    private fun renderFilters(binding: FragmentSearchBinding) {
        binding.filterRow.removeAllViews()
        SearchFilter.entries.forEach { filter ->
            val chip = TextView(requireContext()).apply {
                id = View.generateViewId()
                text = SearchUiCopy.filterLabel(filter)
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                minWidth = 92.dp
                minHeight = 40.dp
                setPadding(14.dp, 0, 14.dp, 0)
                contentDescription = "筛选${text}"
                setOnClickListener { selectedFilter = filter; renderFilters(binding); renderCurrent(binding) }
            }
            chip.background = ContextCompat.getDrawable(requireContext(), if (filter == selectedFilter) R.drawable.search_filter_selected else R.drawable.search_filter_unselected)
            val params = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 40.dp)
            params.marginEnd = 6.dp
            binding.filterRow.addView(chip, params)
        }
    }

// 渲染current。
    private fun renderCurrent(binding: FragmentSearchBinding) {
        val state = viewModel.uiState.value
        render(binding, state.merchants, state.searchStatus, state.error, state.keyword)
    }

// 渲染当前数据。
    private fun render(binding: FragmentSearchBinding, merchants: List<Merchant>, status: SearchStatus, error: String?, keyword: String) {
        if (binding.searchInput.text.toString() != keyword) binding.searchInput.setText(keyword)
        val visible = merchants.filter { it.open || selectedFilter != SearchFilter.COMPREHENSIVE }.let { list ->
            when (selectedFilter) {
                SearchFilter.NEARBY -> list.sortedBy { it.deliveryMinutes }
                SearchFilter.TOP_RATED -> list.sortedByDescending { it.id }
                SearchFilter.FAST_DELIVERY -> list.sortedBy { it.deliveryMinutes }
                SearchFilter.COMPREHENSIVE -> list
            }
        }
        binding.resultCount.text = if (visible.isEmpty()) "" else "共 ${visible.size} 家商家  ›"
        binding.searchStatus.text = error ?: SearchUiCopy.status(status)
        binding.searchStatus.setOnClickListener(if (status == SearchStatus.NetworkError) View.OnClickListener { submitSearch(binding) } else null)
        binding.searchResults.removeAllViews()
        visible.forEach { merchant ->
            val card = LayoutInflater.from(requireContext()).inflate(R.layout.item_search_merchant, binding.searchResults, false)
            card.contentDescription = "${merchant.name}，查看商家菜单"
            card.findViewById<TextView>(R.id.merchant_name).text = merchant.name
            card.findViewById<TextView>(R.id.merchant_distance).text = "● 距离 ${String.format(java.util.Locale.US, "%.1f", 1.0 + (merchant.id % 20) / 10.0)} km"
            card.findViewById<TextView>(R.id.merchant_meta).text = "★ ${(4.6 + (merchant.id % 4) * 0.1).coerceAtMost(4.9)}   月售 ${1000 + merchant.id * 100}+  |  ${merchant.deliveryMinutes} 分钟送达"
            card.findViewById<TextView>(R.id.merchant_tags).text = "${merchant.category}   清淡健康   家常美味   现做现送"
            card.findViewById<TextView>(R.id.merchant_price).text = "¥${merchant.minimumOrder}-${merchant.minimumOrder.add(java.math.BigDecimal.TEN)}   |   起送 ¥${merchant.minimumOrder}   |   配送 ¥${merchant.deliveryFee}"
            card.findViewById<TextView>(R.id.merchant_description).text = "“食材新鲜，味道清淡，适合工作日午餐！”"
            val cover = listOf(R.drawable.home_merchant_3, R.drawable.home_merchant_2, R.drawable.home_merchant_1)[((merchant.id - 1) % 3).toInt()]
            ImageLoader.load(card.findViewById(R.id.merchant_cover), merchant.imageUrl, cover, cover)
            card.setOnClickListener {
                val requiresSwitch = viewModel.uiState.value.cartDraft?.merchantId?.let { it != merchant.id } == true
                viewModel.openMerchant(merchant.id)
                if (!requiresSwitch) requireView().findNavController().navigate(R.id.merchantFragment)
            }
            binding.searchResults.addView(card)
        }
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
