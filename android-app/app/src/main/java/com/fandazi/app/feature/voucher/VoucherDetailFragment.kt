// 团购券模块：展示本人真实券、退款与已申请进度；本期核销延期，不开放展示码入口。
package com.fandazi.app.feature.voucher

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.core.image.CatalogImageMetadataRefresh
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.time.BusinessDisplay
import com.fandazi.app.core.navigation.openRegisteredBusinessRoute
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

/** UI-12：团购券详情页；退款资格取后端，QR源码和导航仅预留，不宣称本期可核销。 */
class VoucherDetailFragment : Fragment(R.layout.fragment_voucher_detail) {
    private val app by lazy { requireActivity().application as FandaziApplication }
    private val repository by lazy { app.voucherRepository }
    private var loading: Job? = null

    /** 返回详情时重新读取本人资格，售后操作结束后不残留旧按钮。 */
    override fun onResume() {
        super.onResume()
        view?.let(::load)
    }

    /** 加载真实券详情并绑定售后入口；开始加载前就隐藏延期核销操作，避免短暂可见或触发令牌请求。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.voucher_show_code).apply {
            isVisible = false
            isEnabled = false
            setOnClickListener(null)
        }
        view.findViewById<View>(R.id.voucher_detail_retry).setOnClickListener { load(view) }
    }

    /** 原资源只读查询；加载或失败隐藏旧业务内容，401 登录回同一公开券号。 */
    private fun load(view: View) {
        if (loading?.isActive == true) return
        val voucherId = requireArguments().getString("voucherId").orEmpty()
        val error = view.findViewById<TextView>(R.id.voucher_detail_error)
        view.findViewById<View>(R.id.voucher_detail_body).isVisible = false
        view.findViewById<View>(R.id.voucher_detail_retry).isVisible = false
        error.isVisible = true
        error.text = "正在加载团购券"
        loading = viewLifecycleOwner.lifecycleScope.launch {
            when (val result = repository.getVoucher(voucherId)) {
                is ApiResult.Success -> {
                    val voucher = result.value
                    error.isVisible = false
                    view.findViewById<View>(R.id.voucher_detail_body).isVisible = true
                    view.findViewById<TextView>(R.id.voucher_detail_title).text = voucher.dealTitle
                    view.findViewById<TextView>(R.id.voucher_detail_store).text = voucher.storeName
                    val state = VoucherPresentation.voucherStatus(voucher.status)
                    view.findViewById<TextView>(R.id.voucher_detail_status).text = state
                    // 购买金额为该券支付分摊快照，订单数量另列，不能将整单金额作为每券金额。
                    view.findViewById<TextView>(R.id.voucher_detail_info).text = listOf(
                        "券码状态  $state", "团购券编号  ${voucher.publicId}", "订单编号  ${voucher.orderId}",
                        "购买数量  ${voucher.orderQuantity?.let { "$it 份" } ?: "未取得"}",
                        if (voucher.amountCents != null) "本券实付  ${BusinessDisplay.money(voucher.amountCents)}" else "本券尚无付款记录",
                        "购买时间  ${BusinessDisplay.time(voucher.orderCreatedAt)}",
                        // 使用起日属于服务器权益快照，未来有效券不能只显示结束日期造成误解。
                        "使用开始  ${voucher.validFrom ?: "以使用规则为准"}",
                        "有效期  ${BusinessDisplay.time(voucher.expiresAt)}").joinToString("\n")
                    view.findViewById<TextView>(R.id.voucher_detail_rules).text = voucher.dealDescription?.takeIf(String::isNotBlank) ?: "使用说明暂未提供"
                    // 缺图仅读取本对象的图片字段，不重绘资格/表单，也不准备报价或确认凭证。
                    CatalogImageMetadataRefresh.bind(view.findViewById(R.id.voucher_detail_image), voucher.imageUrl, voucher.imageAlt) {
                        (repository.getVoucher(voucher.publicId) as? ApiResult.Success)?.value?.let { it.imageUrl to it.imageAlt }
                    }
                    // 退款入口只认后端资格，提交页会再做一次归属和状态校验。
                    view.findViewById<TextView>(R.id.voucher_refund).apply {
                        isVisible = voucher.canRefund
                        setOnClickListener { it.openRegisteredBusinessRoute("refundRequestFragment", Bundle().apply {
                            putString("voucherId", voucher.publicId)
                        }, "退款申请将在售后功能启用后开放") }
                    }
                    // 每次进入独立查询已有申请；即使没有本地返回栈或进程已重启也能恢复真实进度。
                    loadRefundProgress(view, voucherId)
                }
                is ApiResult.Failure -> {
                    // 身份失效保留原入口，其他接口/网络错误仅显式重试，不补样例券或猜测资格。
                    if ((result.error as? ApiError.Http)?.code == 401) {
                        (requireActivity() as MainActivity).openPurchaseLogin(R.id.voucherDetailFragment,
                            Bundle().apply { putString("voucherId", voucherId) }, true)
                    } else {
                        error.text = "团购券加载失败，请重试"
                        view.findViewById<View>(R.id.voucher_detail_retry).isVisible = true
                    }
                }
            }
        }
    }

    /** 404 是无申请，其他失败显示原地重试；查询成功只绑定真实 requestId，不伪造资格。 */
    private fun loadRefundProgress(view: View, voucherId: String) {
        val button = view.findViewById<TextView>(R.id.voucher_refund_progress)
        button.isVisible = false
        viewLifecycleOwner.lifecycleScope.launch {
            // 该查询是券页已需的只读依赖，不能为了恢复入口接入未来的售后提交仓库。
            when (val result = repository.getRefundLink(voucherId)) {
                is ApiResult.Success -> button.apply {
                    isVisible = true
                    text = "查看退款进度"
                    setOnClickListener {
                        it.openRegisteredBusinessRoute("refundProgressFragment", Bundle().apply {
                            putString("requestId", result.value.requestId)
                        }, "退款进度页面将在售后功能启用后开放")
                    }
                }
                is ApiResult.Failure -> {
                    if ((result.error as? ApiError.Http)?.code != 404) button.apply {
                        isVisible = true
                        text = "退款进度读取失败，点击重试"
                        setOnClickListener { loadRefundProgress(view, voucherId) }
                    }
                }
            }
        }
    }

    /** 延期核销代码保留：未来获得明确产品授权后才可重新绑定；本期无任何可见调用入口。 */
    private fun requestToken(view: View, voucher: VoucherSummary) {
        view.findViewById<TextView>(R.id.voucher_show_code).isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = repository.createDisplayToken(voucher.publicId)) {
                is ApiResult.Success -> view.openRegisteredBusinessRoute("voucherQrFragment", Bundle().apply {
                    putString("voucherId", voucher.publicId); putString("storeId", voucher.storeId); putString("token", result.value.token)
                }, "核销功能暂未开放")
                is ApiResult.Failure -> view.findViewById<TextView>(R.id.voucher_detail_status).text = "展示码获取失败：${result.error}"
            }
        }
    }
}
