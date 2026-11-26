// 售后成功主体绑定：复用Figma同构的反馈/评价结果布局，只负责展示与显式返回，不执行写入。
package com.fandazi.app.feature.support

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.fandazi.app.R

/** 为已由后端确认的操作绑定结果主体；调用方负责保证资格与成功状态真实性。 */
object AfterSaleSuccessContent {
    /** 隐藏原表单并绑定静态展示文案与返回动作；不接受本地业务样例或自动跳页。 */
    fun show(form: View, result: View, header: String, title: String, description: String,
             returnLabel: String, onReturn: () -> Unit) {
        // 成功与编辑状态互斥，避免可重复提交的表单仍出现在灰幕下。
        form.isVisible = false
        result.isVisible = true
        result.findViewById<TextView>(R.id.after_sale_result_header).text = header
        result.findViewById<TextView>(R.id.after_sale_result_title).text = title
        result.findViewById<TextView>(R.id.after_sale_result_description).text = description
        result.findViewById<TextView>(R.id.after_sale_result_return).apply {
            text = returnLabel
            setOnClickListener { onReturn() }
        }
        // 返回箭头与主按钮遵循相同原调用方导航，留在结果页直到用户明确操作。
        result.findViewById<View>(R.id.after_sale_result_back).setOnClickListener { onReturn() }
    }
}
