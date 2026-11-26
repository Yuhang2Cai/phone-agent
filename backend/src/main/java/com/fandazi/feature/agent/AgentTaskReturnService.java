// 明确返回指令协调器：短事务预备→事务外安全停点HTTP→短事务消费，恢复焦点后不调用业务Agent。
package com.fandazi.feature.agent;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 注册来源/版本未满足时只保留恢复入口；进程重启只处理已有指令，不根据最近任务自动恢复。 */
@Service
public class AgentTaskReturnService {
    private final AgentTaskReturnRegistry registry;private final AgentTaskCommandRepository commands;private final AgentTaskReconciliationService reconciliation;
    private final boolean enabled,automatic;
    /** 默认功能关闭；验收可关闭后台扫描，由同一主Agent显式控制故障时序。 */
    public AgentTaskReturnService(AgentTaskReturnRegistry registry,AgentTaskCommandRepository commands,AgentTaskReconciliationService reconciliation,
            @Value("${agent.task-lifecycle.enabled:false}") boolean enabled,@Value("${agent.task-lifecycle.reconcile-enabled:true}") boolean automatic) {
        this.registry=registry;this.commands=commands;this.reconciliation=reconciliation;this.enabled=enabled;this.automatic=automatic;
    }
    /** 一个指令只推进已持久恢复控制；控制尚未安全提交则保持prepared，不虚报返回成功。 */
    public void process(String id) {
        var scope=commands.findById(id).orElseThrow(AgentTaskRegistryService::missing).scope();
        String returnId=registry.prepare(id,scope);if(returnId==null)return;
        var status=reconciliation.process(returnId,Instant.now());if("committed".equals(status.get("state")))registry.finish(id,returnId,scope);
    }
    /** 网关查询完成后触发只恢复焦点的控制，方法不注入AgentServiceClient/工具执行器。 */
    public void completedTask(String taskId){for(var row:commands.returnsForTask(taskId))process(row.id());}
    /** 有界启动/周期补偿保留原命令身份，原目标条件变化时不会错误恢复。 */
    @Scheduled(fixedDelay=1000) public void tick(){if(enabled&&automatic)for(var row:commands.returnCandidates(PageRequest.of(0,50)))process(row.id());}
}
