/* 刷新拒绝类型：与网络错误、普通登录密码错误分开，客户端只据此终止长期会话。 */
package com.fandazi.feature.auth;
/** 对外统一拒绝信息，不泄露会话存在性、归属或秘密摘要。 */
public class RefreshRejectedException extends RuntimeException {
    /** 固定中文提示；具体安全状态留在数据库，不包含凭据正文。 */
    public RefreshRejectedException() {super("登录已失效，请重新登录");}
}
