// 套餐详情协议测试：缺失数字不能被默认零冒充真实金额；外部合成JSON不写入业务服务。
package com.fandazi.app.feature.deal

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

/** 只检查字段存在性和原始类型，UUID/归属及日期范围由页面状态边界另行验证。 */
class DealDetailPayloadContractTest {
    /** 逐一删除所有必需字段；若解析仍成功，可信内容可能出现虚构默认价或库存。 */
    @Test fun missingRequiredFieldsAreRejected() {
        fields.forEach { key ->
            val body=JsonParser.parseString(valid).asJsonObject;body.remove(key)
            assertTrue("缺字段必须拒绝：$key",runCatching { DealPackagePayloadParser.parseDetail(body.toString()) }.isFailure)
        }
    }
    /** 金额不可用字符串或小数截断为整数；后续页面只能展示接口真实整数分。 */
    @Test fun coerciblePriceIsNotTrusted() {
        listOf("\"1234\"","12.75").forEach { price ->
            assertTrue("错误金额类型必须拒绝",runCatching { DealPackagePayloadParser.parseDetail(valid.replace("1234",price)) }.isFailure)
        }
    }
    /** 合法零元是接口事实，存在性校验不能擅自增加套餐必须收费的业务规则。 */
    @Test fun explicitZeroPriceRemainsZero() { assertEquals(0,DealPackagePayloadParser.parseDetail(valid.replace("1234","0")).priceCents) }
    companion object {
        /** 与现有后端DTO一致的必需字段集合，图片保持可空，不从标题推断人数。 */
        private val fields=listOf("publicId","storePublicId","title","description","priceCents","originalPriceCents","inventory","validFrom","validTo","active","purchaseLimit")
        private const val valid="""{"publicId":"20000000-0000-0000-0000-000000000001","storePublicId":"10000000-0000-0000-0000-000000000001","title":"合成套餐","description":"合成说明","priceCents":1234,"originalPriceCents":2500,"inventory":10,"validFrom":"2026-01-01","validTo":"2030-12-31","active":true,"purchaseLimit":2}"""
    }
}
