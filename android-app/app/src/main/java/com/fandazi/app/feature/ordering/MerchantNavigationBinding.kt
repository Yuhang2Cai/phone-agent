package com.fandazi.app.feature.ordering

import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.navOptions
import com.fandazi.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

// 三个入口只在前台消费；视图停止后请求仍留在 StateFlow，恢复时按 ID 导航并确认。
fun Fragment.bindMerchantNavigation(viewModel: OrderingViewModel, sourceDestination: Int) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch {
                viewModel.merchantNavigationRequest.collect { request ->
                    val controller = findNavController()
                    if (request != null && controller.currentDestination?.id == sourceDestination) {
                        viewModel.consumeMerchantNavigation(request.requestId) { merchantId ->
                            val currentId = controller.currentBackStackEntry?.arguments?.getLong("merchantId")
                            // 已展示同一商家时只确认；不同商家替换详情页，避免返回到共享状态已失效的旧详情。
                            if (sourceDestination != R.id.merchantFragment || currentId != merchantId) {
                                controller.navigate(R.id.merchantFragment, bundleOf("merchantId" to merchantId), navOptions {
                                    launchSingleTop = true
                                })
                            }
                        }
                    }
                }
            }
            launch {
                viewModel.uiState.map { it.pendingMerchantSwitchRequestId to it.pendingMerchantSwitch }
                    .distinctUntilChanged().collectLatest { (requestId, merchant) ->
                        if (requestId == null || merchant == null || findNavController().currentDestination?.id != sourceDestination) return@collectLatest
                        val dialog = MaterialAlertDialogBuilder(requireContext())
                            .setTitle("清空当前购物车？")
                            .setMessage("购物车中已有其他商家的菜品。清空后才能进入「${merchant.name}」。")
                            .setNegativeButton("取消") { _, _ -> viewModel.cancelMerchantSwitch(requestId) }
                            .setPositiveButton("清空并进入") { _, _ -> viewModel.confirmMerchantSwitch(requestId) }
                            .setOnCancelListener { viewModel.cancelMerchantSwitch(requestId) }
                            .show()
                        // 停止、销毁或目标替换时关闭旧弹窗；回调携带代次，不能操作后来出现的目标。
                        try {
                            awaitCancellation()
                        } finally {
                            dialog.dismiss()
                        }
                    }
            }
        }
    }
}
