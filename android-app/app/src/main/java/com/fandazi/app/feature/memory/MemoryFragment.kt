package com.fandazi.app.feature.memory

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.launch

/** Figma UI22记忆页：列表、纠正、确认、删除、清空均来自真实服务，不保存端侧业务副本。 */
class MemoryFragment : Fragment(R.layout.fragment_memory) {
    private val repository get() = (requireActivity().application as FandaziApplication).memoryRepository
    private var busy = false
    /** 只保留当前视图的弹窗，离开页面立即释放，不能携带上一用户的显示内容。 */
    private var activeDialog: AlertDialog? = null
    /** 控件绑定后读取API；返回保持原导航，错误重试不会伪装成空记忆。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.memory_back).setOnClickListener { findNavController().popBackStack() }
        view.findViewById<View>(R.id.memory_retry).setOnClickListener { load() }
        load()
    }
    /** 生命周期取消协程，避免退出页面后继续写控件或显示上一个用户记录。 */
    private fun load() {
        viewLifecycleOwner.lifecycleScope.launch {
            showStatus("正在加载", false)
            when (val result = repository.list()) {
                is ApiResult.Success -> render(result.value)
                is ApiResult.Failure -> failure(result.error)
            }
        }
    }
    /** 服务端状态显示为普通、敏感、过期或冲突；待确认不会标记已保存使用。 */
    private fun render(items: List<MemoryItem>) {
        val parent = requireView().findViewById<LinearLayout>(R.id.memory_items)
        parent.removeAllViews()
        showStatus(if (items.isEmpty()) "还没有AI记忆\n你可以告诉AI助手需要记住的用餐偏好。" else "", false)
        for (item in items) {
            val row = layoutInflater.inflate(R.layout.item_memory, parent, false)
            row.findViewById<TextView>(R.id.memory_kind).text = kind(item.key)
            row.findViewById<TextView>(R.id.memory_value).text = item.value
            row.findViewById<TextView>(R.id.memory_metadata).text = "${status(item.status)} · ${source(item.source)}"
            row.findViewById<TextView>(R.id.memory_expiry).text = "有效期至 ${item.expiresAt.take(10)}"
            row.findViewById<View>(R.id.memory_edit).setOnClickListener { dialog("编辑记忆", "", "保存", item.value) { value -> repository.edit(item, value) } }
            row.findViewById<View>(R.id.memory_delete).setOnClickListener { dialog("删除这条记忆？", "删除后，AI不会再使用这条偏好。", "确认删除") { repository.delete(item) } }
            row.findViewById<TextView>(R.id.memory_confirm).apply {
                isVisible = item.status == "pending" || item.status == "pending_sensitive"
                text = if (item.sensitive) "确认敏感保存" else "确认保存"
                setOnClickListener { dialog(if (item.sensitive) "确认保存敏感信息？" else "确认这条记忆？",
                    if (item.sensitive) "这条信息涉及饮食限制等敏感偏好，同意后才用于个性化推荐。" else "确认后AI将使用这条偏好。", "确认保存") { repository.confirm(item) } }
            }
            parent.addView(row)
        }
        if (items.isNotEmpty()) parent.addView(TextView(requireContext()).apply {
            text = "全部清除"; textSize = 16f
            setTextColor(requireContext().getColor(R.color.group_buy_orange))
            minHeight = (48 * resources.displayMetrics.density).toInt()
            setOnClickListener { dialog("清空全部AI记忆？", "全部记忆将停止使用，此操作不可撤销。", "确认清空") { repository.clear() } }
        })
    }
    /** 自定义XML弹窗还原Figma确认层；只在请求成功后关闭并重新读取权威列表。 */
    private fun dialog(title: String, message: String, action: String, initial: String? = null,
                       submit: suspend (String) -> ApiResult<String>) {
        if (busy) return
        val content = layoutInflater.inflate(R.layout.dialog_memory, null)
        val popup = AlertDialog.Builder(requireContext()).setView(content).create()
        activeDialog?.dismiss()
        activeDialog = popup
        content.findViewById<TextView>(R.id.memory_dialog_title).text = title
        val info = content.findViewById<TextView>(R.id.memory_dialog_message).apply { text = message; isVisible = message.isNotEmpty() }
        val input = content.findViewById<EditText>(R.id.memory_dialog_value).apply { isVisible = initial != null; setText(initial.orEmpty()) }
        val button = content.findViewById<Button>(R.id.memory_dialog_confirm).apply { text = action }
        content.findViewById<View>(R.id.memory_dialog_cancel).setOnClickListener { if (!busy) popup.dismiss() }
        button.setOnClickListener {
            if (initial != null && input.text.isBlank()) { input.error = "请输入记忆内容"; return@setOnClickListener }
            viewLifecycleOwner.lifecycleScope.launch {
                busy = true; button.isEnabled = false; popup.setCancelable(false)
                // 请求失败保留内容与弹窗，不让用户误以为修改/删除已经生效。
                try {
                    when (val result = submit(input.text.toString())) {
                        is ApiResult.Success -> { popup.dismiss(); load() }
                        is ApiResult.Failure -> { info.isVisible = true; info.text = "操作未完成，请重新加载后重试"; failure(result.error) }
                    }
                } finally {
                    // 页面退出会取消协程；仍须解除忙碌状态，不能下次进入后永久禁止操作。
                    busy = false; button.isEnabled = true; popup.setCancelable(true)
                }
            }
        }
        popup.show()
    }
    /** 与视图生命周期同步释放确认层，不允许销毁页面后继续显示旧偏好。 */
    override fun onDestroyView() {
        activeDialog?.dismiss()
        activeDialog = null
        busy = false
        super.onDestroyView()
    }
    /** 401进入正式登录，其他错误单独展示重试；不打印含服务细节的原始错误。 */
    private fun failure(error: ApiError) {
        if (error is ApiError.Http && error.code == 401) {
            // 清除失效令牌并保留记忆页返回目标，不能把内部服务故障当用户过期。
            (requireActivity() as MainActivity).openPurchaseLogin(R.id.memoryFragment, Bundle(), expired = true)
        }
        showStatus("记忆加载失败", true)
    }
    /** 状态文字与重试按钮是固定UI，记忆业务值只能来自API。 */
    private fun showStatus(message: String, retry: Boolean) {
        requireView().findViewById<TextView>(R.id.memory_status).apply { text = message; isVisible = message.isNotEmpty() }
        requireView().findViewById<View>(R.id.memory_retry).isVisible = retry
    }
    /** 协议标签映射只是UI文案，不生成任何用户偏好。 */
    private fun kind(key: String) = mapOf("cuisine" to "口味偏好", "taste" to "口味偏好", "budget" to "预算偏好",
        "party_size" to "用餐人数", "distance" to "距离偏好", "occasion" to "用餐场合", "feedback" to "推荐反馈",
        "response_style" to "回答偏好", "sort_order" to "排序偏好", "health" to "饮食限制", "allergy" to "饮食限制", "religion" to "饮食限制")[key] ?: "用餐偏好"
    /** 不把待确认、过期或冲突记录标记为生效。 */
    private fun status(value: String) = mapOf("confirmed" to "已确认", "pending" to "待确认", "pending_sensitive" to "敏感信息 · 未确认", "expired" to "已过期", "conflict" to "存在冲突")[value] ?: "未生效"
    /** 来源由服务器决定，端侧只翻译展示。 */
    private fun source(value: String) = mapOf("explicit" to "来自明确记忆授权", "repeated" to "来自重复表达", "user_confirmed" to "用户确认", "user_edit" to "用户纠正")[value] ?: "用户偏好"
}
