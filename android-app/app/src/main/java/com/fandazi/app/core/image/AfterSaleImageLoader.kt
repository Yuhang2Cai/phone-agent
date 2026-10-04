// 售后图片模块：退款、进度和评价只加载接口原图；缺图刷新元数据，失败重试原URL，不补演示餐图。
package com.fandazi.app.core.image

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey

/** 固定图片区域的真实加载状态；仅操作图片，不改变金额、资格或用户已填写内容。 */
object AfterSaleImageLoader {
    /**
     * 按接口URL加载图片。URL缺失时点击调用只读元数据刷新；真实加载失败后点击重新请求同一URL。
     * alt仅用于成功图片的无障碍说明，不能掩盖缺图/失败状态；Glide随宿主视图生命周期取消请求。
     */
    fun load(image: ImageView, url: String?, alt: String?, refreshMetadata: () -> Unit) {
        // 现有调用只提供后端公开餐图，保持四参数及尾随回调兼容，不扩大到私人图片。
        load(image,url,alt,CatalogImageRequestOptions(),refreshMetadata)
    }

    /** options传递稳定版本、访问归属及重试模式；只改图片请求，不执行交易或元数据写入。 */
    fun load(image:ImageView,url:String?,alt:String?,options:CatalogImageRequestOptions,refreshMetadata:()->Unit) {
        // 复用前先取消旧请求并清理旧点击与滤镜，避免旧条目的监听或占位影响新图片。
        Glide.with(image).clear(image)
        image.setOnClickListener(null)
        image.isClickable=false
        image.imageTintList=null
        // 售后快照可能含版本化站内路径，失败重试仍使用解析后的同一资源。
        val source = CatalogImageUrls.resolve(url)
        if (source == null) {
            showState(image, "暂无图片", "点击刷新")
            image.setOnClickListener { refreshMetadata() }
            return
        }
        showState(image, "图片加载中", "")
        image.setOnClickListener(null)
        // 普通公开餐图复用两级缓存；仅用户点击失败重试或私人访问绕过缓存，签名保持稳定。
        val plan=CatalogImageRequestPolicy.resolve(options)
        Glide.with(image).load(source)
            .placeholder(image.drawable)
            .signature(ObjectKey(plan.signature))
            .skipMemoryCache(!plan.cacheEnabled)
            .diskCacheStrategy(if(plan.cacheEnabled) DiskCacheStrategy.AUTOMATIC else DiskCacheStrategy.NONE)
            .listener(object : RequestListener<Drawable> {
                /** 失败保持原URL与当前归属；下一次用户点击只重试本图，不自动请求。 */
                override fun onLoadFailed(error: GlideException?, model: Any?, target: Target<Drawable>, first: Boolean): Boolean {
                    showState(image, "图片加载失败", "点击重试")
                    // Glide回调期间不能立刻发起另一请求；用户下一次点击才重新加载，避免自动重试循环。
                    image.setOnClickListener { load(image, source, alt, options.copy(mode=CatalogImageRequestMode.RETRY), refreshMetadata) }
                    return true
                }

                /** 成功清除失败监听，无障碍文本对应当前条目，不替换其他业务内容。 */
                override fun onResourceReady(resource: Drawable, model: Any, target: Target<Drawable>?, sourceType: DataSource, first: Boolean): Boolean {
                    image.contentDescription = alt?.takeIf { it.isNotBlank() } ?: "套餐图片"
                    image.setOnClickListener(null)
                    image.isClickable = false
                    return false
                }
            }).into(image)
    }

    /** 视觉占位和无障碍说明同步更新；灰底与图库符号明确表示状态，而不是餐图本身。 */
    private fun showState(image: ImageView, title: String, action: String) {
        image.setImageDrawable(ImageStateDrawable(image, title, action))
        image.contentDescription = listOf(title, action).filter(String::isNotBlank).joinToString("，")
    }

    /** 使用原生图库符号和短中文状态绘制固定区域占位；不生成或伪造任何业务图片。 */
    private class ImageStateDrawable(image: ImageView, private val title: String, private val action: String) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textSize = 11f * image.resources.displayMetrics.scaledDensity
        private val icon = image.context.getDrawable(android.R.drawable.ic_menu_gallery)!!.mutate().apply { setTint(Color.rgb(137, 147, 157)) }

        /** 基于图片区域大小布局符号，文字字号保持固定；两行说明不挤占邻侧订单摘要。 */
        override fun draw(canvas: Canvas) {
            val width = bounds.width().toFloat()
            val height = bounds.height().toFloat()
            paint.color = Color.rgb(238, 241, 244)
            canvas.drawRoundRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), 8f, 8f, paint)
            val size = (height * .32f).toInt()
            val left = bounds.left + ((width - size) / 2).toInt()
            val top = bounds.top + (height * .12f).toInt()
            icon.setBounds(left, top, left + size, top + size)
            icon.draw(canvas)
            paint.color = Color.rgb(86, 99, 112)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = textSize
            canvas.drawText(title, bounds.exactCenterX(), bounds.top + height * .66f, paint)
            canvas.drawText(action, bounds.exactCenterX(), bounds.top + height * .84f, paint)
        }

        /** 保持Drawable标准透明度契约，绘制区域仍采用不透明中性背景。 */
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        /** 支持宿主颜色滤镜，不改变加载状态或原始图片URL。 */
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        /** 占位包含完整背景，按不透明Drawable报告。 */
        @Deprecated("Android保留的Drawable接口")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}
