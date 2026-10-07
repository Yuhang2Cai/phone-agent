// 订单模块：管理页面交互和状态展示。
package com.fandazi.app.feature.order

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.image.ImageLoader
import com.fandazi.app.databinding.FragmentCartBinding
import com.fandazi.app.feature.ordering.OrderingUiState
import com.fandazi.app.feature.ordering.OrderingViewModel
import com.fandazi.app.feature.ordering.OrderingViewModelFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class CartFragment : Fragment(R.layout.fragment_cart) {
    private val viewModel: OrderingViewModel by activityViewModels {
        val activity = requireActivity() as MainActivity
        OrderingViewModelFactory(activity.orderingRepository(), activity.cartStateStore(), activity.cartOwner())
    }

// 在视图创建后绑定控件、事件与初始状态。
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentCartBinding.bind(view)
        binding.cartBack.setOnClickListener { view.findNavController().navigateUp() }
        binding.cartClear.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle("清空购物车？")
                .setMessage("已选菜品会全部移除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空") { _, _ -> viewModel.clearCart() }
                .show()
        }
        binding.cartCheckout.setOnClickListener { viewModel.prepareCheckout() }
        binding.cartAddress.setOnClickListener { (requireActivity() as MainActivity).openProtectedDestination(R.id.addressFragment) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    render(binding, state)
                    navigateToPaymentIfReady(view, state)
                }
            }
        }
    }

// 渲染当前数据。
    private fun render(binding: FragmentCartBinding, state: OrderingUiState) {
        val quote = state.quote
        binding.cartMerchantName.text = state.selectedMerchant?.name ?: "本次点餐"
        binding.cartLines.removeAllViews()
        state.cartLines.forEachIndexed { index, line ->
            val item = LayoutInflater.from(requireContext()).inflate(R.layout.item_cart_line, binding.cartLines, false)
            item.findViewById<TextView>(R.id.cart_dish_name).text = line.dishName
            item.findViewById<TextView>(R.id.cart_dish_options).text = line.optionSummary.ifBlank { "标准规格" }
            item.findViewById<TextView>(R.id.cart_dish_price).text = "¥${line.unitPrice}"
            item.findViewById<TextView>(R.id.cart_quantity).text = line.quantity.toString()
            val dish = state.menu.firstOrNull { it.id == line.dishId }
            ImageLoader.load(item.findViewById<ImageView>(R.id.cart_dish_image), dish?.imageUrl, listOf(R.drawable.home_merchant_3, R.drawable.home_merchant_1, R.drawable.home_merchant_2)[index % 3], R.drawable.home_merchant_3)
            item.findViewById<View>(R.id.cart_minus).setOnClickListener { viewModel.updateCartLine(line.dishId, line.optionIds, line.quantity - 1) }
            item.findViewById<View>(R.id.cart_plus).setOnClickListener { viewModel.updateCartLine(line.dishId, line.optionIds, line.quantity + 1) }
            binding.cartLines.addView(item)
        }
        binding.cartEmpty.isVisible = state.cartLines.isEmpty()
        binding.cartClear.isEnabled = state.cartLines.isNotEmpty() && !state.isLoading
        binding.cartFeeSummary.text = if (quote == null) "商品小计　¥0.00\n配送费　　¥0.00\n优惠　　　-¥0.00" else
            "商品小计　¥${quote.subtotal}\n配送费　　¥${quote.deliveryFee}\n优惠　　　-¥${quote.discount}"
        binding.cartTotal.text = "¥${quote?.total ?: "0.00"}"
        binding.cartCheckout.isEnabled = quote != null && !state.isLoading
        binding.cartCheckout.alpha = if (binding.cartCheckout.isEnabled) 1f else .55f
        binding.cartCheckout.text = if (state.isLoading) "正在校验…" else "去结算"
        binding.cartAddress.text = state.selectedAddress?.let { "📍  ${it.recipient}  ${it.detail}  ›" } ?: "📍  结算时将校验默认收货地址  ›"
        binding.cartError.text = state.error.orEmpty()
    }

// 处理 `onResume` 相关逻辑。
    override fun onResume() {
        super.onResume()
        view?.let { navigateToPaymentIfReady(it, viewModel.uiState.value) }
    }

// 处理 `navigateToPaymentIfReady` 相关逻辑。
    private fun navigateToPaymentIfReady(view: View, state: OrderingUiState) {
        if (!state.checkoutReady || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (parentFragmentManager.isStateSaved || view.findNavController().currentDestination?.id != R.id.cartFragment) return
        viewModel.consumeCheckoutReady()
        view.findNavController().navigate(R.id.paymentFragment)
    }
}
