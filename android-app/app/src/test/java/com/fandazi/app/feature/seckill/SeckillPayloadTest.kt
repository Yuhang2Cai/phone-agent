// 秒杀资格协议回归：数字字符串、未知状态和互相矛盾的资格不能进入可购买界面。
package com.fandazi.app.feature.seckill

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** 直接验证真实解析器，错误数据必须拒绝而不是补默认值。 */
class SeckillPayloadTest {
    private val body="""{"activityId":"activity","activityVersion":1,"state":"LIVE","serverTime":"2026-10-29T04:00:00Z","startsAt":"2026-10-29T03:00:00Z","endsAt":"2026-10-29T05:00:00Z","availableStock":10,"remainingQuota":1,"canPurchase":true,"reasonCode":"AVAILABLE"}"""
    /** 有效的权威资格能原样消费，不因收紧格式破坏正常购买。 */
    @Test fun validEligibilityPreservesAuthority(){assertTrue(SeckillPayload.eligibility(body).canPurchase)}
    /** Gson允许数字字符串转换，但协议要求真实数字，必须阻止错误类型继续授权。 */
    @Test fun stockStringIsRejected(){val value=JsonParser.parseString(body).asJsonObject;value.addProperty("availableStock","10");assertThrows(IllegalArgumentException::class.java){SeckillPayload.eligibility(value.toString())}}
    /** 未知状态即使canPurchase=false也不可作为正常活动显示，避免错走已开抢分支。 */
    @Test fun unknownStateIsRejected(){val value=JsonParser.parseString(body).asJsonObject;value.addProperty("state","NEW_STATE");value.addProperty("canPurchase",false);assertThrows(IllegalArgumentException::class.java){SeckillPayload.eligibility(value.toString())}}
    /** 活动版本必须非负，负版本不得与旧确认进行匹配。 */
    @Test fun negativeVersionIsRejected(){val value=JsonParser.parseString(body).asJsonObject;value.addProperty("activityVersion",-1);assertThrows(IllegalArgumentException::class.java){SeckillPayload.eligibility(value.toString())}}
    /** 身份字段必须为真实字符串，不能把数值ID通过asString冒充公开身份。 */
    @Test fun numericIdentityIsRejected(){val value=JsonParser.parseString(body).asJsonObject;value.addProperty("activityId",99);assertThrows(IllegalArgumentException::class.java){SeckillPayload.eligibility(value.toString())}}
}
