package com.fandazi.feature.agent;

/** Agent执行入口明确拒绝的安全边界：保留HTTP状态及有限等待提示，不声称Spring尚未保存轮次。
 * 只由实际上游429/503响应创建，网络断连和未知写结果不得转换成本异常；禁止自动重发业务。
 */
public final class AgentUpstreamAdmissionException extends AgentServiceUnavailableException {
    private final int status;
    private final int retryAfter;

    /** 上游正文从不进入公开消息；只接收429/503及整数秒，异常头用最小等待提示并保持拒绝。 */
    public AgentUpstreamAdmissionException(int status, String retryHeader) {
        super("本次执行暂不可用，请稍后再试；已有任务进度保留");
        if(status!=429 && status!=503)throw new IllegalArgumentException("非准入拒绝状态");
        this.status=status;
        int seconds=1;
        // Agent使用有限整数秒；不解析任意日期、负数或超长值，不把头部内容反射到客户端。
        if(retryHeader!=null && retryHeader.matches("[0-9]{1,4}")) {
            int parsed=Integer.parseInt(retryHeader);
            if(parsed>=1 && parsed<=3600)seconds=parsed;
        }
        this.retryAfter=seconds;
    }
    /** 返回实际上游拒绝状态，不将429降为500或成功回复。 */
    public int status(){return status;}
    /** 仅提供等待建议，不表示当前确认仍有效，也不授权自动重试。 */
    public int retryAfter(){return retryAfter;}
}
