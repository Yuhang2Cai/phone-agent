// JVM网络测试前置：建立平台在线WiFi及互联网能力，恢复共享内存状态；不跳过正式门禁。
package com.fandazi.app.core.cache
import android.content.Context
import android.net.*
import org.robolectric.Shadows
import org.junit.Assert.assertTrue
/** 只供Robolectric场景，所有认证/请求/释放断言仍执行真实生产代码。 */
object CatalogOnlineTestEnvironment {
    /** 设置受控网络事实，公开服务状态初始化为尚未失败，不注入业务响应或身份。 */
    @Suppress("DEPRECATION") fun establish(context:Context) {
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow=Shadows.shadowOf(manager)
        val info=org.robolectric.shadows.ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_WIFI,0,true,true)
        shadow.setActiveNetworkInfo(info)
        val network=manager.activeNetwork ?: error("在线测试缺少平台网络")
        val capabilities=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadow.setNetworkCapabilities(network,capabilities)
        CatalogNetworkAccess.observedHttpSuccess();assertTrue(CatalogNetworkAccess.connected(context))
    }
}
