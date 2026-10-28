// 活动 HTTP 输入边界：只允许预期规则版本，不信任完成次数、奖励金额和用户身份字段。
package com.fandazi.feature.activity;

import com.fasterxml.jackson.databind.JsonNode;

/** 将外部 JSON 转成有限业务参数，错误统一为稳定的 400 原因码。 */
final class ActivityInput {
    private ActivityInput() {}
    /** 接受且仅接受一个正整数 expectedRuleVersion 字段。 */
    static int version(JsonNode body) {
        if(body==null||!body.isObject()||body.size()!=1||!body.has("expectedRuleVersion"))throw new ActivityException(400,"INVALID_REQUEST");
        var version=body.get("expectedRuleVersion");
        // 不做字符串到整数、小数截断或溢出收窄，用户确认的版本必须精确保持。
        if(!version.isIntegralNumber()||!version.canConvertToInt()||version.intValue()<=0)throw new ActivityException(400,"INVALID_REQUEST");
        return version.intValue();
    }
    /** 领奖无业务载荷，必须为空 JSON 对象，禁止客户端覆盖奖励金额。 */
    static void empty(JsonNode body) {
        if(body==null||!body.isObject()||!body.isEmpty())throw new ActivityException(400,"INVALID_REQUEST");
    }
}
