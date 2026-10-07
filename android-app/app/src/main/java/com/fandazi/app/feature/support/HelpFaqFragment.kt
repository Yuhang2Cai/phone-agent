// 帮助与客服页面：搜索并展开服务端 FAQ，提供投诉入口。
package com.fandazi.app.feature.support

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/** UI-20A：FAQ 的问答正文以接口结果为唯一来源，搜索时保留当前页面。 */
class HelpFaqFragment : Fragment(R.layout.fragment_help_faq) {
    private val repository by lazy { (requireActivity().application as FandaziApplication).afterSaleRepository }
    private var searchJob: Job? = null
    private var requestGeneration = 0
    private var categoryId: String? = null

    /** 分类是用户查询上下文；旋转时保存编号，正文仍从服务端读取。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        categoryId = savedInstanceState?.getString("faqCategory")
    }

    /** 只保存分类选择，不把 FAQ 答案落入本地静态副本。 */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("faqCategory", categoryId)
        super.onSaveInstanceState(outState)
    }

    /** 绑定搜索、重试和投诉入口；客服入口暂时指向可记录问题的投诉表单。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.faq_back).setOnClickListener { findNavController().navigateUp() }
        view.findViewById<View>(R.id.faq_retry).setOnClickListener { loadCategories(view) }
        // FAQ 可公开阅读，投诉写入需要身份；游客点击反馈时先走统一登录引导。
        view.findViewById<View>(R.id.faq_complaint).setOnClickListener { (requireActivity() as MainActivity).openProtectedDestination(R.id.complaintFragment) }
        view.findViewById<View>(R.id.faq_contact).setOnClickListener { (requireActivity() as MainActivity).openProtectedDestination(R.id.complaintFragment) }
        view.findViewById<EditText>(R.id.faq_search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // 短暂停顿后查询服务端，避免每次输入都触发请求；旧查询任务被取消后不会覆盖新结果。
                searchJob?.cancel()
                searchJob = viewLifecycleOwner.lifecycleScope.launch { delay(250); load(view) }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        loadCategories(view)
    }

    /** 先获得真实目录再绑定分类；“全部”只表示清除过滤，不是额外业务类别。 */
    private fun loadCategories(view: View) {
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = repository.faqCategories()) {
                is ApiResult.Success -> {
                    val group = view.findViewById<ChipGroup>(R.id.faq_categories)
                    group.setOnCheckedStateChangeListener(null)
                    group.removeAllViews()
                    if (result.value.none { it.id == categoryId }) categoryId = null
                    (listOf(SupportOption("", "全部")) + result.value).forEach { option ->
                        group.addView(Chip(requireContext()).apply {
                            id = View.generateViewId()
                            text = option.label
                            tag = option.id
                            isCheckable = true
                            isChecked = option.id == categoryId.orEmpty()
                            setEnsureMinTouchTargetSize(true)
                        })
                    }
                    // 每次变更同时携带当前关键词，保证分类和搜索取交集。
                    group.setOnCheckedStateChangeListener { _, ids ->
                        categoryId = ids.firstOrNull()?.let { group.findViewById<Chip>(it).tag as String }?.takeIf { it.isNotBlank() }
                        load(view)
                    }
                    load(view)
                }
                is ApiResult.Failure -> {
                    view.findViewById<TextView>(R.id.faq_error).apply { isVisible = true; text = "帮助分类加载失败，请重试" }
                    view.findViewById<View>(R.id.faq_retry).isVisible = true
                }
            }
        }
    }

    /** 错误时显示原地重试；空结果也是服务端搜索结果，不补造本地 FAQ。 */
    private fun load(view: View) {
        // 每次请求带代数；即使旧 HTTP 无法取消，也不能覆盖更晚的搜索结果。
        val generation = ++requestGeneration
        val keyword = view.findViewById<EditText>(R.id.faq_search).text.toString()
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = repository.listFaqs(keyword, categoryId)) {
                is ApiResult.Success -> {
                    if (generation != requestGeneration) return@launch
                    view.findViewById<View>(R.id.faq_retry).isVisible = false
                    view.findViewById<TextView>(R.id.faq_error).isVisible = false
                    render(view.findViewById(R.id.faq_list), result.value)
                }
                is ApiResult.Failure -> {
                    if (generation != requestGeneration) return@launch
                    view.findViewById<TextView>(R.id.faq_error).apply { isVisible = true; text = "帮助内容加载失败，请重试" }
                    view.findViewById<View>(R.id.faq_retry).isVisible = true
                }
            }
        }
    }

    /** 每条记录保留独立展开状态，点击标题只改变本行答案可见性。 */
    private fun render(container: LinearLayout, items: List<FaqItem>) {
        container.removeAllViews()
        if (items.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "没有找到相关问题"
                setPadding(16, 24, 16, 24)
            })
            return
        }
        items.forEach { item ->
            val row = LayoutInflater.from(requireContext()).inflate(R.layout.item_faq_row, container, false)
            row.findViewById<TextView>(R.id.faq_row_title).text = item.question
            row.findViewById<TextView>(R.id.faq_row_summary).text = item.answer
            // ID 只决定视觉图标，不参与 FAQ 分类或答案内容生成；未知条目使用通用反馈图标。
            row.findViewById<ImageView>(R.id.faq_row_icon).setImageResource(when (item.id) {
                "purchase-flow" -> R.drawable.faq_purchase
                "voucher-use", "coupon-use" -> R.drawable.faq_voucher
                "refund-eligibility" -> R.drawable.faq_refund
                "account-login" -> R.drawable.faq_account
                else -> R.drawable.faq_complaint
            })
            val answer = row.findViewById<TextView>(R.id.faq_row_answer).apply { text = item.answer }
            val arrow = row.findViewById<TextView>(R.id.faq_row_arrow)
            row.findViewById<View>(R.id.faq_row_header).setOnClickListener {
                answer.isVisible = !answer.isVisible
                arrow.text = if (answer.isVisible) "⌄" else "›"
            }
            container.addView(row)
        }
    }
}
