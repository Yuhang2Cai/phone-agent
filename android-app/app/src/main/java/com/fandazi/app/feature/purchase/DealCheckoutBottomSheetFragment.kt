// UI-07 购买确认模块：在真实套餐详情上展示底部弹窗，依赖后端报价和统一登录返回。
package com.fandazi.app.feature.purchase

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.feature.auth.SessionState
import com.fandazi.app.core.image.AfterSaleImageLoader
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

/** 只准备报价；用户提交后创建待支付订单，关闭弹窗不执行购买。 */
class DealCheckoutBottomSheetFragment : BottomSheetDialogFragment(R.layout.sheet_deal_checkout) {
    private val viewModel: PurchaseViewModel by viewModels { PurchaseViewModelFactory((requireActivity().application as FandaziApplication).purchaseRepository) }
    private val dealId get() = requireArguments().getString("dealPublicId").orEmpty()
    private var quantity = 1
    private var routed = false

    /** 展开完整确认内容，白色底部弹窗保留套餐详情遮罩。 */
    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply { state = BottomSheetBehavior.STATE_EXPANDED; skipCollapsed = true }
    }

    /** 绑定数量与确认，首次自动报价；没有完整报价时只能重试而不能创建订单。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        quantity = savedInstanceState?.getInt("quantity") ?: requireArguments().getInt("quantity", 1)
        val activity = requireActivity() as MainActivity
        if (activity.applicationSession().state.value !is SessionState.Authenticated) { login(activity, false); return }
        view.findViewById<View>(R.id.checkout_close).setOnClickListener { findNavController().popBackStack() }
        view.findViewById<View>(R.id.checkout_minus).setOnClickListener { changeQuantity(-1) }
        view.findViewById<View>(R.id.checkout_plus).setOnClickListener { changeQuantity(1) }
        view.findViewById<View>(R.id.checkout_action).setOnClickListener {
            if (viewModel.state.value.quote == null) viewModel.quote(dealId, quantity) else viewModel.createOrder()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val quote = state.quote
                    view.findViewById<TextView>(R.id.checkout_action).apply {
                        isEnabled = !state.loading
                        alpha = if (state.loading) .5f else 1f
                        text = if (state.loading) "正在处理" else if (quote == null) "重新报价" else "提交订单"
                    }
                    // 报价加载或拒绝后清除所有旧金额，防止用户把旧数量金额误认为新授权。
                    view.findViewById<TextView>(R.id.checkout_summary).text = state.error?.let(::purchaseError).orEmpty()
                    view.findViewById<TextView>(R.id.checkout_subtotal).text = quote?.let { purchaseMoney(it.subtotalCents) }.orEmpty()
                    view.findViewById<TextView>(R.id.checkout_discount).text = quote?.let { "-${purchaseMoney(it.discountCents)}" }.orEmpty()
                    view.findViewById<TextView>(R.id.checkout_total).text = quote?.let { purchaseMoney(it.totalCents) }.orEmpty()
                    view.findViewById<TextView>(R.id.checkout_price).text = quote?.let { purchaseMoney(it.totalCents) }.orEmpty()
                    view.findViewById<TextView>(R.id.checkout_quantity).text = quantity.toString()
                    view.findViewById<View>(R.id.checkout_minus).isEnabled = !state.loading && quote != null && quantity > quote.minQuantity
                    view.findViewById<View>(R.id.checkout_plus).isEnabled = !state.loading && quote != null && quantity < quote.maxQuantity
                    quote?.let {
                        quantity = it.quantity
                        view.findViewById<TextView>(R.id.checkout_deal).text = it.dealTitle
                        view.findViewById<TextView>(R.id.checkout_rules).text = it.usageRules
                        view.findViewById<TextView>(R.id.checkout_subtotal_label).text = "商品总价（共${it.quantity}件）"
                        AfterSaleImageLoader.load(view.findViewById(R.id.checkout_image), it.imageUrl, it.imageAlt) { viewModel.quote(dealId, quantity) }
                    }
                    // 类型化 401 才进入登录；保留套餐与数量并废弃旧会话、报价和凭证。
                    if ((state.error as? ApiError.Http)?.code == 401 && !routed) { login(activity, true); return@collect }
                    if (state.order != null && !routed) {
                        routed = true
                        findNavController().navigate(R.id.groupBuyPaymentFragment, Bundle().apply { putString("orderId", state.order.orderId) })
                    }
                }
            }
        }
        if (viewModel.state.value.quote == null && !viewModel.state.value.loading) viewModel.quote(dealId, quantity)
    }

    /** 只允许服务端范围内调节，改变数量后立即重新报价而非端上乘价格。 */
    private fun changeQuantity(delta: Int) {
        val quote = viewModel.state.value.quote ?: return
        val next = quantity + delta
        if (!viewModel.state.value.loading && next in quote.minQuantity..quote.maxQuantity) { quantity = next; viewModel.quote(dealId, next) }
    }

    /** 登录返回重新加载原套餐，原数量只是用户输入，价格和资格重新取报价。 */
    private fun login(activity: MainActivity, expired: Boolean) {
        routed = true
        activity.openPurchaseLogin(R.id.dealDetailFragment, Bundle().apply {
            putString("dealPublicId", dealId); putString("storePublicId", requireArguments().getString("storePublicId").orEmpty())
            putInt("quantity", quantity); putBoolean("resumePurchase", true)
        }, expired)
    }

    /** 配置变化只保留用户数量，不把短期授权序列化为路由金额。 */
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("quantity", quantity); super.onSaveInstanceState(outState) }
}
