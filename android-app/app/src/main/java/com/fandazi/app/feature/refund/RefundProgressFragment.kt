// 退款进度页面：每次加载均从后端读取申请状态，避免把待处理展示为已退款。
package com.fandazi.app.feature.refund

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.core.image.AfterSaleImageLoader
import com.fandazi.app.core.time.BusinessDisplay
import kotlinx.coroutines.launch

/** UI-18B：进度页只显示接口确认的状态与创建时间。 */
class RefundProgressFragment : Fragment(R.layout.fragment_refund_progress) {
    private val app by lazy { requireActivity().application as FandaziApplication }

    /** 刷新按钮原地重新查询；联系客服进入真实帮助页。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.progress_back).setOnClickListener { findNavController().navigateUp() }
        view.findViewById<View>(R.id.progress_refresh).setOnClickListener { load(view) }
        view.findViewById<View>(R.id.progress_help).setOnClickListener { findNavController().navigate(R.id.helpFaqFragment) }
        load(view)
    }

    /** 请求失败时保留当前上下文和重试入口，不显示伪造的审核结果。 */
    private fun load(view: View) {
        val requestId = requireArguments().getString("requestId").orEmpty()
        if (requestId.isBlank()) { showError(view, "缺少退款申请编号"); return }
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = app.afterSaleRepository.getRefund(requestId)) {
                is ApiResult.Success -> {
                    showError(view, null)
                    val progress = result.value
                    // 摘要与申请金额均取本次查询，不能沿用导航参数或当前套餐售价。
                    view.findViewById<TextView>(R.id.progress_store).text = progress.storeName
                    view.findViewById<TextView>(R.id.progress_deal).text = progress.dealTitle
                    view.findViewById<TextView>(R.id.progress_order).text = "订单号末8位：${progress.orderId.takeLast(8)}"
                    view.findViewById<TextView>(R.id.progress_amount).text = BusinessDisplay.money(progress.amountCents)
                    view.findViewById<TextView>(R.id.progress_reason).text = "退款原因：${progress.reason}"
                    // 缺图刷新原申请的真实摘要；失败重试不改变退款状态或展示别的订单图片。
                    AfterSaleImageLoader.load(view.findViewById(R.id.progress_image), progress.imageUrl, progress.imageAlt) { load(view) }
                    // 完整标识留在服务端和导航参数中；页面只显示末八位，避免长 UUID 挤压状态卡。
                    view.findViewById<TextView>(R.id.progress_voucher).text =
                        "退款申请编号（末8位）：${progress.requestId.takeLast(8).uppercase()}"
                    // ISO 时间带 UTC 时区，先转换上海时区再格式化到分钟。
                    view.findViewById<TextView>(R.id.progress_created).text = BusinessDisplay.time(progress.createdAt)
                    view.findViewById<TextView>(R.id.progress_status).text = when (progress.status) {
                        "REQUESTED", "PENDING" -> "申请已提交"
                        "APPROVED" -> "退款已批准"
                        "REJECTED" -> "退款未通过"
                        else -> "状态更新中"
                    }
                    view.findViewById<TextView>(R.id.progress_next).text = when (progress.status) {
                        "APPROVED" -> "平台已批准退款"
                        "REJECTED" -> "平台未批准退款"
                        else -> "申请待处理"
                    }
                    // 终态由接口状态决定；待处理节点不能在已有结果时继续显示“待更新”。
                    view.findViewById<TextView>(R.id.progress_result).text = when (progress.status) {
                        "APPROVED" -> "已有处理结果"
                        "REJECTED" -> "已有处理结果"
                        else -> "处理结果待更新"
                    }
                }
                is ApiResult.Failure -> showError(view, "进度加载失败，请重试")
            }
        }
    }

    /** 错误文字只依赖当前请求结果，刷新成功后及时清除。 */
    private fun showError(view: View, message: String?) {
        view.findViewById<TextView>(R.id.progress_error).apply { isVisible = message != null; text = message.orEmpty() }
    }
}
