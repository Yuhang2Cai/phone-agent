// 公开任务事件事务模块：投影与水位原子写入；投递租约/回执使用短事务，消费者调用不持锁。
package com.fandazi.feature.agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** 事件采用至少一次投递；客户端按taskEventId去重，失败只重投事件不重执任务。 */
@Service
public class AgentTaskEventOutbox {
    private final AgentTaskEventRepository events;private final AgentSessionRepository sessions;
    private final AgentTaskRegistryService registry;private final AgentProtocolValidator contracts;
    /** 注入同库仓储及受控结构；公开事件永不从任意检查点Map直接复制。 */
    public AgentTaskEventOutbox(AgentTaskEventRepository events,AgentSessionRepository sessions,AgentTaskRegistryService registry,ObjectMapper mapper) {
        this.events=events;this.sessions=sessions;this.registry=registry;contracts=new AgentProtocolValidator(mapper);
    }
    /** 必须加入调用方持有session锁的业务/控制事务；写失败同时回滚版本/投影。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public void append(AgentSession session,String kind,Map<String,Object> payload) {
        var scope=registry.scopeOf(session);long sequence=session.nextTaskEvent();
        var event=Map.<String,Object>of("schemaVersion","2.2","taskEventId",sequence,"scope",AgentTaskFingerprint.scope(scope),
                "focusVersion",session.focusVersion(),"eventType",kind,"payload",payload);
        contracts.validateTaskPayload("taskEvent",event);events.saveAndFlush(new AgentTaskEventEntity(scope,sequence,event));
    }
    /** 同scope只读补读；缺口/过期/未来水位明确拒绝，客户端据此重新读取目录。 */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public List<Map<String,Object>> read(Long user,String sessionId,long after,int limit) {
        if(after<0||limit<1||limit>100)throw AgentTaskRegistryService.bad("事件游标或数量无效");
        var session=sessions.findBySessionIdAndUserId(sessionId,user).orElseThrow(AgentTaskRegistryService::missing);var scope=registry.scopeOf(session);
        if(after>session.taskEventSequence())throw AgentTaskRegistryService.conflict("事件游标超出当前目录水位");
        Long first=events.first(user,sessionId,scope.epoch());
        if((first!=null&&after<first-1)||(first==null&&after<session.taskEventSequence()))
            throw new ResponseStatusException(HttpStatus.GONE,"事件游标已过期，请重新读取任务目录");
        var result=events.after(user,sessionId,scope.epoch(),after,PageRequest.of(0,limit)).stream().map(AgentTaskEventEntity::event).toList();
        long next=after+1;for(var row:result) {if(((Number)row.get("taskEventId")).longValue()!=next++)throw AgentTaskRegistryService.conflict("事件水位存在缺口，请重读目录");}
        // 未填满本批仍落后目录水位，说明尾部被删/丢失；不能返回成功让客户端静默漏事件。
        if(result.size()<limit&&next-1<session.taskEventSequence())throw AgentTaskRegistryService.conflict("事件尾部存在缺口，请重读目录");
        return result;
    }
    /** 列出有界候选；返回身份不授予投递资格，后续仍须claim。 */
    @Transactional(readOnly=true)
    public List<Long> pending() {return events.findByDeliveryStateOrderByIdAsc("pending",PageRequest.of(0,50)).stream().map(AgentTaskEventEntity::id).toList();}
    /** 短事务取得投递租约；实际消费者不能在本事务内调用。 */
    @Transactional
    public Map<String,Object> claim(Long id,String owner,Instant now) {
        var row=events.lockById(id).orElseThrow();if(!row.claimable(now))return null;row.claim(owner,now);return row.event();
    }
    /** 只确认当前租约；崩溃/旧回执允许同ID重投但不推进任务版本。 */
    @Transactional
    public void finish(Long id,String owner,boolean success) {var row=events.lockById(id).orElseThrow();if(row.ownedLease(owner))row.finish(success);}
}
