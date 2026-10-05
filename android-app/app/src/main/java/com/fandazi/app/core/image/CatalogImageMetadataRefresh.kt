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
    private class Binding { var job:Job?=null;var offline:Boolean=false;var rerender:(()->Unit)?=null }
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
        var latestUrl=url;var latestAlt=alt
        fun render(currentUrl:String?,currentAlt:String?) {
            latestUrl=currentUrl;latestAlt=currentAlt
            token.offline=!com.fandazi.app.core.cache.CatalogNetworkAccess.available(image.context)
            val options=CatalogImageRequestOptions(onlyFromCache=token.offline)
            if(thumbnail) CatalogImages.thumbnail(image,currentUrl,currentAlt,options,refresh)
            else AfterSaleImageLoader.load(image,currentUrl,currentAlt,options,refresh)
        }
        // 仅当前附着视图保存局部重绘闭包，销毁release会移除，不建立全局强引用注册表。
        token.rerender={render(latestUrl,latestAlt)}
        refresh=refresh@{
            // 离线点击不得绕过图片缓存去获取元数据，也不能启动重试循环。
            if(!com.fandazi.app.core.cache.CatalogNetworkAccess.available(image.context))return@refresh
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
    /** 来源变化只重绑附着图片，后台HTTP失败不会由旧监听偷偷补图。 */
    fun refreshVisible(view:android.view.View) {
        if(view is ImageView) {
            val token=view.getTag(R.id.catalog_image_metadata_token) as? Binding
            val offline=!com.fandazi.app.core.cache.CatalogNetworkAccess.available(view.context)
            if(token!=null && token.offline!=offline){token.job?.cancel();token.rerender?.invoke()}
        }
        if(view is android.view.ViewGroup)for(i in 0 until view.childCount)refreshVisible(view.getChildAt(i))
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
