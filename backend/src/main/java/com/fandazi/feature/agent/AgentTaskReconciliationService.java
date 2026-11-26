// 控制派发/重对账协调模块：短事务认领→事务外HTTP→短事务持久回执/提交，不持锁调用Agent。
package com.fandazi.feature.agent;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 六次/60秒后自动apply停止；后续显式核查只GET同命令，不能重新授予业务资格。 */
@Service
public class AgentTaskReconciliationService {
    private final AgentTaskControlService controls;private final AgentTaskControlClient client;
    private final AgentTaskCommandRepository commands;private final boolean enabled;private final boolean automatic;
    /** 生产默认功能关闭；组件测试可显式启用控制并关闭自动轮询以冻结故障时序。 */
    public AgentTaskReconciliationService(AgentTaskControlService controls,AgentTaskControlClient client,AgentTaskCommandRepository commands,
            @Value("${agent.task-lifecycle.enabled:false}") boolean enabled,
            @Value("${agent.task-lifecycle.reconcile-enabled:true}") boolean automatic) {
        this.controls=controls;this.client=client;this.commands=commands;this.enabled=enabled;this.automatic=automatic;
    }
    /** 单次派发只发送同一个持久命令；失败保留prepared/aborting，不返回假完成。 */
    public Map<String,Object> process(String id,Instant now) {
        var scope=controls.scope(id);var current=controls.read(scope.userId(),scope.sessionId(),id);
        if("agent_acknowledged".equals(current.get("state")))return controls.commit(scope.userId(),scope.sessionId(),id);
        String owner=UUID.randomUUID().toString();var request=controls.claim(scope.userId(),scope.sessionId(),id,owner,now);
        if(request==null)return controls.read(scope.userId(),scope.sessionId(),id);
        try {
            var response=controls.acknowledge(scope.userId(),scope.sessionId(),id,client.dispatch(request));
            return "agent_acknowledged".equals(response.get("state"))?controls.commit(scope.userId(),scope.sessionId(),id):response;
        } catch(Exception error) {
            controls.dispatchFailed(scope.userId(),scope.sessionId(),id,owner,now);return controls.read(scope.userId(),scope.sessionId(),id);
        }
    }
    /** 用户/运维显式重查只读取同命令事实；未知/不可用不表示未执行，不重发apply。 */
    public Map<String,Object> recheck(Long user,String sessionId,String id) {
        return recheck(user,sessionId,id,true);
    }
    /** 周期核查遵守退避与租约；显式核查只越过退避，不重置派发计数。 */
    private Map<String,Object> recheck(Long user,String sessionId,String id,boolean explicit) {
        controls.read(user,sessionId,id);var scope=controls.scope(id);String owner=UUID.randomUUID().toString();
        if(!controls.claimLookup(user,sessionId,id,owner,Instant.now(),explicit))return controls.read(user,sessionId,id);
        try {
            var ack=client.lookup(id,scope);
            if(ack!=null) {
                var state=controls.acknowledge(user,sessionId,id,ack);
                if("agent_acknowledged".equals(state.get("state")))return controls.commit(user,sessionId,id);
            }
        } catch(Exception error) {
            // 只读核查失败保留原状态/原因，不能以新命令或新来源掩盖原命令。
        } finally {controls.finishLookup(user,sessionId,id,owner,Instant.now());}
        return controls.read(user,sessionId,id);
    }
    /** 周期仅扫描有界持久候选；默认关闭时无查询/派发，重启后仍使用原commandId。 */
    @Scheduled(fixedDelay=1000)
    public void tick() {
        if(!enabled||!automatic)return;
        for(var command:commands.pending(PageRequest.of(0,50))) {
            if("needs_attention".equals(command.reconcileStatus()))recheck(command.scope().userId(),command.scope().sessionId(),command.id(),false);
            else process(command.id(),Instant.now());
        }
    }
}
