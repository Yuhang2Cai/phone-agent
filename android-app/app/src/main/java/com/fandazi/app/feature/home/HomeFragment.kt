// 首页模块：管理页面交互和状态展示。
package com.fandazi.app.feature.home

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.view.isVisible
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentHomeBinding
import com.fandazi.app.feature.agent.AgentSurface
import com.fandazi.app.feature.agent.openAgentAssistant
import com.fandazi.app.feature.auth.SessionError
import com.fandazi.app.feature.ordering.OrderingViewModel
import com.fandazi.app.feature.ordering.OrderingViewModelFactory
import com.fandazi.app.feature.ordering.bindMerchantNavigation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class HomeFragment : Fragment(R.layout.fragment_home) {
    private val viewModel: OrderingViewModel by activityViewModels {
        val activity = requireActivity() as MainActivity
        OrderingViewModelFactory(activity.orderingRepository(), activity.cartStateStore(), activity.cartOwner())
    }
    private var binding: FragmentHomeBinding? = null

// 在视图创建后绑定控件、事件与初始状态。
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val viewBinding = FragmentHomeBinding.bind(view)
        binding = viewBinding
        bindMerchantNavigation(viewModel, R.id.homeFragment)
        val activity = requireActivity() as MainActivity
        val openSearch = { view.findNavController().navigate(R.id.searchFragment) }
        val openAssistant = { openAgentAssistant(AgentSurface.HOME) }

        viewBinding.homeSearchButton.setOnClickListener { openSearch() }
        viewBinding.homeHeaderSearch.setOnClickListener {
            viewBinding.homeSearchButton.isVisible = true
            viewBinding.homeHero.layoutParams = viewBinding.homeHero.layoutParams.apply {
                height = (300 * resources.displayMetrics.density).toInt()
            }
            viewBinding.homeScroll.smoothScrollTo(0, 0)
        }
        viewBinding.homeVoiceButton.setOnClickListener { openAssistant() }
        viewBinding.agentFab.setOnClickListener { openAssistant() }
        viewBinding.homeFloatingAssistant.setOnClickListener { openAssistant() }
        viewBinding.quickLightMealButton.setOnClickListener { openAssistant() }
        viewBinding.quickBudgetButton.setOnClickListener { openAssistant() }
        viewBinding.repeatOrderButton.setOnClickListener { openAssistant() }
        viewBinding.merchantPageButton.setOnClickListener { openSearch() }
        viewBinding.homeAddress.setOnClickListener { view.findNavController().navigate(R.id.profileFragment) }
        viewBinding.homeNotification.setOnClickListener {
            Toast.makeText(requireContext(), R.string.home_notification, Toast.LENGTH_SHORT).show()
        }

        bindQuickCategories(viewBinding)
        bindNearbyCards(viewBinding)
        // 首页后续请求距离排序前统一经过策略校验，定位拒绝时不能展示伪造距离。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                activity.applicationSession().error.collect { error ->
                    val message = when (error) {
                        SessionError.StorageReadFailed -> getString(R.string.session_read_failed)
                        SessionError.StorageClearFailed -> getString(R.string.logout_failed_retry)
                        SessionError.StorageUnavailable, SessionError.StorageWriteFailed -> getString(R.string.session_storage_unavailable)
                        null -> null
                    }
                    message?.let { Toast.makeText(requireContext(), it, Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

// 处理 `bindQuickCategories` 相关逻辑。
    private fun bindQuickCategories(viewBinding: FragmentHomeBinding) {
        val categories = listOf(
            Triple(viewBinding.homeCategoryHealthy, "健康轻食", "轻盈无负担"),
            Triple(viewBinding.homeCategorySpicy, "无辣不欢", "干饭人必选"),
            Triple(viewBinding.homeCategorySoup, "暖心汤面", "治愈一整天"),
            Triple(viewBinding.homeCategoryTea, "下午茶", "甜一点吧")
        )
        val images = listOf(
            R.drawable.home_merchant_2,
            R.drawable.home_merchant_1,
            R.drawable.home_merchant_3,
            R.drawable.home_merchant_2
        )
        categories.forEachIndexed { index, (category, title, subtitle) ->
            category.categoryTitle.text = title
            category.categorySubtitle.text = subtitle
            category.categoryImage.setImageResource(images[index])
            category.root.contentDescription = "$title，$subtitle"
            category.root.setOnClickListener { openAgentAssistant(AgentSurface.HOME) }
        }
    }

// 处理 `bindNearbyCards` 相关逻辑。
    private fun bindNearbyCards(viewBinding: FragmentHomeBinding) {
        val cards = listOf(
            Triple(R.string.nearby_merchant_1, R.string.nearby_merchant_1_meta, R.string.nearby_merchant_1_tags),
            Triple(R.string.nearby_merchant_2, R.string.nearby_merchant_2_meta, R.string.nearby_merchant_2_tags),
            Triple(R.string.nearby_merchant_3, R.string.nearby_merchant_3_meta, R.string.nearby_merchant_3_tags)
        )
        val images = listOf(R.drawable.home_merchant_1, R.drawable.home_merchant_2, R.drawable.home_merchant_3)
        val distances = listOf("● 1.2 km", "● 1.6 km", "● 2.1 km")
        listOf(viewBinding.homeMerchantOne, viewBinding.homeMerchantTwo, viewBinding.homeMerchantThree).forEachIndexed { index, card ->
            card.merchantName.setText(cards[index].first)
            card.merchantMeta.setText(cards[index].second)
            card.merchantTags.setText(cards[index].third)
            card.merchantFoodImage.setImageResource(images[index])
            card.merchantBackgroundImage.setImageResource(images[index])
            card.merchantDistance.text = distances[index].replace("● ", "● 距离 ")
            card.root.contentDescription = getString(cards[index].first)
            // 首页示例卡片映射到演示数据中的稳定 publicId，直接进入新的团购门店详情页。
            card.root.setOnClickListener {
                val arguments = Bundle().apply {
                    putString("storePublicId", "store-${String.format("%03d", index + 1)}")
                }
                requireView().findNavController().navigate(R.id.storeFragment, arguments)
            }
        }
    }

// 在视图销毁时释放视图相关资源。
    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
