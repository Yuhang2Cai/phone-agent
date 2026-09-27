// 图片加载模块：定义该模块的数据与行为。
package com.fandazi.app.core.image

import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners

data class ImageContent(val title: String, val loadedImageUrl: String?)

object ImageContentFallback {
// 渲染当前数据。
    fun render(title: String, imageUrl: String?, load: (String) -> Boolean): ImageContent {
        val loadedUrl = imageUrl?.takeIf(String::isNotBlank)?.takeIf(load)
        return ImageContent(title = title, loadedImageUrl = loadedUrl)
    }
}

object ImageLoader {
// 加载当前数据。
    fun load(
        imageView: ImageView,
        url: String?,
        @DrawableRes placeholder: Int,
        @DrawableRes error: Int
    ) {
        val requestUrl = ImageContentFallback.render("", url) { true }.loadedImageUrl
        if (requestUrl == null) {
            imageView.setImageResource(error)
            return
        }
        val radius = (IMAGE_CORNER_DP * imageView.resources.displayMetrics.density).toInt()
        Glide.with(imageView)
            .load(requestUrl)
            .placeholder(placeholder)
            .error(error)
            .skipMemoryCache(false)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .transform(CenterCrop(), RoundedCorners(radius))
            .into(imageView)
    }

    private const val IMAGE_CORNER_DP = 12
}
