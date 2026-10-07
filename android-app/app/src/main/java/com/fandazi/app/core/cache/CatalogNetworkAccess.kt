// 目录网络可用性：连接事实与本次HTTP来源分开；状态只在内存，不持久化账号、坐标或写请求。
package com.fandazi.app.core.cache
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
/** 用于图片/写门禁，不代替页面的最新事实、后端报价及用户确认。 */
object CatalogNetworkAccess {
    @Volatile private var publicReadUnavailable=false
    /** 平台仅报告网络存在；实际后端不可达仍由来源流标记，不能凭WiFi图标宣称请求成功。 */
    fun connected(context:Context):Boolean {val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager;val network=manager.activeNetwork ?: return false;return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true}
    /** 图片/业务写入保守同时要求连接存在和目录服务未出现当前失败。 */
    fun available(context:Context):Boolean=connected(context) && !publicReadUnavailable
    /** 实际目录在线成功才能清失败；刷新阶段不自行假定网络已恢复。 */
    fun record(metadata:CatalogReadMetadata){when(metadata.status){CatalogReadStatus.ONLINE->publicReadUnavailable=false;CatalogReadStatus.REFRESHING->Unit;else->publicReadUnavailable=true}}
    /** 可用GET/显式登录成功证明服务实际可达，仍不能赋予缓存页面购买资格。 */
    fun observedHttpSuccess(){publicReadUnavailable=false}
}
