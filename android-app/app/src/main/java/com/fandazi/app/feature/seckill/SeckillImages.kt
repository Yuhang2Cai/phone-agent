// 秒杀图片绑定：展示真实后端图片，缺图与失败采用已确认的绿色品牌占位，显式点击才重试。
package com.fandazi.app.feature.seckill

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.fandazi.app.R
import com.fandazi.app.core.cache.CatalogNetworkAccess
import com.fandazi.app.core.image.*

/** 调用方每次商品URL/版本变化才绑定；倒计时不重载图片或重建图片请求。 */
object SeckillImages {
    /** 主图和缩略图共用缓存身份，尺寸和占位由各自XML规定。 */
    fun bind(root:View,url:String?,alt:String?,version:String?,retry:Boolean=false) {
        val image=root.findViewById<ImageView>(R.id.sk_photo)
        val fallback=root.findViewById<View>(R.id.sk_photo_fallback)
        val label=root.findViewById<TextView>(R.id.sk_photo_message)
        // 76dp缩略图只保留券形标志，避免主图的两行占位说明在小容器中被截断；失败说明仍可由无障碍读取。
        val compact=root.id==R.id.sk_summary
        label.isVisible=!compact
        root.findViewById<View>(R.id.sk_photo_brand).isVisible=!compact
        val source=CatalogImageUrls.resolve(url)
        val cachedOnly=!CatalogNetworkAccess.available(root.context)
        // 保留图片测量尺寸；GONE会使Glide等待永远不会发生的可见布局，实图因此一直卡在加载中。
        Glide.with(image).clear(image);image.visibility=View.INVISIBLE;fallback.isVisible=true;root.setOnClickListener(null)
        label.text=if(source==null)"商品图片暂未提供" else if(cachedOnly)"读取已缓存图片" else "商品图片加载中"
        root.contentDescription=label.text
        if(source==null)return
        val plan=CatalogImageRequestPolicy.resolve(CatalogImageRequestOptions(resourceVersion=version,onlyFromCache=cachedOnly,mode=if(retry)CatalogImageRequestMode.RETRY else CatalogImageRequestMode.NORMAL))
        Glide.with(image).load(source).onlyRetrieveFromCache(cachedOnly).signature(ObjectKey(plan.signature))
            .skipMemoryCache(!plan.cacheEnabled).diskCacheStrategy(if(plan.cacheEnabled)DiskCacheStrategy.AUTOMATIC else DiskCacheStrategy.NONE)
            .listener(object:RequestListener<Drawable> {
                /** 保持几何尺寸和真实失败提示；不会用另一张菜图兜底或自动反复联网。 */
                override fun onLoadFailed(e:GlideException?,model:Any?,target:Target<Drawable>,isFirstResource:Boolean):Boolean {
                    label.text=if(cachedOnly)"图片未缓存，联网后点击重试" else "图片加载失败，点击重试"
                    root.contentDescription=label.text
                    root.setOnClickListener {bind(root,url,alt,version,true)}
                    return true
                }
                /** 只显示当前请求成功的原图，无障碍说明使用后端alt。 */
                override fun onResourceReady(resource:Drawable,model:Any,target:Target<Drawable>?,dataSource:DataSource,isFirstResource:Boolean):Boolean {
                    image.isVisible=true;fallback.isVisible=false;image.contentDescription=alt ?: "套餐实物图";root.contentDescription=null;root.setOnClickListener(null)
                    return false
                }
            }).into(image)
    }
    /** 页面销毁清理本宿主图片，不清共享磁盘缓存。 */
    fun clear(root:View) {
        root.findViewById<ImageView>(R.id.sk_photo)?.let {image->
            // RecyclerView 会在 Activity 已进入 destroyed 后继续回收缓存 View；应用级管理器只撤销该目标，
            // 不重新绑定已销毁的 Activity 生命周期，也不会清除其他页面可复用的内存或磁盘缓存。
            Glide.with(image.context.applicationContext).clear(image)
        }
        root.setOnClickListener(null)
    }
}
