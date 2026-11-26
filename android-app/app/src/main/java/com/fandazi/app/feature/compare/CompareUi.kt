// 对比视图公共装配：复用目录图片加载与主题，负责安全区/按钮状态，依赖既有Android控件。
package com.fandazi.app.feature.compare
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.fandazi.app.R

/** 设计状态栏由真实系统Insets替代，不能画死9:41或设备电量。 */
internal fun View.compareInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this){root,insets->root.updatePadding(top=insets.getInsets(WindowInsetsCompat.Type.statusBars()).top);insets}
    ViewCompat.requestApplyInsets(this)
}
/** 固定按钮的可用状态同时影响触摸与颜色，禁用不会保留上一帧可点击资格。 */
internal fun TextView.compareEnabled(enabled:Boolean,secondary:Boolean=false) {
    isEnabled=enabled
    setBackgroundResource(if(!enabled)android.R.color.transparent else if(secondary)R.drawable.compare_secondary else R.drawable.compare_primary)
    setTextColor(ContextCompat.getColor(context,if(enabled)R.color.surface_card else R.color.group_buy_muted))
}

/** 对比设计统一显示两位元；仅整数分格式转换，交易金额保持原整数值。 */
internal fun compareMoney(cents:Int):String="¥"+java.math.BigDecimal.valueOf(cents.toLong(),2).setScale(2).toPlainString()
