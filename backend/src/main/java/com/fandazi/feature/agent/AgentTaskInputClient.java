// 任务归属内部客户端边界：只解释原句/公开目录，不执行工具、不修改任务、不回退旧协议。
package com.fandazi.feature.agent;
import java.util.Map;

/** 实际HTTP和测试替身共用一次归属响应，Spring在真正写入前仍复核scope/版本及参数。 */
public interface AgentTaskInputClient {
    /** 请求只包含可信scope、原文及公开目录；返回已持久分类，失败不先暂停原任务。 */
    Map<String,Object> interpret(Long user,String sessionId,Map<String,Object> request);
}
