// 目录餐图地址模块：把后端审核过的站内资源路径解析为当前环境的绝对地址。
package com.fandazi.app.core.image

import com.fandazi.app.BuildConfig
import java.net.URI

/**
 * Android 与后端共享目录图片相对路径；主机从当前构建的 API 基址取得。
 * 这样模拟器和实际部署可以使用各自的后端地址，缓存键仍由资源版本与摘要固定。
 */
object CatalogImageUrls {
    private val hostedPath = Regex("^/catalog-images/v[0-9]+/(full|thumb)/[a-z0-9-]+-[a-f0-9]{12}\\.jpg$")

    /**
     * 输入可为空、绝对 HTTP(S) 地址或固定版本化餐图路径；返回 Glide 可读的绝对 URL。
     * 非法路径、无效主机或不支持的协议返回 null，调用方显示自己的失败状态，不猜图。
     */
    fun resolve(url: String?, apiBaseUrl: String = BuildConfig.API_BASE_URL): String? {
        val source = url?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return try {
            val parsed = URI.create(source)
            if (parsed.isAbsolute) {
                // 保留历史 HTTP 图片的兼容行为，但拒绝文件协议和没有主机的外部地址。
                source.takeIf { parsed.scheme in setOf("http", "https") && parsed.host != null }
            } else {
                if (!hostedPath.matches(source)) return null
                val base = URI.create(apiBaseUrl)
                if (base.scheme !in setOf("http", "https") || base.host == null) return null
                base.resolve(source).toString()
            }
        } catch (_: IllegalArgumentException) {
            // 异常 URL 不得退回通用占位餐图或被交给 Glide 作为本地文件路径。
            null
        }
    }
}
