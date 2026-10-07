// 智能助手模块：封装远程服务通信。
package com.fandazi.feature.agent;

// 运行当前数据。
public interface AgentServiceClient { AgentResult run(AgentRunRequest request); }
