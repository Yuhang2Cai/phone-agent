// UI17缓存提示：复用白底圆角组件，时间来自真实读取毫秒，动态价格/城市/图片不写入设计示例。
package com.fandazi.app.core.cache
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import com.fandazi.app.R
import java.time.*
import java.time.format.DateTimeFormatter
/** 来源提示只显示和触发当前只读重试，不发AI、报价或交易。 */
object CatalogCacheBanner {
    private data class Display(var previous:CatalogReadStatus?=null,var recovered:Boolean=false,var missingImage:Boolean=false,var hadOffline:Boolean=false,var refreshingTitle:String="缓存先显")
    /** 基于真实时间展示更新时间，未知时间必须明确未知，不写死设计的10:20。 */
    fun time(metadata:CatalogReadMetadata):String=metadata.fetchedAt?.let{Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("HH:mm"))} ?: "未知"
    /** 未缓存/超时效/不完整的列表说明，整个业务列表应隐藏，不从别的条件补齐。 */
    fun empty(metadata:CatalogReadMetadata):String=when(metadata.status){CatalogReadStatus.EXPIRED->"这份缓存太旧，暂不展示套餐价格和库存。\n请联网后重试。";CatalogReadStatus.INCOMPLETE->"无法离线取得当前城市和分类的完整结果。\n联网后继续查询。";else->"当前没有可浏览的离线数据\n联网后可继续查看当前筛选。"}
    /** 一个页面可以显示通用目录或详情专用文案；缓存状态永远不承诺当前可买。 */
    fun bind(root:View,metadata:CatalogReadMetadata,detail:Boolean=false,retry:(()->Unit)?=null) {
        CatalogNetworkAccess.record(metadata)
        val banner=root.findViewById<View>(R.id.catalog_cache_banner) ?: return
        val display=(banner.tag as? Display) ?: Display().also{banner.tag=it}
        val previous=display.previous
        if(CatalogRecoveryRefresh.needed(metadata))display.hadOffline=true
        if(metadata.status==CatalogReadStatus.ONLINE){display.missingImage=false;if(display.hadOffline)display.recovered=true}
        if(metadata.status==CatalogReadStatus.ONLINE && previous!=null && previous!=CatalogReadStatus.ONLINE && previous!=CatalogReadStatus.REFRESHING)display.recovered=true
        if(metadata.status==CatalogReadStatus.REFRESHING && previous!=CatalogReadStatus.REFRESHING){display.refreshingTitle=if(previous==null)"缓存先显" else "在线刷新中"}
        val visible=metadata.status!=CatalogReadStatus.ONLINE || display.recovered
        banner.isVisible=visible && !(metadata.status==CatalogReadStatus.REFRESHING && metadata.fetchedAt==null)
        if(!banner.isVisible){if(metadata.status==CatalogReadStatus.ONLINE)display.previous=metadata.status;return}
        val clock=time(metadata)
        val age=metadata.fetchedAt?.let{((System.currentTimeMillis()-it).coerceAtLeast(0)/3_600_000).coerceAtLeast(1)}
        val title=when(metadata.status){CatalogReadStatus.ONLINE->"网络已恢复";CatalogReadStatus.REFRESHING->display.refreshingTitle;CatalogReadStatus.OFFLINE->if(detail)"离线快照 · 仅供浏览"else "离线可浏览";CatalogReadStatus.STALE->if(detail)"离线快照 · 仅供浏览"else "离线过期快照";CatalogReadStatus.MISSING->"没有离线缓存";CatalogReadStatus.EXPIRED->"缓存已超过24小时";CatalogReadStatus.INCOMPLETE->"离线结果不完整"}
        val subtitle=when(metadata.status){CatalogReadStatus.ONLINE->"已更新当前结果 · 保留所选套餐";CatalogReadStatus.REFRESHING->"更新于$clock · 正在获取最新结果";CatalogReadStatus.OFFLINE->if(detail)"${clock}更新 · 购买需联网核对"else "更新于$clock · 价格库存为快照";CatalogReadStatus.STALE->"${age}小时前更新 · 请联网核对最新信息";CatalogReadStatus.MISSING->"当前条件未缓存，请联网后加载";CatalogReadStatus.EXPIRED->"请联网获取最新数据";CatalogReadStatus.INCOMPLETE->"当前筛选没有完整缓存"}
        val missing=metadata.status==CatalogReadStatus.OFFLINE && display.missingImage
        root.findViewById<TextView>(R.id.catalog_cache_title).text=if(missing)"离线图片未缓存"else title;root.findViewById<TextView>(R.id.catalog_cache_subtitle).text=if(missing)"已缓存资料可读 · 未下载图片不可用"else subtitle
        banner.contentDescription="catalog-cache-${metadata.status.name.lowercase()}";banner.setOnClickListener(if(retry==null)null else View.OnClickListener{retry()})
        display.previous=metadata.status
    }
    /** Glide真正离线未命中才标记UI17I，不能用图片URL缺失假装已有缓存；详情仍保留交易只读提示。 */
    fun imageMissing(image:android.widget.ImageView) {
        val root=image.rootView
        if(root.findViewById<View>(R.id.home_store_list)==null)return
        val banner=root.findViewById<View>(R.id.catalog_cache_banner) ?: return
        val display=banner.tag as? Display ?: return
        if(!banner.isVisible || display.previous!=CatalogReadStatus.OFFLINE)return
        display.missingImage=true
        root.findViewById<TextView>(R.id.catalog_cache_title).text="离线图片未缓存"
        root.findViewById<TextView>(R.id.catalog_cache_subtitle).text="已缓存资料可读 · 未下载图片不可用"
    }

}
