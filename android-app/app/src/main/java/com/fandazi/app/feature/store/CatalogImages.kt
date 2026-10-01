// 浏览缩略图模块：复用 Glide 和售后大图加载器，小尺寸区域使用清晰符号而非无法容纳的两行状态文字。
package com.fandazi.app.feature.store

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/** 48dp 套餐缩略图专用适配，不生成业务餐图；大图沿用既有实际 URL 加载行为。 */
object CatalogImages {
    /** 实际图片成功后显示原图；缺图刷新元数据，加载失败只重试同一 URL。 */
    fun thumbnail(image: ImageView, url: String?, alt: String?, refresh: () -> Unit) {
        Glide.with(image).clear(image)
        val source = url?.takeUnless { it.isBlank() }
        if (source == null) {
            state(image, "暂无图片，点击刷新", android.R.drawable.ic_menu_gallery)
            image.setOnClickListener { refresh() }
            return
        }
        state(image, "图片加载中", android.R.drawable.ic_menu_gallery)
        image.setOnClickListener(null)
        // 占位只使用图库符号，状态通过无障碍文本表达；小区域不缩放文字或叠放两行说明。
        Glide.with(image).load(source).placeholder(image.drawable)
            .listener(object : RequestListener<Drawable> {
                /** 失败保留中性告警符号，用户点击再请求，避免自动重试循环。 */
                override fun onLoadFailed(error: GlideException?, model: Any?, target: Target<Drawable>, first: Boolean): Boolean {
                    state(image, "图片加载失败，点击重试", android.R.drawable.ic_dialog_alert)
                    image.setOnClickListener { thumbnail(image, source, alt, refresh) }
                    return true
                }
                /** 原始图片不应用占位灰色滤镜；图片描述来自当前套餐，不借用其他餐图。 */
                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, first: Boolean): Boolean {
                    image.imageTintList = null
                    image.scaleType = ImageView.ScaleType.CENTER_CROP
                    image.contentDescription = alt?.takeUnless { it.isBlank() } ?: "套餐图片"
                    image.setOnClickListener(null); image.isClickable = false
                    return false
                }
            }).into(image)
    }

    /** 图库/告警符号与中性底明确表示未知或失败，不当作真实业务照片。 */
    private fun state(image: ImageView, description: String, icon: Int) {
        image.setBackgroundColor(Color.rgb(238, 241, 244))
        image.scaleType = ImageView.ScaleType.CENTER_INSIDE
        image.imageTintList = ColorStateList.valueOf(Color.rgb(137, 147, 157))
        image.setImageResource(icon)
        image.contentDescription = description
    }
}
