// 图片退出生命周期回归：真实Activity销毁后回收同一图片，不新建已销毁Activity的Glide请求管理器。
package com.fandazi.app.core.image

import androidx.fragment.app.FragmentActivity
import android.widget.ImageView
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 这项断言防止RecyclerView晚于Activity销毁回收缓存Holder导致退出崩溃，不能跳过图片释放。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class DestroyedCatalogImageReleaseTest {
    /** 先建立真实图片绑定，再销毁宿主，最后清理；应取消令牌/监听并归还Drawable而不抛异常。 */
    @Test fun releaseAfterActivityDestroyedClearsBindingWithoutNewActivityManager() {
        val controller=Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val image=ImageView(controller.get())
        controller.get().setContentView(image)
        CatalogImageMetadataRefresh.bind(image,null,"合成缺图"){null}
        assertTrue(image.hasOnClickListeners())
        controller.pause().stop().destroy()
        assertTrue(controller.get().isDestroyed)
        // 若清理仍用Glide.with(image)，此处会在已销毁Activity上抛IllegalArgumentException。
        CatalogImageMetadataRefresh.release(image)
        assertNull(image.drawable);assertNull(image.contentDescription);assertFalse(image.hasOnClickListeners())
        assertNull(image.getTag(com.fandazi.app.R.id.catalog_image_metadata_token))
    }
}
