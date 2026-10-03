// 评价页面：按真实订单资格展示门店与套餐，并把用户星级、文字提交后端。
package com.fandazi.app.feature.review

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.core.image.AfterSaleImageLoader
import com.fandazi.app.core.time.BusinessDisplay
import com.fandazi.app.feature.support.AfterSaleSuccessContent
import kotlinx.coroutines.launch

/** UI-19：资格、订单和门店均由服务端决定，禁止用页面本地状态代替核销判断。 */
class ReviewFragment : Fragment(R.layout.fragment_review) {
    private val app by lazy { requireActivity().application as FandaziApplication }
    private var eligible = false
    private var submitting = false
    private var rating = 0
    /** 仅后端发布成功才能置位；旋转后恢复结果主体而不是重发评价或重新开启表单。 */
    private var published = false
    /** 仅输入校验错误随编辑清除，资格或网络错误必须由实际重试结果解除。 */
    private var inputErrorVisible = false

    /** 星级保存在页面状态中，旋转后仍须由本人订单资格控制提交。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rating = savedInstanceState?.getInt("reviewRating") ?: 0
        published = savedInstanceState?.getBoolean("reviewPublished") ?: false
    }

    /** 保存用户输入星级；核销资格不持久化，进入页面重新查询。 */
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("reviewRating", rating)
        outState.putBoolean("reviewPublished", published)
        super.onSaveInstanceState(outState)
    }

    /** 绑定评分、字数和提交操作，进入后重新读取订单资格。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (published) { showPublished(view); return }
        view.findViewById<View>(R.id.review_back).setOnClickListener { findNavController().navigateUp() }
        // 五个独立等宽触摸区域避免缩放 RatingBar 导致最后一星越界或触点不准。
        starIds.forEachIndexed { index, id ->
            view.findViewById<View>(id).setOnClickListener {
                rating = index + 1
                renderRating(view)
                clearCorrectedInputError(view)
            }
        }
        renderRating(view)
        view.findViewById<EditText>(R.id.review_content).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                view.findViewById<TextView>(R.id.review_count).text = "${s?.length ?: 0}/500"
                clearCorrectedInputError(view)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        view.findViewById<View>(R.id.review_submit).setOnClickListener { submit(view) }
        view.findViewById<View>(R.id.review_retry).setOnClickListener { loadOrder(view) }
        loadOrder(view)
    }

    /** 资格变化或评价已存在时关闭写入口，避免重复评价误导用户。 */
    private fun loadOrder(view: View) {
        val orderId = requireArguments().getString("orderId").orEmpty()
        if (orderId.isBlank()) { showError(view, "缺少订单编号"); return }
        eligible = false
        view.findViewById<View>(R.id.review_submit).isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = app.voucherRepository.getOrder(orderId)) {
                is ApiResult.Success -> {
                    val order = result.value
                    eligible = order.canReview
                    // 页面摘要是订单事实；时间统一把 UTC 转上海时区，图片缺失保留区域。
                    view.findViewById<TextView>(R.id.review_store).text = order.storeName
                    view.findViewById<TextView>(R.id.review_deal).text = order.dealTitle
                    view.findViewById<TextView>(R.id.review_order).text = "下单时间：${BusinessDisplay.time(order.createdAt)}"
                    // 图片缺失刷新原订单元数据，星级与正文保持；图片失败不阻断真实可评价资格。
                    AfterSaleImageLoader.load(view.findViewById(R.id.review_image), order.imageUrl, order.imageAlt) { loadOrder(view) }
                    view.findViewById<View>(R.id.review_submit).isEnabled = eligible
                    showError(view, if (eligible) null else "此订单目前不可评价：需有已核销券且尚未评价")
                }
                is ApiResult.Failure -> showError(view, "订单加载失败，请重试")
            }
        }
    }

    /** 用户必须填写有效评分和正文；实际资格仍由后端在写入时再次检查。 */
    private fun submit(view: View) {
        if (!eligible || submitting) return
        val content = view.findViewById<EditText>(R.id.review_content).text.toString().trim()
        if (rating !in 1..5 || content.isBlank()) { showError(view, "请选择评分并填写评价内容", true); return }
        if (content.length > 500) { showError(view, "评价内容不能超过500字", true); return }
        submitting = true
        view.findViewById<View>(R.id.review_submit).isEnabled = false
        showError(view, null)
        viewLifecycleOwner.lifecycleScope.launch {
            when (val result = app.afterSaleRepository.publishReview(requireArguments().getString("orderId").orEmpty(), rating, content)) {
                is ApiResult.Success -> {
                    // 真实发布成功后关闭写入口，完整结果主体替换表单，不能由成功话术伪造资格。
                    eligible = false
                    published = true
                    app.voucherRepository.getOrder(requireArguments().getString("orderId").orEmpty())
                    showPublished(view)
                }
                is ApiResult.Failure -> {
                    submitting = false
                    view.findViewById<View>(R.id.review_submit).isEnabled = true
                    showError(view, "发布失败，请重试或返回订单核查")
                }
            }
        }
    }

    /** UI-19R使用已确认Figma结果构图；只在真实发布成功或同一次成功状态恢复后调用。 */
    private fun showPublished(view: View) {
        AfterSaleSuccessContent.show(view.findViewById(R.id.review_form), view.findViewById(R.id.review_result),
            "发布评价", "评价已发布", "感谢分享这次用餐体验", "返回订单") { findNavController().navigateUp() }
    }

    /** 清除旧错误，避免加载成功后仍显示前一次网络异常。 */
    private fun showError(view: View, message: String?, inputError: Boolean = false) {
        inputErrorVisible = message != null && inputError
        view.findViewById<TextView>(R.id.review_error).apply { isVisible = message != null; text = message.orEmpty() }
        view.findViewById<View>(R.id.review_retry).isVisible = message != null && !eligible
    }

    /** 星级和正文均合法后解除已失效的输入错误，不掩盖服务端资格或写入失败。 */
    private fun clearCorrectedInputError(view: View) {
        val content = view.findViewById<EditText>(R.id.review_content).text.toString().trim()
        if (inputErrorVisible && rating in 1..5 && content.isNotBlank() && content.length <= 500) showError(view, null)
    }

    /** 输出一至五星的选中图标与评价反馈，提供明确的可访问性选中状态。 */
    private fun renderRating(view: View) {
        starIds.forEachIndexed { index, id ->
            view.findViewById<ImageView>(id).apply {
                setImageResource(if (index < rating) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off)
                isSelected = index < rating
            }
        }
        view.findViewById<TextView>(R.id.review_rating_caption).text = when (rating) {
            1 -> "很不满意"
            2 -> "不太满意"
            3 -> "一般"
            4 -> "非常好，值得推荐"
            5 -> "非常满意"
            else -> "请选择评分"
        }
    }

    /** 星级视图顺序与评分值固定对应，不包含业务样例数据。 */
    private val starIds = listOf(R.id.review_star_1, R.id.review_star_2, R.id.review_star_3, R.id.review_star_4, R.id.review_star_5)
}
