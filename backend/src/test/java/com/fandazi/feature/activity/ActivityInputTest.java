// 活动输入测试：拒绝伪造权威字段与有损数字转换，保留可定位的 400 错误契约。
package com.fandazi.feature.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 无数据库测试只验证 HTTP 到服务输入的严格转换。 */
class ActivityInputTest {
    private final ObjectMapper mapper=new ObjectMapper();
    /** 合法版本原样传递，不能默认零或猜测当前版本。 */
    @Test void exactVersionIsPreserved() throws Exception {assertEquals(3,ActivityInput.version(mapper.readTree("{\"expectedRuleVersion\":3}")));}
    /** 缺失、负数、小数、字符串及溢出均拒绝，不执行 Jackson 宽松 asInt 转换。 */
    @Test void malformedVersionsAreRejected() throws Exception {
        for(String json:new String[]{"{}","null","[]","{\"expectedRuleVersion\":0}","{\"expectedRuleVersion\":1.5}","{\"expectedRuleVersion\":\"1\"}","{\"expectedRuleVersion\":2147483648}"}) {
            var body=mapper.readTree(json);assertEquals(400,assertThrows(ActivityException.class,()->ActivityInput.version(body),json).status());
        }
    }
    /** 额外用户/奖励字段即使服务当前不使用，也必须拒绝而非悄悄忽略。 */
    @Test void authoritativeFieldsCannotBeSmuggled() throws Exception {
        var body=mapper.readTree("{\"expectedRuleVersion\":1,\"rewardCoins\":999}");
        assertEquals("INVALID_REQUEST",assertThrows(ActivityException.class,()->ActivityInput.version(body)).reasonCode());
        var claim=mapper.readTree("{\"userId\":2}");assertThrows(ActivityException.class,()->ActivityInput.empty(claim));
        assertDoesNotThrow(()->ActivityInput.empty(mapper.createObjectNode()));
    }
}
