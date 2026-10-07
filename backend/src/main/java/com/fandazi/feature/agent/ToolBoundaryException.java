// 工具边界错误模块：使用稳定业务错误码，禁止把堆栈、SQL 或完整请求内容返回 Agent。
package com.fandazi.feature.agent;

/** 表示可预期的工具拒绝；事务层抛出异常回滚，HTTP 层再构造标准失败结果。 */
public class ToolBoundaryException extends com.fandazi.feature.DomainException {
    private final String code;
    /** 保存可公开的中文消息和契约错误码，不包含用户输入或依赖内部错误。 */
    public ToolBoundaryException(String code, String message) { super(message); this.code = code; }
    /** 返回契约错误码供控制器映射。 */
    public String getCode() { return code; }
}
