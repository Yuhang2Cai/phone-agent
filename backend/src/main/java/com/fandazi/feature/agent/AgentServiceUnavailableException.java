// 智能助手模块：标识业务异常边界。
package com.fandazi.feature.agent;

public class AgentServiceUnavailableException extends RuntimeException {
    public AgentServiceUnavailableException(String message) { super(message); }
    public AgentServiceUnavailableException(String message, Throwable cause) { super(message, cause); }
}
