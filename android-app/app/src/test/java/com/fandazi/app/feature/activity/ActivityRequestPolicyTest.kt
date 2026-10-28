// 活动桥接请求契约测试：校验白名单、身份/奖励输入拒绝和幂等键，防止 JS 取得任意网络能力。
package com.fandazi.app.feature.activity

import org.junit.Assert.*
import org.junit.Test

/** 直接运行生产请求策略，不模拟其结果；服务器事实和认证凭据不由 JS 输入决定。 */
class ActivityRequestPolicyTest {
    /** 领取请求必须按冻结契约构造目标和原幂等键，错误路由会使该断言失败。 */
    @Test fun acceptingTaskUsesVersionAndOriginalRequestKey() {
        val request = ActivityRequestPolicy.resolve("accept", """{"id":"10000000-0000-0000-0000-000000000001","version":3,"requestId":"20000000-0000-0000-0000-000000000001"}""")
        assertEquals("POST", request.method)
        assertEquals("activities/tasks/10000000-0000-0000-0000-000000000001/accept", request.path)
        assertEquals("20000000-0000-0000-0000-000000000001", request.headers["Idempotency-Key"])
        assertEquals("{\"expectedRuleVersion\":3}", request.body)
    }

    /** 非白名单动作与路径注入必须在发包前拒绝，不允许读取任意原生地址。 */
    @Test fun arbitraryActionOrPathCannotReachNetwork() {
        assertThrows(IllegalArgumentException::class.java) { ActivityRequestPolicy.resolve("https://evil.invalid", "{}") }
        assertThrows(IllegalArgumentException::class.java) { ActivityRequestPolicy.resolve("detail", """{"id":"../coins/account"}""") }
    }

    /** 即使其余字段合法，也拒绝用户归属和奖励等额外输入，避免协议逐渐放宽。 */
    @Test fun identityAndRewardAreNeverClientAuthority() {
        for (field in listOf("userId", "reward", "completedCount")) {
            assertThrows(IllegalArgumentException::class.java) {
                ActivityRequestPolicy.resolve("claim", """{"id":"10000000-0000-0000-0000-000000000001","requestId":"20000000-0000-0000-0000-000000000001","$field":9}""")
            }
        }
    }

    /** 缺失幂等键不能自动补新键；否则超时重试可能失去原请求身份。 */
    @Test fun writeWithoutRequestKeyIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ActivityRequestPolicy.resolve("claim", """{"id":"10000000-0000-0000-0000-000000000001"}""") }
    }
}
