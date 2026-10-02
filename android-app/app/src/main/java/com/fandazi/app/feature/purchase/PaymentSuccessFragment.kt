// UI-09 结果模块：回读已支付订单与真实券列表，依赖购买仓库和现有券详情导航。
package com.fandazi.app.feature.purchase

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.image.AfterSaleImageLoader
import kotlinx.coroutines.launch

/** 成功状态必须由真实 PAID 快照证明，不能仅根据进入此页面宣称付款或生成券。 */
class PaymentSuccessFragment : Fragment(R.layout.fragment_payment_success) {
    private val viewModel: PurchaseViewModel by viewModels { PurchaseViewModelFactory((requireActivity().application as FandaziApplication).purchaseRepository) }
    private val orderId get() = requireArguments().getString("orderId").orEmpty()
    private var routed = false

    /** 显示真实摘要、金额与全部券身份；多券从订单集合选择，单券直接打开该券。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.payment_success_orders).setOnClickListener { findNavController().navigate(R.id.ordersFragment) }
        view.findViewById<View>(R.id.payment_success_vouchers).setOnClickListener {
            val order = viewModel.state.value.order ?: return@setOnClickListener
            if (order.status != "PAID" || order.vouchers.isEmpty()) return@setOnClickListener
            if (order.vouchers.size == 1) findNavController().navigate(R.id.voucherDetailFragment, Bundle().apply { putString("voucherId", order.vouchers.single().publicId) })
            else findNavController().navigate(R.id.groupOrderDetailFragment, Bundle().apply { putString("orderId", orderId) })
        }
        view.findViewById<View>(R.id.payment_success_detail).setOnClickListener { viewModel.loadOrder(orderId) }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val order = state.order?.takeIf { it.status == "PAID" }
                    view.findViewById<View>(R.id.payment_success_vouchers).isEnabled = order != null && order.vouchers.isNotEmpty()
                    view.findViewById<TextView>(R.id.payment_success_title).text = if (order != null) "支付成功" else "核对支付结果"
                    view.findViewById<TextView>(R.id.payment_success_detail).text = state.error?.let { purchaseError(it) + "，点击重试" }
                        ?: if (order != null) "这是一次模拟支付，不会产生真实支付。" else "正在查询订单"
                    order?.let {
                        view.findViewById<TextView>(R.id.payment_success_deal).text = it.dealTitle
                        view.findViewById<TextView>(R.id.payment_success_store).text = it.storeName
                        view.findViewById<TextView>(R.id.payment_success_amount).text = purchaseMoney(it.totalCents)
                        view.findViewById<TextView>(R.id.payment_success_count).text = "已生成 ${it.voucherCount} 张团购券"
                        view.findViewById<TextView>(R.id.payment_success_voucher_info).text = it.vouchers.mapIndexed { index, voucher ->
                            "团购券 ${index + 1}  ${it.dealTitle}\n编号  ${voucher.publicId}\n有效期至  ${com.fandazi.app.core.time.BusinessDisplay.time(voucher.expiresAt)}"
                        }.joinToString("\n\n")
                        AfterSaleImageLoader.load(view.findViewById(R.id.payment_success_image), it.imageUrl, it.imageAlt) { viewModel.loadOrder(orderId) }
                    }
                    if ((state.error as? ApiError.Http)?.code == 401 && !routed) {
                        routed = true
                        (requireActivity() as MainActivity).openPurchaseLogin(R.id.paymentSuccessFragment, Bundle().apply { putString("orderId", orderId) }, true)
                    }
                }
            }
        }
        if (viewModel.state.value.order == null) viewModel.loadOrder(orderId)
    }
}
