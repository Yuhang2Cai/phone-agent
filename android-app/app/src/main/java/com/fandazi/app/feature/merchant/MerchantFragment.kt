// 商家菜单模块：管理页面交互和状态展示。
package com.fandazi.app.feature.merchant

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.util.Log
import android.view.LayoutInflater
import android.app.Dialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.image.ImageLoader
import com.fandazi.app.databinding.FragmentMerchantBinding
import com.fandazi.app.feature.agent.openAgentAssistant
import com.fandazi.app.feature.agent.AgentSurface
import com.fandazi.app.feature.ordering.OrderingViewModel
import com.fandazi.app.feature.ordering.OrderingViewModelFactory
import com.fandazi.app.feature.ordering.bindMerchantNavigation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MerchantFragment : Fragment(R.layout.fragment_merchant) {
    private val viewModel: OrderingViewModel by activityViewModels {
        val activity = requireActivity() as MainActivity
        OrderingViewModelFactory(activity.orderingRepository(), activity.cartStateStore(), activity.cartOwner())
    }

// 在视图创建后绑定控件、事件与初始状态。
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentMerchantBinding.bind(view)
        bindMerchantNavigation(viewModel, R.id.merchantFragment)
        // 进程重建后共享状态可能为空，按路由 ID 恢复；已有请求仍在加载时不重复发起。
        val merchantId = requireArguments().getLong("merchantId")
        if (viewModel.uiState.value.selectedMerchant?.id != merchantId && !viewModel.uiState.value.isLoading &&
            viewModel.uiState.value.pendingMerchantSwitch == null && viewModel.merchantNavigationRequest.value == null
        ) viewModel.openMerchant(merchantId)
        binding.merchantBack.setOnClickListener { view.findNavController().navigateUp() }
        binding.agentFab.setOnClickListener { openAgentAssistant(AgentSurface.MERCHANT) }
        val openCart = View.OnClickListener {
            (requireActivity() as MainActivity).openProtectedDestination(R.id.cartFragment)
        }
        binding.cartSummary.setOnClickListener(openCart)
        binding.cartCheckout.setOnClickListener(openCart)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val selectedMerchant = state.selectedMerchant
                    binding.title.text = "饭搭子"
                    binding.merchantName.text = selectedMerchant?.name ?: "清粥小菜"
                    binding.merchantMeta.text = "★ 4.8  |  ${selectedMerchant?.deliveryMinutes ?: 25} 分钟送达"
                    binding.merchantSubtitle.text = "${selectedMerchant?.category ?: "清淡健康"} · 家常美味 · 现做现送"
                    ImageLoader.load(binding.merchantCover, selectedMerchant?.imageUrl, R.drawable.home_merchant_3, R.drawable.home_merchant_3)
                    ImageLoader.load(binding.merchantAvatar, selectedMerchant?.imageUrl, R.drawable.home_merchant_3, R.drawable.home_merchant_3)
                    binding.menuItems.removeAllViews()
                    binding.categoryList.removeAllViews()
                    Log.d("FandaziMerchant", "render merchant=${state.selectedMerchant?.name} menu=${state.menu.size} loading=${state.isLoading}")
                    MerchantMenuPresentation.sections(state.menu).forEachIndexed { sectionIndex, section ->
                        TextView(requireContext()).apply {
                            text = section.category
                            textSize = 14f
                            gravity = android.view.Gravity.CENTER
                            minHeight = (64 * resources.displayMetrics.density).toInt()
                            setTextColor(requireContext().getColor(if (sectionIndex == 0) R.color.brand_orange else R.color.brand_ink))
                            if (sectionIndex == 0) setBackgroundResource(R.drawable.merchant_category_selected)
                            contentDescription = "分类${section.category}"
                            binding.categoryList.addView(this)
                        }
                        section.dishes.forEachIndexed { dishIndex, menuDish ->
                            val dish = menuDish.dish
                            val card = LayoutInflater.from(requireContext()).inflate(R.layout.item_merchant_dish, binding.menuItems, false)
                            card.findViewById<TextView>(R.id.dish_name).text = dish.name
                            card.findViewById<TextView>(R.id.dish_description).text = dish.description.ifBlank { dish.category }
                            card.findViewById<TextView>(R.id.dish_tags).text = if (menuDish.isAddable) "现做现送" else "已售罄"
                            card.findViewById<TextView>(R.id.dish_price).text = "¥${dish.price}"
                            card.findViewById<android.widget.ImageView>(R.id.dish_image).setImageResource(
                                listOf(R.drawable.home_merchant_3, R.drawable.home_merchant_1, R.drawable.home_merchant_2)[dishIndex % 3]
                            )
                            card.contentDescription = "${dish.name}，${if (menuDish.isAddable) "加入购物车" else "已售罄"}"
                            val add = card.findViewById<TextView>(R.id.dish_add)
                            add.isEnabled = menuDish.isAddable
                            add.contentDescription = if (menuDish.isAddable) "加入${dish.name}" else "${dish.name}已售罄"
                            add.setOnClickListener {
                                viewModel.selectDish(dish.id)
                                val availableOptions = dish.options.filter { it.available }
                                if (availableOptions.isEmpty()) {
                                    viewModel.addSelectedDish()
                                } else {
                                    DishOptionsBottomSheet.newInstance(dish.id).show(parentFragmentManager, "dish-options")
                                }
                            }
                            binding.menuItems.addView(card)
                        }
                    }
                    val quote = state.quote
                    binding.cartSummary.text = when {
                        state.error != null -> state.error
                        quote != null -> MerchantMenuPresentation.cartSummary(quote.lines.sumOf { it.quantity }, quote.total)
                        state.isLoading -> "正在同步购物车..."
                        else -> "点选菜品后会创建候选购物车并实时校验金额"
                    }
                    binding.cartCheckout.text = if (quote == null) "查看购物车" else "去购物车"
                    binding.cartCheckout.contentDescription = binding.cartCheckout.text
                }
            }
        }
    }

}
