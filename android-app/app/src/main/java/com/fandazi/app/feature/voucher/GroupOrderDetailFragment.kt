// 团购订单详情：读取本人订单的付款快照、状态和全部券，依赖统一仓库、图片与登录恢复入口。
package com.fandazi.app.feature.voucher

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.image.CatalogImageMetadataRefresh
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.core.time.BusinessDisplay
import com.fandazi.app.core.navigation.openRegisteredBusinessRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** UI11：公开订单号来自导航；只读查询和显式点击导航，不支付、不退款或核销。 */
class GroupOrderDetailFragment : Fragment(R.layout.fragment_group_order_detail) {
    private val repository by lazy { (requireActivity().application as FandaziApplication).voucherRepository }
    private var loading: Job? = null
    private var expanded = false

    /** 首次和返回都重新读取本人事实；保留用户展开券列表的意图，资格重新取接口。 */
    override fun onResume() {
        super.onResume()
        view?.let(::load)
    }

    /** 原地重试始终使用原导航 ID，不能在失败时退回固定订单。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.group_order_retry).setOnClickListener { load(view) }
    }

    /** 校验输入、清理旧视图、读取本人资源；401 清除失效身份并登录返回原订单。 */
    private fun load(view: View) {
        if (loading?.isActive == true) return
        val orderId = requireArguments().getString("orderId").orEmpty()
        val status = view.findViewById<TextView>(R.id.group_order_detail_status)
        view.findViewById<View>(R.id.group_order_body).isVisible = false
        view.findViewById<View>(R.id.group_order_retry).isVisible = false
        status.isVisible = true
        status.text = "正在加载订单"
        loading = viewLifecycleOwner.lifecycleScope.launch {
            when (val result = repository.getOrder(orderId)) {
                is ApiResult.Success -> render(view, result.value)
                is ApiResult.Failure -> {
                    // 失败不能保留可用券和旧资格；只有真实 401 进入登录，其余错误允许原地重试。
                    if ((result.error as? ApiError.Http)?.code == 401) {
                        (requireActivity() as MainActivity).openPurchaseLogin(R.id.groupOrderDetailFragment,
                            Bundle().apply { putString("orderId", orderId) }, true)
                    } else {
                        status.text = "订单详情加载失败，请重试"
                        view.findViewById<View>(R.id.group_order_retry).isVisible = true
                    }
                }
            }
        }
    }

    /** 渲染真实订单、图片及每张独立券；金额与资格均直接消费服务端字段。 */
    private fun render(view: View, order: GroupOrderSummary) {
        view.findViewById<View>(R.id.group_order_detail_status).isVisible = false
        view.findViewById<View>(R.id.group_order_body).isVisible = true
        view.findViewById<TextView>(R.id.group_order_number).text = "订单号 " + order.orderId
        view.findViewById<TextView>(R.id.group_order_state).text = VoucherPresentation.orderStatus(order.status)
        // 跨设备、Agent或旧入口创建的秒杀订单也能按原订单恢复，不依赖本机创建回执。
        view.findViewById<TextView>(R.id.group_order_seckill).apply {
            isVisible=order.activityId!=null
            text=if(order.status=="PENDING_PAYMENT")"继续支付秒杀订单" else "查看秒杀订单"
            setOnClickListener {order.activityId?.let {activityId->it.findNavController().navigate(R.id.seckillFragment,Bundle().apply {putString("activityId",activityId);putString("orderId",order.orderId)})}}
        }
        view.findViewById<TextView>(R.id.group_order_state_description).text = when {
            order.status == "PENDING_PAYMENT" -> "订单尚未支付"
            order.paidTotalCents != null -> "已支付；券状态及可操作资格以当前记录为准"
            else -> "当前没有付款记录"
        }
        view.findViewById<TextView>(R.id.group_order_store).text = order.storeName
        view.findViewById<TextView>(R.id.group_order_deal).text = order.dealTitle
        view.findViewById<TextView>(R.id.group_order_detail_total).text = "数量 ${order.quantity}  " + VoucherPresentation.amount(order)
        // 实付快照是付款事实，支付时间只说明时间是否可得；历史已付款记录可能没有paidAt。
        // 不用券状态或当前时间补造支付记录，也不能因时间缺失把已支付误写成未支付。
        val paymentRecord = when {
            order.paidTotalCents != null && order.paidAt != null -> "已支付 · 模拟支付 · ${BusinessDisplay.time(order.paidAt)}"
            order.paidTotalCents != null -> "已支付 · 支付时间暂未记录"
            order.status == "PENDING_PAYMENT" -> "未支付"
            else -> "付款记录暂未提供"
        }
        view.findViewById<TextView>(R.id.group_order_info).text = listOf(
            "订单编号  ${order.orderId}", "下单时间  ${BusinessDisplay.time(order.createdAt)}",
            "支付记录  $paymentRecord",
            "有效期  ${BusinessDisplay.time(order.expiresAt)}").joinToString("\n")
        view.findViewById<TextView>(R.id.group_order_rules).text = order.dealDescription?.takeIf(String::isNotBlank) ?: "使用说明暂未提供"
        // 缺图仅读取本对象的图片字段，不重绘资格/表单，也不准备报价或确认凭证。
        CatalogImageMetadataRefresh.bind(view.findViewById(R.id.group_order_image), order.imageUrl, order.imageAlt) {
            (repository.getOrder(order.orderId) as? ApiResult.Success)?.value?.let { it.imageUrl to it.imageAlt }
        }
        val container = view.findViewById<LinearLayout>(R.id.group_voucher_list)
        container.removeAllViews()
        // 券身份逐项绑定闭包；序号只用于可读选择，不能用下标生成资源 ID。
        order.vouchers.forEachIndexed { index, voucher ->
            val item = LayoutInflater.from(requireContext()).inflate(R.layout.item_voucher_row, container, false)
            item.findViewById<TextView>(R.id.voucher_row_title).text = "团购券 ${index + 1}\n${voucher.dealTitle}"
            item.findViewById<TextView>(R.id.voucher_row_status).text = VoucherPresentation.voucherStatus(voucher.status)
            item.contentDescription = "团购券 ${index + 1} ${voucher.publicId}"
            item.setOnClickListener { it.findNavController().navigate(R.id.voucherDetailFragment,
                Bundle().apply { putString("voucherId", voucher.publicId) }) }
            container.addView(item)
        }
        container.isVisible = expanded
        view.findViewById<TextView>(R.id.group_order_show_vouchers).apply {
            isEnabled = order.vouchers.isNotEmpty()
            text = if (order.vouchers.isEmpty()) "暂无团购券" else "查看团购券"
            setOnClickListener { expanded = !expanded; container.isVisible = expanded }
        }
        // 退款入口只认后端 canRefund；多券明确选择后进入券详情，避免默认退款首券。
        view.findViewById<TextView>(R.id.group_order_refund).apply {
            val eligible = order.vouchers.filter { it.canRefund }
            isVisible = eligible.isNotEmpty()
            setOnClickListener {
                if (eligible.size == 1) it.openRegisteredBusinessRoute("refundRequestFragment",
                    Bundle().apply { putString("voucherId", eligible.single().publicId) }, "退款申请将在售后功能启用后开放")
                else { expanded = true; container.isVisible = true }
            }
        }
        // 原 Task8 评价导航保留，入口仍只消费服务端 canReview；本轮不提交评价。
        view.findViewById<TextView>(R.id.group_order_review).apply {
            isVisible = order.canReview
            setOnClickListener { it.openRegisteredBusinessRoute("reviewFragment",
                Bundle().apply { putString("orderId", order.orderId) }, "评价页面将在售后功能启用后开放") }
        }
    }
}
