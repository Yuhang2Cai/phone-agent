// 智能助手模块：标识业务异常边界。
package com.fandazi.feature.agent;

public class AgentServiceUnavailableException extends RuntimeException {
// 初始化对象依赖与默认状态。
    public AgentServiceUnavailableException(String message) { super(message); }
// 初始化对象依赖与默认状态。
    public AgentServiceUnavailableException(String message, Throwable cause) { super(message, cause); }
}
