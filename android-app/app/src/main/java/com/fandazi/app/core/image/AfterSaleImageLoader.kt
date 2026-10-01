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

/** 固定图片区域的真实加载状态；仅操作图片，不改变金额、资格或用户已填写内容。 */
object AfterSaleImageLoader {
    /**
     * 按接口URL加载图片。URL缺失时点击调用只读元数据刷新；真实加载失败后点击重新请求同一URL。
     * alt仅用于成功图片的无障碍说明，不能掩盖缺图/失败状态；Glide随宿主视图生命周期取消请求。
     */
    fun load(image: ImageView, url: String?, alt: String?, refreshMetadata: () -> Unit) {
        Glide.with(image).clear(image)
        val source = url?.takeIf { it.isNotBlank() }
        if (source == null) {
            showState(image, "暂无图片", "点击刷新")
            image.setOnClickListener { refreshMetadata() }
            return
        }
        showState(image, "图片加载中", "")
        image.setOnClickListener(null)
        // 每次明确重试都实际发出原URL请求，不能把失败缓存当作成功，也不能换一张无关餐图。
        Glide.with(image).load(source)
            .placeholder(image.drawable)
            .skipMemoryCache(true).diskCacheStrategy(DiskCacheStrategy.NONE)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(error: GlideException?, model: Any?, target: Target<Drawable>, first: Boolean): Boolean {
                    showState(image, "图片加载失败", "点击重试")
                    // Glide回调期间不能立刻发起另一请求；用户下一次点击才重新加载，避免自动重试循环。
                    image.setOnClickListener { load(image, source, alt, refreshMetadata) }
                    return true
                }

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
