// 秒杀倒计时时钟：把服务器UTC时间锚定到设备单调时钟；本模块不发请求或创建订单。
package com.fandazi.app.feature.seckill

import java.time.Instant

/** 仅负责显示时间，归零后的购买资格必须重新向服务器核对。 */
class SeckillClock {
    private var anchor: Instant? = null
    private var receivedAt: Long = 0
    /** 请求起止使用elapsedRealtime毫秒；同步失败不能建立可信锚点。 */
    fun synchronize(serverTime: Instant, requestStarted: Long, responseReceived: Long) {
        // 先撤销旧锚点，非法或跨重启采样不能继续借用上一份有效时钟。
        anchor=null
        require(requestStarted>=0 && responseReceived>=requestStarted)
        val requestSpan=responseReceived-requestStarted
        require(requestSpan<=20_000) { "同步请求超时，必须重新获取服务器时间" }
        // 请求跨度还包含认证续期和线程排队，不能假设它在服务端采样点两侧对称分布。
        // 将服务端采样时刻保守地锚到收到响应这一刻，只会让显示稍晚，不会把本地等待
        // 折半后凭空推进权威时间，进而提前开放抢购或污染边界判断。
        receivedAt=responseReceived
        anchor=serverTime
    }
    /** 无锚点或设备单调时钟回退返回空，调用方必须禁用购买并重新同步。 */
    fun now(elapsedRealtime: Long): Instant? {
        if(elapsedRealtime<receivedAt)return null
        return anchor?.plusMillis(elapsedRealtime-receivedAt)
    }
    /** 剩余时间不出现负数；未知时间不能被当作零而误认为活动已开始。 */
    fun remainingMillis(deadline: Instant, elapsedRealtime: Long): Long? = now(elapsedRealtime)?.let {
        java.time.Duration.between(it,deadline).toMillis().coerceAtLeast(0)
    }
}
