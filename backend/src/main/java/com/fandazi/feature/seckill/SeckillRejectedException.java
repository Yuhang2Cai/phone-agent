// 秒杀公开拒绝类型：页面返回稳定原因，工具出口保留既有错误大类并附加同一原因。
package com.fandazi.feature.seckill;
/** 与普通团购原有错误映射分离，避免为新增场景追溯改变历史客户端合同。 */
public class SeckillRejectedException extends com.fandazi.feature.agent.ToolBoundaryException {
    /** 保存固定业务原因和可公开文案，不接受SQL、凭据或完整请求作为消息。 */
    public SeckillRejectedException(String reasonCode,String message){super(reasonCode,message);}

    /** 构造既有工具信封中的错误对象；业务拒绝已经有确定结果，不能提示自动重发购买或支付。 */
    public java.util.Map<String,Object> toolError(){
        // 原错误大类决定恢复策略，细粒度原因决定展示；不扩大历史协议的错误枚举。
        String category=switch(getCode()){
            case "FORBIDDEN","NOT_FOUND"->getCode();
            case "PAYMENT_EXPIRED","ENDED"->"EXPIRED";
            case "AMOUNT_EXCEEDED"->"VALIDATION_ERROR";
            default->"CONFLICT";
        };
        return java.util.Map.of("code",category,"reasonCode",getCode(),"message",getMessage(),"retryable",false);
    }
}
