// 任务后台协调配置：仅显式启用生命周期功能时注册调度，旧部署默认不产生后台控制请求。
package com.fandazi.feature.agent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 后台只交接控制/重查/事件，不执行模型业务工具；六次/60秒预算由持久命令检查。 */
@Configuration @EnableScheduling
@ConditionalOnProperty(name="agent.task-lifecycle.enabled",havingValue="true")
class AgentTaskSchedulingConfiguration {}
