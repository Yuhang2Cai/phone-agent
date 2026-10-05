// 图片元数据局部刷新：只更新点击图片的URL和描述，依赖视图生命周期，不重绘目录或改交易事实。
package com.fandazi.app.core.image

import android.widget.ImageView
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.fandazi.app.R
import com.fandazi.app.feature.store.CatalogImages
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import android.util.Log

/** 目录公开餐图专用局部刷新，私人图片继续由有账户归属的显式请求入口处理。 */
object CatalogImageMetadataRefresh {
    /** 当前视图绑定身份与其元数据任务；复用时一起取消，避免旧读取占用网络或覆盖下一条。 */
    private class Binding { var job:Job?=null }
    /**
     * 首次绑定实际URL；缺图点击调用request仅读取该公开对象的图片字段，null结果表示请求失败。
     * 请求成功只重绑本ImageView，不更新其他文本；新绑定或视图离开后丢弃旧响应。
     * thumbnail保留小图状态外观；request禁止产生交易写入或构造业务数据。
     */
    fun bind(image:ImageView,url:String?,alt:String?,thumbnail:Boolean=false,request:suspend ()->Pair<String?,String?>?) {
        // 每次绑定固定一个身份令牌；晚到元数据必须与视图当前身份一致，不能覆盖复用后的下一条。
        (image.getTag(R.id.catalog_image_metadata_token) as? Binding)?.job?.cancel()
        val token=Binding()
        image.setTag(R.id.catalog_image_metadata_token,token)
        lateinit var refresh:()->Unit
        fun render(currentUrl:String?,currentAlt:String?) {
            if(thumbnail) CatalogImages.thumbnail(image,currentUrl,currentAlt,refresh)
            else AfterSaleImageLoader.load(image,currentUrl,currentAlt,refresh)
        }
        refresh=refresh@{
            val owner=image.findViewTreeLifecycleOwner() ?: return@refresh
            token.job?.cancel()
            token.job=owner.lifecycleScope.launch {
                try {
                    val result=request()
                    ensureActive()
                    // 元数据失败保留当前缺图和重试入口；只有仍附着且身份未变才更新对应图片。
                    if(result!=null && image.isAttachedToWindow && image.getTag(R.id.catalog_image_metadata_token)===token) {
                        render(result.first,result.second)
                    }
                } catch(cancelled:CancellationException) {
                    throw cancelled
                } catch(error:Exception) {
                    // 只记录类型定位异常，不把URL、响应正文、账户或凭据写入日志。
                    Log.w("CatalogImageMetadata","图片元数据刷新异常：${error.javaClass.simpleName}")
                }
            }
        }
        render(url,alt)
    }
    /** Holder回收/页面销毁释放当前图片读取、令牌、Drawable和事件；不影响共享公开磁盘缓存。 */
    fun release(image:ImageView) {
        (image.getTag(R.id.catalog_image_metadata_token) as? Binding)?.job?.cancel()
        image.setTag(R.id.catalog_image_metadata_token,null)
        // RecyclerView缓存Holder可能在Activity已销毁后才回收；此时不能再次获取该Activity的管理器。
        // 应用管理器仅执行clear，Glide按目标已有请求委派原管理器取消，不新增跨生命周期图片加载。
        com.bumptech.glide.Glide.with(image.context.applicationContext).clear(image)
        image.setOnClickListener(null);image.isClickable=false;image.setImageDrawable(null)
        image.contentDescription=null;image.imageTintList=null
    }

}
