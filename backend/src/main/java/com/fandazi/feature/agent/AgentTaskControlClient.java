// Agent控制边界接口：只传持久命令/查询回执，不提供业务工具执行能力。
package com.fandazi.feature.agent;
import java.util.Map;

/** 测试替身必须显式注入/指定地址；生产HTTP失败不返回伪检查点或降级旧Agent。 */
public interface AgentTaskControlClient {
    /** 发送同commandId的apply/abort决定；返回值仅为Agent持久控制回执。 */
    Map<String,Object> dispatch(Map<String,Object> command);
    /** 只读核查已有命令，不产生新的控制决定、用户输入或执行资格；不存在返回null。 */
    Map<String,Object> lookup(String commandId,AgentTaskRegistryService.Scope scope);
}
