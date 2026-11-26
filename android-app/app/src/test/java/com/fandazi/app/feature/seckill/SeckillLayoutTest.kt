// 秒杀XML开发冒烟：实际展开资源和共享include，防止布局引用/样式问题延迟到安装后才暴露。
package com.fandazi.app.feature.seckill

import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.fandazi.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 只证明原生资源可展开与测量，不替代最新APK的Maestro和13屏Figma对照。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SeckillLayoutTest {
    /** 详情、列表、列表卡和确认弹层都必须保留独立图片与主要操作结构。 */
    @Test fun confirmedXmlStructuresInflateAndMeasure() {
        val controller=Robolectric.buildActivity(FragmentActivity::class.java).setup();val activity=controller.get()
        for(resource in listOf(R.layout.fragment_seckill,R.layout.fragment_seckill_store,R.layout.item_seckill_activity,R.layout.sheet_seckill)) {
            val root=LayoutInflater.from(activity).inflate(resource,FrameLayout(activity),false)
            root.measure(View.MeasureSpec.makeMeasureSpec(393,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(824,View.MeasureSpec.EXACTLY));root.layout(0,0,393,824)
            assertEquals(393,root.measuredWidth)
            if(resource!=R.layout.fragment_seckill_store)assertNotNull(root.findViewById<View>(R.id.sk_photo_fallback))
            if(resource==R.layout.sheet_seckill)assertNotNull(root.findViewById<TextView>(R.id.sk_sheet_confirm))
            if(resource==R.layout.fragment_seckill)assertNotNull(root.findViewById<TextView>(R.id.sk_primary))
        }
        controller.destroy()
    }
    /** 按冻结规格逐个测量小按钮，防止只放大主购买按钮而遗漏导航、栏目和数量操作。 */
    @Test fun everySmallInteractiveControlHasAtLeast48DpHitArea() {
        val controller=Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity=controller.get()
        val density=activity.resources.displayMetrics.density
        val minimum=kotlin.math.ceil(48*density).toInt()
        // 采用真实手机宽度的dp换算，避免小于正常视口的测试宽度挤压控件而误判。
        val controls=mapOf(
            R.layout.fragment_seckill to listOf(R.id.sk_back,R.id.sk_orders),
            R.layout.fragment_seckill_store to listOf(R.id.sk_back,R.id.sk_orders,R.id.sk_tab_deals,R.id.sk_tab_dishes,R.id.sk_tab_reviews),
            R.layout.sheet_seckill to listOf(R.id.sk_sheet_close,R.id.sk_minus,R.id.sk_plus)
        )
        for((layout,ids) in controls) {
            val root=LayoutInflater.from(activity).inflate(layout,FrameLayout(activity),false)
            root.measure(View.MeasureSpec.makeMeasureSpec((393*density).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((824*density).toInt(),View.MeasureSpec.EXACTLY))
            root.layout(0,0,root.measuredWidth,root.measuredHeight)
            for(id in ids) {
                val control=root.findViewById<View>(id)
                assertTrue("控件宽度必须至少48dp：$id",control.width>=minimum)
                assertTrue("控件高度必须至少48dp：$id",control.height>=minimum)
                // 扩展触达不能伸出父容器后被裁掉，否则账面48dp仍不是有效点击范围。
                val parent=control.parent as View
                assertTrue("控件触达不能越出父容器：$id",control.left>=0 && control.top>=0 && control.right<=parent.width && control.bottom<=parent.height)
            }
        }
        controller.destroy()
    }
}
