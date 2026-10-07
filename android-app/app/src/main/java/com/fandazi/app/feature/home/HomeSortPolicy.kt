package com.fandazi.app.feature.home

/** 首页排序策略：距离排序必须建立在真实定位坐标之上。 */
object HomeSortPolicy {
    /**
     * 判断当前是否允许请求距离排序。
     * @param latitude 当前纬度，拒绝定位时为空
     * @param longitude 当前经度，拒绝定位时为空
     */
    fun canUseDistanceSort(latitude: Double?, longitude: Double?): Boolean =
        latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0
}
