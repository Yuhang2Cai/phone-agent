// 浏览控制模块：首页和搜索复用分类/排序/城市/定位交互，依赖公开目录仓库与 Android 定位权限。
package com.fandazi.app.feature.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.store.CatalogQuery
import com.fandazi.app.feature.store.CatalogRepository
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 在 Fragment 构造阶段注册权限；拒绝/关闭定位不会发距离请求，也不会改变现有查询。 */
class CatalogControls(private val fragment: Fragment, private val repository: () -> CatalogRepository,
    private val query: () -> CatalogQuery, private val load: (CatalogQuery) -> Unit) {
    private val permission = fragment.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) locate() else notify("未开启定位，可继续浏览；距离排序不可用")
    }

    /** 展示稳定的人均排序选项，选择后由服务器按人均字段排序。 */
    fun average() {
        AlertDialog.Builder(fragment.requireContext()).setTitle("人均排序")
            .setItems(arrayOf("推荐排序", "人均从低到高", "人均从高到低")) { _, index ->
                load(query().copy(sort = listOf("RECOMMENDED", "PRICE_ASC", "PRICE_DESC")[index]))
            }.show()
    }

    /** 距离入口先检查权限和真实位置；不将缺定位提示变成业务关键词。 */
    fun distance() {
        if (ContextCompat.checkSelfPermission(fragment.requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        else locate()
    }

    /** 筛选菜单使用既定分类和真实城市；不引入设计禁止的价格区间或商圈筛选。 */
    fun filter() {
        AlertDialog.Builder(fragment.requireContext()).setTitle("筛选")
            .setItems(arrayOf("分类", "城市")) { _, index ->
                if (index == 1) cities() else AlertDialog.Builder(fragment.requireContext()).setTitle("分类")
                    .setItems(CATEGORIES.toTypedArray()) { _, selected -> load(query().copy(category = CATEGORIES[selected])) }.show()
            }.show()
    }

    /** 每次打开读取真实城市目录；失败时提供重试，不缓存硬编码城市名单。 */
    fun cities() {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            when (val result = repository().cities()) {
                is ApiResult.Success -> {
                    val options = result.value
                    AlertDialog.Builder(fragment.requireContext()).setTitle("选择城市")
                        .setItems((listOf("全部城市") + options.map { it.name }).toTypedArray()) { _, index ->
                            load(query().copy(cityCode = if (index == 0) null else options[index - 1].cityCode))
                        }.show()
                }
                is ApiResult.Failure -> AlertDialog.Builder(fragment.requireContext()).setMessage("城市加载失败")
                    .setPositiveButton("重试") { _, _ -> cities() }.setNegativeButton("取消", null).show()
            }
        }
    }

    /** 取近期位置或等待新位置；超时/系统关闭时保留普通浏览，不捏造坐标。 */
    @Suppress("MissingPermission", "DEPRECATION")
    private fun locate() {
        val manager = fragment.requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { manager.isProviderEnabled(it) }
            if (provider == null) { notify("定位服务未开启，可继续浏览"); return@launch }
            val last = manager.getLastKnownLocation(provider)?.takeIf { System.currentTimeMillis() - it.time in 0..300_000 }
            val location = last ?: withTimeoutOrNull(10_000) {
                suspendCancellableCoroutine<Location> { continuation ->
                    val listener = object : LocationListener {
                        /** 首个实际位置完成请求，马上移除监听以避免后台持续定位。 */
                        override fun onLocationChanged(location: Location) {
                            manager.removeUpdates(this)
                            if (continuation.isActive) continuation.resume(location)
                        }
                        /** 兼容旧 Android 定位回调，状态变化本身不是可信坐标。 */
                        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                        /** 提供者开启不代表已取得位置，继续等待实际回调。 */
                        override fun onProviderEnabled(provider: String) = Unit
                        /** 提供者关闭后超时返回普通浏览，不使用默认经纬度。 */
                        override fun onProviderDisabled(provider: String) = Unit
                    }
                    continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
            if (location == null || !HomeSortPolicy.canUseDistanceSort(location.latitude, location.longitude)) {
                notify("暂未取得位置，可继续浏览；距离排序不可用")
            } else {
                // 原始位置仅用于本轮目录请求；业务距离由 Spring 计算，不由 Android 补造。
                load(query().copy(sort = "DISTANCE", latitude = location.latitude, longitude = location.longitude))
            }
        }
    }

    /** 非阻塞提示只描述定位状态，不修改业务结果。 */
    private fun notify(message: String) { Toast.makeText(fragment.requireContext(), message, Toast.LENGTH_LONG).show() }

    companion object {
        /** 已确认的 UI 分类标签，服务器负责映射到实际目录品类。 */
        val CATEGORIES = listOf("推荐", "奶茶咖啡", "快餐小吃", "火锅", "烧烤烤肉", "地方菜系")
    }
}
