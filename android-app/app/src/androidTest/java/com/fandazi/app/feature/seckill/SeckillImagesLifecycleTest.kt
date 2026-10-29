// 秒杀图片生命周期回归：页面销毁后清理旧 View 不能再绑定已销毁的 Activity 请求管理器。
package com.fandazi.app.feature.seckill

import android.view.LayoutInflater
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** 覆盖 RecyclerView 在 Activity 销毁阶段回收卡片时触发的真实 Glide 生命周期边界。 */
@RunWith(AndroidJUnit4::class)
class SeckillImagesLifecycleTest {
    /**
     * 先用 Activity 上下文创建真实秒杀图片布局，再销毁 Activity 后执行清理。
     * 旧实现会由 Glide.with(image) 抛出“destroyed activity”，修复后只清目标并移除点击监听。
     */
    @Test
    fun clearAfterOwnerDestroyedDoesNotStartDestroyedActivityRequestManager() {
        lateinit var root:View
        val scenario=ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity {activity->
            root=LayoutInflater.from(activity).inflate(R.layout.include_seckill_photo,null,false)
            root.setOnClickListener { /* 构造待清理的重试入口，验证销毁时同时撤销。 */ }
        }
        scenario.close()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {SeckillImages.clear(root)}
        assertFalse("销毁清理后不能保留图片重试点击入口",root.hasOnClickListeners())
    }
}
