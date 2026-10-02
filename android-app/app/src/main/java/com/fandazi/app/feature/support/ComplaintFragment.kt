// 投诉与反馈页面：用户选择类型、可选订单和描述后，经后端确认凭证创建工单。
package com.fandazi.app.feature.support

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.voucher.GroupOrderSummary
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch
import java.util.UUID

/** UI-20B：订单选择和工单写入均使用后端资源，客户端不持有内部数据库主键。 */
class ComplaintFragment : Fragment(R.layout.fragment_complaint) {
    private val app by lazy { requireActivity().application as FandaziApplication }
    private var sessionId: String = UUID.randomUUID().toString()
    private var orderId: String? = null
    private var orders: List<GroupOrderSummary> = emptyList()
    private var submitting = false
    private var typesReady = false
    private var selectedTypeId: String? = null
    /** 编辑只能清除已经修正的输入错误，不能隐藏目录、网络或提交不确定状态。 */
    private var inputErrorVisible = false

    /** 恢复同一表单的会话和已选订单，避免旋转后凭证资源不一致。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = savedInstanceState?.getString("complaintSessionId") ?: sessionId
        orderId = savedInstanceState?.getString("complaintOrderId") ?: arguments?.getString("orderId")
        selectedTypeId = savedInstanceState?.getString("complaintTypeId")
    }

    /** 只保留资源标识；一次性 grant 不写入持久化状态。 */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("complaintSessionId", sessionId)
        outState.putString("complaintOrderId", orderId)
        outState.putString("complaintTypeId", selectedTypeId)
        super.onSaveInstanceState(outState)
    }

    /** 入口允许不关联订单；需要关联时仅从本人订单列表选择。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.complaint_back).setOnClickListener { findNavController().navigateUp() }
        view.findViewById<View>(R.id.complaint_order_picker).setOnClickListener { chooseOrder(view) }
        view.findViewById<View>(R.id.complaint_submit).setOnClickListener { prepareAndConfirm(view) }
        view.findViewById<View>(R.id.complaint_retry).setOnClickListener { loadTypes(view); loadOrders(view) }
        view.findViewById<EditText>(R.id.complaint_description).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                view.findViewById<TextView>(R.id.complaint_count).text = "${s?.length ?: 0}/500"
                clearCorrectedInputError(view)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        loadOrders(view)
        loadTypes(view)
    }

    /** 类型按钮只根据后端目录创建，未知类型仍可正常选中；图标映射不决定业务选项。 */
    private fun loadTypes(view: View) {
        typesReady = false
        view.findViewById<View>(R.id.complaint_submit).isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = app.afterSaleRepository.complaintTypes()) {
                is ApiResult.Success -> {
                    val group = view.findViewById<ChipGroup>(R.id.complaint_types)
                    // 待布局获得可用宽度后等分三列，标签和图标固定在本列内。
                    group.post {
                        if (this@ComplaintFragment.view !== view) return@post
                        group.removeAllViews()
                        if (result.value.none { it.id == selectedTypeId }) selectedTypeId = result.value.firstOrNull()?.id
                        val density = resources.displayMetrics.density
                        val width = ((group.width - 16 * density) / 3).toInt()
                        result.value.forEach { option ->
                            group.addView(Chip(requireContext()).apply {
                                id = View.generateViewId()
                                tag = option.id
                                text = option.label
                                textSize = 13f
                                isCheckable = true
                                isChecked = option.id == selectedTypeId
                                setOnCheckedChangeListener { _, checked ->
                                    if (checked) { selectedTypeId = option.id; clearCorrectedInputError(view) }
                                }
                                layoutParams = ChipGroup.LayoutParams(width, (48 * density).toInt())
                                chipCornerRadius = 8 * density
                                chipBackgroundColor = ContextCompat.getColorStateList(requireContext(), R.color.after_sale_chip_background)
                                chipStrokeColor = ContextCompat.getColorStateList(requireContext(), R.color.after_sale_chip_stroke)
                                chipStrokeWidth = density
                                setTextColor(ContextCompat.getColorStateList(requireContext(), R.color.after_sale_chip_text))
                                isCheckedIconVisible = false
                                chipIcon = ContextCompat.getDrawable(requireContext(), typeIcon(option.id))
                                isChipIconVisible = true
                                chipIconSize = 17 * density
                                chipIconTint = ContextCompat.getColorStateList(requireContext(), R.color.after_sale_chip_text)
                                // 原生 Chip 默认留白偏宽，按设计稿保留图标与四字标签的完整宽度。
                                chipStartPadding = 8 * density
                                chipEndPadding = 6 * density
                                textStartPadding = 4 * density
                                textEndPadding = 0f
                            })
                        }
                        typesReady = result.value.isNotEmpty()
                        view.findViewById<View>(R.id.complaint_submit).isEnabled = typesReady
                        showError(view, if (typesReady) null else "问题类型目录为空，请重新加载")
                    }
                }
                is ApiResult.Failure -> showError(view, "问题类型加载失败，请重新加载")
            }
        }
    }

    /** 后端 ID 仅映射设计图标；未知编号用其他问题图标，不添加或删改目录项。 */
    private fun typeIcon(id: String): Int = when (id) {
        "product" -> R.drawable.complaint_product_icon
        "payment" -> R.drawable.complaint_payment_icon
        "merchant" -> R.drawable.complaint_merchant_icon
        "order" -> R.drawable.complaint_order_icon
        "account" -> R.drawable.complaint_account_icon
        else -> R.drawable.complaint_other_icon
    }

    /** 下拉选项来自服务端；读失败仍允许无订单反馈，但不构造虚假订单。 */
    private fun loadOrders(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = app.voucherRepository.listOrders()) {
                is ApiResult.Success -> {
                    orders = result.value
                    updateOrderLabel(view)
                }
                is ApiResult.Failure -> {
                    // 网络失败保留已经从本人接口取得的选择，后续 prepare 仍会重查归属。
                    showError(view, "关联订单暂时不可用，可重新加载或提交一般反馈")
                }
            }
        }
    }

    /** 当前用户可以选择不关联订单；订单标签来自响应中的真实门店或套餐名称。 */
    private fun chooseOrder(view: View) {
        if (submitting) return
        val labels = listOf("不关联订单") + orders.map { order ->
            "${order.storeName} · ${order.dealTitle} · ${order.orderId.takeLast(8)}"
        }
        val checked = orders.indexOfFirst { it.orderId == orderId }.let { if (it < 0) 0 else it + 1 }
        AlertDialog.Builder(requireContext()).setTitle("选择订单")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, index ->
                orderId = if (index == 0) null else orders[index - 1].orderId
                updateOrderLabel(view)
                dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    /** 控件只显示当前选择的公开订单号，避免把对象位置当成业务标识。 */
    private fun updateOrderLabel(view: View) {
        view.findViewById<TextView>(R.id.complaint_order_picker).text =
            orderId?.let { id -> "订单 ${id.takeLast(8)}    ›" } ?: "选择订单    ›"
    }

    /** 后端准备阶段校验可选订单归属，展示确认摘要后才执行写入。 */
    private fun prepareAndConfirm(view: View) {
        if (submitting || !typesReady) return
        val group = view.findViewById<ChipGroup>(R.id.complaint_types)
        val subject = view.findViewById<Chip>(group.checkedChipId)?.text?.toString()
            ?: run { showError(view, "请选择问题类型", true); return }
        val description = view.findViewById<EditText>(R.id.complaint_description).text.toString().trim()
        if (description.isBlank()) { showError(view, "请填写问题描述", true); return }
        if (description.length > 500) { showError(view, "问题描述不能超过500字", true); return }
        val confirmedOrderId = orderId
        submitting = true
        view.findViewById<View>(R.id.complaint_submit).isEnabled = false
        showError(view, null)
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = app.afterSaleRepository.prepareComplaint(sessionId, confirmedOrderId)) {
                is ApiResult.Success -> {
                    // 准备与写入绑定同一个公开订单；弹窗期间入口保持锁定，关闭则恢复编辑。
                    var confirmed = false
                    AlertDialog.Builder(requireContext()).setTitle("确认提交反馈")
                        .setMessage("问题类型：$subject\n关联订单：${confirmedOrderId ?: "未关联"}\n问题描述：$description")
                        .setNegativeButton("返回修改", null)
                        .setPositiveButton("确认提交") { _, _ ->
                            confirmed = true
                            submitting = false
                            submit(view, confirmedOrderId, subject, description, result.value)
                        }
                        .setOnDismissListener {
                            if (!confirmed) {
                                submitting = false
                                view.findViewById<View>(R.id.complaint_submit).isEnabled = true
                            }
                        }
                        .show()
                }
                is ApiResult.Failure -> {
                    submitting = false
                    view.findViewById<View>(R.id.complaint_submit).isEnabled = true
                    showError(view, "反馈准备失败，请重试")
                }
            }
        }
    }

    /** 非幂等提交不自动重试；成功时将真实工单号和后端状态转换为可读摘要。 */
    private fun submit(view: View, selectedOrderId: String?, subject: String, description: String, grant: String) {
        if (submitting) return
        submitting = true
        view.findViewById<View>(R.id.complaint_submit).isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = app.afterSaleRepository.submitComplaint(sessionId, selectedOrderId, subject, description, grant)) {
                is ApiResult.Success -> AlertDialog.Builder(requireContext()).setTitle("反馈已提交")
                    // 编号只作人工检索提示，完整标识仍以服务端工单为准；状态不能直接露出 OPEN 枚举。
                    .setMessage("工单编号（末8位）：${result.value.ticketId.takeLast(8).uppercase()}\n当前状态：${when (result.value.status) {
                        "OPEN" -> "待处理"
                        "IN_PROGRESS" -> "处理中"
                        "RESOLVED", "CLOSED" -> "已处理"
                        else -> "状态更新中"
                    }}")
                    .setCancelable(false)
                    .setPositiveButton("返回帮助") { _, _ -> findNavController().navigateUp() }.show()
                is ApiResult.Failure -> {
                    submitting = false
                    view.findViewById<View>(R.id.complaint_submit).isEnabled = true
                    showError(view, "提交结果未确认，请先核查再重新准备")
                }
            }
        }
    }

    /** 错误状态始终留在当前页面，不把网络失败展示成成功工单。 */
    private fun showError(view: View, message: String?, inputError: Boolean = false) {
        inputErrorVisible = message != null && inputError
        view.findViewById<TextView>(R.id.complaint_error).apply { isVisible = message != null; text = message.orEmpty() }
        view.findViewById<View>(R.id.complaint_retry).isVisible = message != null
    }

    /** 类型与正文同时合法时移除过时校验提示，其他错误仍等待服务端复验。 */
    private fun clearCorrectedInputError(view: View) {
        val description = view.findViewById<EditText>(R.id.complaint_description).text.toString().trim()
        if (inputErrorVisible && selectedTypeId != null && description.isNotBlank() && description.length <= 500) showError(view, null)
    }
}
