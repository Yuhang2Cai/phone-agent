// 当前可见页面的恢复读取：按平台事件事实防抖，服务不可达时有界只读重试；不排队/重放业务写。
package com.fandazi.app.core.cache

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 页面离开立即注销监听及取消轮询，查询、锚点、选择与私人草稿由原ViewModel保留。 */
object CatalogRecoveryRefresh {
    /** retryNeeded只反映最终失败状态；refresh重读当前公开查询，不恢复任何业务写请求。 */
    @OptIn(FlowPreview::class)
    fun attach(fragment:Fragment,retryNeeded:()->Boolean,refresh:()->Unit) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val context=fragment.requireContext().applicationContext
                launch {
                    val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    // 页面隐藏期间也可能断网/恢复，首个事实不能一律丢弃；在线正常初始化才避免重复请求。
                    var first=true
                    callbackFlow {
                        // 同步查询只作注册前的基线；回调中读取activeNetwork/能力可能仍是旧对象。
                        var current=manager.activeNetwork
                        trySend(CatalogNetworkAccess.connected(context))
                        val callback=object:ConnectivityManager.NetworkCallback() {
                            /** 新默认网络要等随后能力事件，不能凭“出现”就宣称互联网可用。 */
                            override fun onAvailable(network:Network) {current=network}
                            /** 使用事件自带能力，忽略已被新默认网络替代的迟到通知。 */
                            override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities) {
                                if(network==current)trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                            }
                            /** 当前默认网络丢失即发离线；旧网络的迟到丢失不能覆盖新网络。 */
                            override fun onLost(network:Network) {
                                if(network==current){current=null;trySend(false)}
                            }
                        }
                        manager.registerDefaultNetworkCallback(callback)
                        // 生命周期取消即解除平台引用；离开页的回调不能更新旧视图。
                        awaitClose {manager.unregisterNetworkCallback(callback)}
                    }.distinctUntilChanged().filter {connected->
                        val shouldRefresh=!first || !connected || retryNeeded()
                        first=false
                        shouldRefresh
                    }.debounce(500).collect {refresh()}
                }
                launch {
                    // 服务故障未必产生平台事件；只在终态失败每5秒重读当前公开条件，避免取消在途请求。
                    while(isActive){delay(5_000);if(retryNeeded())refresh()}
                }
            }
        }
    }
    /** 在线或正在刷新不触发定时重试，缓存最终失败才允许新的只读尝试。 */
    fun needed(metadata:CatalogReadMetadata)=metadata.status!=CatalogReadStatus.ONLINE && metadata.status!=CatalogReadStatus.REFRESHING
}
