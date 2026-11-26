// 活动领域错误：只承载稳定公开原因码和 HTTP 状态，不包含订单、凭据或数据库原文。
package com.fandazi.feature.activity;

/** 控制器将该异常转换为 reasonCode；事务遇到异常整体回滚。 */
public final class ActivityException extends RuntimeException {
    private final int status;
    private final String reasonCode;
    /** status 为标准 HTTP 错误码，reasonCode 为客户端白名单键。 */
    public ActivityException(int status, String reasonCode) {
        super(reasonCode); this.status = status; this.reasonCode = reasonCode;
    }
    /** 返回公开错误状态，不由客户端覆盖。 */
    public int status() { return status; }
    /** 返回稳定原因码，便于页面区分版本冲突和资格变化。 */
    public String reasonCode() { return reasonCode; }
}
