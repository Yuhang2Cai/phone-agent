// 退款申请页面：读取本人券、收集原因，并使用服务端一次性凭证提交申请。
package com.fandazi.app.feature.refund

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.core.image.CatalogImageMetadataRefresh
import com.fandazi.app.core.time.BusinessDisplay
import com.fandazi.app.feature.support.RefundPreparation
import com.fandazi.app.feature.voucher.VoucherSummary
import kotlinx.coroutines.launch
import java.util.UUID

/** UI-18A 以服务端资格为准；端上确认框不能代替后端再次验证。 */
class RefundRequestFragment : Fragment(R.layout.fragment_refund_request) {
    private val app by lazy { requireActivity().application as FandaziApplication }
    private var voucher: VoucherSummary? = null
    private var sessionId: String = UUID.randomUUID().toString()
    private var submitting = false
    private var preparation: RefundPreparation? = null

    /** 旋转页面时保留同一会话 ID，确保准备凭证只用于本次申请。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = savedInstanceState?.getString("refundSessionId") ?: sessionId
    }

    /** 恢复会话标识；确认凭证不落盘，重新提交时会向后端重新准备。 */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("refundSessionId", sessionId)
        super.onSaveInstanceState(outState)
    }

    /** 进入页面后按公开券 ID 查询当前归属与展示信息。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.refund_back).setOnClickListener { findNavController().navigateUp() }
        val note = view.findViewById<EditText>(R.id.refund_note)
        note.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                view.findViewById<TextView>(R.id.refund_count).text = "${s?.length ?: 0}/200"
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        view.findViewById<View>(R.id.refund_submit).setOnClickListener { prepareAndConfirm(view) }
        view.findViewById<View>(R.id.refund_retry).setOnClickListener { loadVoucher(view) }
        loadVoucher(view)
    }

    /** 只有服务端明确允许申请的券才开放提交；失败时显示可重试错误。 */
    private fun loadVoucher(view: View) {
        val voucherId = requireArguments().getString("voucherId").orEmpty()
        if (voucherId.isBlank()) { showError(view, "缺少团购券编号"); return }
        // 初次加载也走真实 prepare，金额和资格未取得之前不能进入确认。
        if (submitting) return
        submitting = true
        preparation = null
        view.findViewById<View>(R.id.refund_submit).isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = app.voucherRepository.getVoucher(voucherId)) {
                is ApiResult.Success -> {
                    voucher = result.value
                    view.findViewById<TextView>(R.id.refund_store).text = result.value.storeName
                    view.findViewById<TextView>(R.id.refund_deal).text = result.value.dealTitle
                    view.findViewById<TextView>(R.id.refund_order_id).text = "订单号末8位：${result.value.orderId.takeLast(8)}"
                    // 缺图仅提取本人券图片元数据；不重置退款准备凭证、原因和说明。
                    // 缺图仅读取本对象的图片字段，不重绘资格/表单，也不准备报价或确认凭证。
                    CatalogImageMetadataRefresh.bind(view.findViewById(R.id.refund_image), result.value.imageUrl, result.value.imageAlt) {
                        (app.voucherRepository.getVoucher(voucherId) as? ApiResult.Success)?.value?.let { it.imageUrl to it.imageAlt }
                    }
                    // 不合资格时保留摘要与输入，同时明确禁止后续写入。
                    if (!result.value.canRefund) {
                        showError(view, "此券当前不符合退款申请条件")
                    } else when (val preview = app.afterSaleRepository.prepareRefund(sessionId, voucherId)) {
                        is ApiResult.Success -> {
                            preparation = preview.value
                            view.findViewById<TextView>(R.id.refund_amount).text = BusinessDisplay.money(preview.value.amountCents)
                            view.findViewById<View>(R.id.refund_submit).isEnabled = preview.value.canRefund
                            showError(view, null)
                        }
                        is ApiResult.Failure -> showError(view, "退款金额与资格加载失败，请重试")
                    }
                }
                is ApiResult.Failure -> showError(view, "团购券加载失败，请重试")
            }
            submitting = false
        }
    }

    /** 合并用户选中的固定原因与可选说明，并在写入前展示真实预览。 */
    private fun prepareAndConfirm(view: View) {
        val selected = voucher ?: run { loadVoucher(view); return }
        if (submitting || !selected.canRefund || preparation?.canRefund != true) return
        val group = view.findViewById<RadioGroup>(R.id.refund_reasons)
        // 恢复视图或用户未选原因时明确校验，避免非法控件 ID 导致崩溃。
        val reason = view.findViewById<RadioButton>(group.checkedRadioButtonId)?.text?.toString()
            ?: run { showError(view, "请选择退款原因"); return }
        val note = view.findViewById<EditText>(R.id.refund_note).text.toString().trim()
        val finalReason = if (note.isBlank()) reason else "$reason：$note"
        // 准备请求不会创建退款记录，但会重新检查归属、状态和重复申请。
        submitting = true
        view.findViewById<View>(R.id.refund_submit).isEnabled = false
        showError(view, null)
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = app.afterSaleRepository.prepareRefund(sessionId, selected.publicId)) {
                is ApiResult.Success -> {
                    val preview = result.value
                    preparation = preview
                    if (!preview.canRefund) {
                        submitting = false
                        showError(view, "此券当前不符合退款申请条件")
                        return@launch
                    }
                    view.findViewById<TextView>(R.id.refund_amount).text = BusinessDisplay.money(preview.amountCents)
                    // 弹窗期间一直锁住入口；只有明确确认才写入，关闭弹窗则恢复编辑。
                    var confirmed = false
                    AlertDialog.Builder(requireContext())
                        .setTitle("确认退款申请")
                        .setMessage("门店：${preview.storeName}\n团购券：${preview.dealTitle}\n订单号：${preview.orderId}\n金额：${BusinessDisplay.money(preview.amountCents)}\n原因：$finalReason\n有效期：${BusinessDisplay.time(preview.expiresAt)}")
                        .setNegativeButton("返回修改", null)
                        .setPositiveButton("确认提交") { _, _ ->
                            confirmed = true
                            submitting = false
                            submit(view, preview.voucherId, finalReason, preview.confirmationGrant)
                        }
                        .setOnDismissListener {
                            if (!confirmed) {
                                submitting = false
                                view.findViewById<View>(R.id.refund_submit).isEnabled = true
                            }
                        }
                        .show()
                }
                is ApiResult.Failure -> {
                    submitting = false
                    view.findViewById<View>(R.id.refund_submit).isEnabled = true
                    showError(view, "退款准备失败，请重试或返回查看券状态")
                }
            }
        }
    }

    /** 写请求绝不自动重试；结果不确定时让用户回到订单核查，避免重复申请。 */
    private fun submit(view: View, voucherId: String, reason: String, grant: String) {
        if (submitting) return
        submitting = true
        view.findViewById<View>(R.id.refund_submit).isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = app.afterSaleRepository.submitRefund(sessionId, voucherId, reason, grant)) {
                is ApiResult.Success -> findNavController().navigate(R.id.refundProgressFragment, Bundle().apply {
                    putString("requestId", result.value.requestId)
                })
                is ApiResult.Failure -> {
                    submitting = false
                    view.findViewById<View>(R.id.refund_submit).isEnabled = true
                    showError(view, "提交结果未确认，请先查看订单或联系帮助，避免重复申请")
                }
            }
        }
    }

    /** 不展示凭证或响应正文，只给用户可定位的操作失败信息。 */
    private fun showError(view: View, message: String?) {
        view.findViewById<TextView>(R.id.refund_error).apply { isVisible = message != null; text = message.orEmpty() }
    }
}
