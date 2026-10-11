// 明确返回指令事务模块：来源/目标/焦点CAS核验后只预备同一个恢复控制，不在事务中HTTP或模型。
package com.fandazi.feature.agent;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 独立Bean保证外部协调器调用的事务拦截生效，避免同类方法自调用丢失事务边界。 */
@Service
public class AgentTaskReturnRegistry {
    private final AgentTaskRegistryService registry;private final AgentTaskCommandRepository commands;private final AgentTaskControlService controls;
    /** 只依赖同库调度组件；自动返回绝不注入Agent业务运行或领域工具。 */
    public AgentTaskReturnRegistry(AgentTaskRegistryService registry,AgentTaskCommandRepository commands,AgentTaskControlService controls) {
        this.registry=registry;this.commands=commands;this.controls=controls;
    }
    /** 返回持久恢复命令ID；尚未成功查询返回null，条件已变化则登记失效，不猜测焦点。 */
    @Transactional public String prepare(String originId,AgentTaskRegistryService.Scope scope) {
        var session=registry.lockScope(scope.userId(),scope.sessionId());
        // 第一条一致性读发生在会话锁之后，避免等待期间JPA缓存/旧读视图复用另一次返回状态。
        var origin=commands.findById(originId).orElseThrow(AgentTaskRegistryService::missing);
        if(!origin.scope().equals(scope))throw AgentTaskRegistryService.missing();
        if(!"committed".equals(origin.state())||origin.returnStatus()==null||!Set.of("registered","prepared").contains(origin.returnStatus()))return null;
        @SuppressWarnings("unchecked") var hint=(Map<String,Object>)origin.details().get("returnTo");
        if(hint==null)throw AgentTaskRegistryService.conflict("返回指令缺少原始来源");
        String targetId=(String)hint.get("taskId");var ids=new TreeSet<>(List.of(targetId,origin.target()));
        var locked=new HashMap<String,AgentTaskEntity>();for(String id:ids)locked.put(id,registry.lockTask(scope,id));
        if("prepared".equals(origin.returnStatus()))return origin.returnCommandId();
        var lookup=locked.get(origin.target());var target=locked.get(targetId);
        if(!"completed".equals(lookup.lifecycle())) {
            if(Set.of("cancelled","failed").contains(lookup.lifecycle()))origin.finishReturn("invalidated");
            return null;
        }
        // 插入提交后查询成功只允许一次自然清空焦点；任何额外切换/修订/取消均使版本条件失效。
        boolean current="lookup".equals(lookup.projection().get("taskKind"))&&"paused".equals(target.lifecycle())&&target.pendingCommand()==null
            &&target.version()==AgentTaskFingerprint.version(hint.get("pausedTaskVersion"))&&session.currentTaskId()==null
            &&session.focusVersion()==AgentTaskFingerprint.version(hint.get("insertFocusVersion"))+1;
        if(!current){origin.finishReturn("invalidated");origin.details().put("returnReason","target_or_focus_changed");return null;}
        // 原命令终态不需先锁command；先让控制服务按session→task→run→turn→command预备，再保存返回引用。
        var command=controls.prepare(scope.userId(),scope.sessionId(),Map.of("requestId","return-"+origin.id(),"action","resume",
            "targetTaskId",targetId,"expectedTaskVersion",target.version(),"expectedFocusVersion",session.focusVersion()));
        commands.lockById(origin.id()).orElseThrow(AgentTaskRegistryService::missing).prepareReturn((String)command.get("commandId"));
        return (String)command.get("commandId");
    }
    /** 仅在恢复控制真实committed后消费原返回指令；重试保持同一命令，不执行任何业务节点。 */
    @Transactional public void finish(String originId,String returnId,AgentTaskRegistryService.Scope scope) {
        registry.lockScope(scope.userId(),scope.sessionId());var command=commands.findById(returnId).orElseThrow(AgentTaskRegistryService::missing);
        var locked=commands.lockById(originId).orElseThrow(AgentTaskRegistryService::missing);
        if(!locked.scope().equals(scope)||!command.scope().equals(scope))throw AgentTaskRegistryService.missing();
        if(returnId.equals(locked.returnCommandId())&&"committed".equals(command.state()))locked.finishReturn("completed");
    }
}
