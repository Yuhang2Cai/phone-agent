// 秒杀时间边界回归：权威锚点、网络往返和设备重启均不能让本地墙钟决定抢购资格。
package com.fandazi.app.feature.seckill

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** 纯时间规则定向测试，不以虚拟时间替代真实设备开抢和300秒验收。 */
class SeckillClockTest {
    private val server = Instant.parse("2026-10-29T04:00:00Z")
    /** 显示随单调时钟递增，不读取用户可修改的日历时间。 */
    @Test fun elapsedTimeAdvancesOnlyFromServerAnchor() {
        val clock=SeckillClock();clock.synchronize(server,1000,1000)
        assertEquals(server.plusSeconds(5),clock.now(6000))
    }
    /** 截止相等为零，之后保持零；归零本身不返回任何可购买事实。 */
    @Test fun exactDeadlineDoesNotGoNegative() {
        val clock=SeckillClock();clock.synchronize(server,1000,1000)
        assertEquals(1L,clock.remainingMillis(server.plusSeconds(1),1999))
        assertEquals(0L,clock.remainingMillis(server.plusSeconds(1),2000))
        assertEquals(0L,clock.remainingMillis(server.plusSeconds(1),3000))
    }
    /** 单调时间回退表示重启或非法锚点，旧估计不能继续显示为已核对的时间。 */
    @Test fun rebootInvalidatesAnchor() {
        val clock=SeckillClock();clock.synchronize(server,1000,1100)
        assertNotNull(clock.now(1100));assertNull(clock.now(10))
    }
    /** 请求跨度仍用于拒绝失真的超长同步，但不能据此推测服务端时间已经前进。 */
    @Test fun boundedRequestSpanDoesNotSpeculativelyAdvanceTime() {
        val clock=SeckillClock();clock.synchronize(server,1000,1200)
        assertEquals(server,clock.now(1200))
    }
    /**
     * 请求起止还包含认证续期、调度排队等本地等待，不能假定整段耗时都在服务端时间两侧对称分布。
     * 否则一次1.2秒的本地等待会把权威时钟凭空推进600毫秒，既污染边界测量，也可能提前开放按钮。
     */
    @Test fun localPreRequestWaitCannotAdvanceServerTime() {
        val clock=SeckillClock();clock.synchronize(server,1000,2200)
        assertEquals(server,clock.now(2200))
    }
}
