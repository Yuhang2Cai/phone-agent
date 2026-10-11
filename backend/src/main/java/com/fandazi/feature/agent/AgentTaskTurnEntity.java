// 普通轮次持久模块：输入指纹、单执行租约、不可变许可与完成回执；不把所有输入当控制命令。
package com.fandazi.feature.agent;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

/** 无工具短回复只持久来源/回执，不借用前台资格或修改前台trace/确认。 */
@Entity @Table(name="agent_task_turns")
public class AgentTaskTurnEntity {
    @Id @Column(name="turn_id",length=100) private String id;
    @Column(name="user_id",nullable=false,updatable=false) private Long userId;
    @Column(name="session_id",nullable=false,length=100,updatable=false) private String sessionId;
    @Column(nullable=false,length=100,updatable=false) private String epoch;
    @Column(name="request_id",nullable=false,length=100,updatable=false) private String requestId;
    @Column(nullable=false,length=64,updatable=false) private String fingerprint;
    @Column(name="source_message_id",nullable=false,length=100,updatable=false) private String sourceId;
    @Column(name="conversation_task_id",length=100,updatable=false) private String taskId;
    @Column(name="run_id",length=100,updatable=false) private String runId;
    @Column(name="trace_id",nullable=false,length=100,updatable=false) private String traceId;
    @Column(nullable=false,length=30) private String state;
    @Column(name="may_execute_tools",nullable=false) private boolean mayExecuteTools;
    @Convert(converter=StringMapJsonConverter.class) @Column(name="fence_json",columnDefinition="TEXT") private Map<String,Object> fence;
    @Convert(converter=StringMapJsonConverter.class) @Column(name="result_json",columnDefinition="TEXT") private Map<String,Object> result;
    @Column(name="result_fingerprint",length=64) private String resultFingerprint;
    @Column(name="lease_task_key",length=100,unique=true) private String leaseTaskKey;
    @Column(name="lease_until") private Instant leaseUntil;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="completed_at") private Instant completedAt;
    /** 仅供JPA恢复持久许可行。 */
    protected AgentTaskTurnEntity() {}
    /** 受理身份由Spring分配；有工具轮次才占单任务租约，无任务短回复的task/run为空。 */
    AgentTaskTurnEntity(AgentTaskRegistryService.Scope scope,String request,String hash,String source,String task,String run,String trace,boolean executable) {
        id=UUID.randomUUID().toString();userId=scope.userId();sessionId=scope.sessionId();epoch=scope.epoch();requestId=request;
        fingerprint=hash;sourceId=source;taskId=task;runId=run;traceId=trace;mayExecuteTools=executable;state="admitted";createdAt=Instant.now();
        if(executable){leaseTaskKey=task;leaseUntil=createdAt.plusSeconds(90);}
    }
    /** 返回普通轮次身份；它不是生命周期commandId。 */
    public String id() {return id;}
    /** 返回原始输入链路；重试不能用新trace替换首次受理身份。 */
    public String traceId() {return traceId;}
    /** 返回精确归属，以拒绝跨账号/epoch完成补交。 */
    AgentTaskRegistryService.Scope scope() {return new AgentTaskRegistryService.Scope(userId,sessionId,epoch);}
    /** 返回请求指纹，来源相同不等于允许改写正文或目标。 */
    String fingerprint() {return fingerprint;}
    /** 返回可信来源，完成不能使用另一用户消息。 */
    String source() {return sourceId;}
    /** 返回所属任务；无状态短回复允许为空且不能签发工具许可。 */
    String taskId() {return taskId;}
    /** 返回所属执行run；终态run不得借新turn重开。 */
    String runId() {return runId;}
    /** 返回受理/完成/被栅栏隔离状态，不从租约过期推测未执行。 */
    String state() {return state;}
    /** 返回是否允许发业务工具，控制回复始终为false。 */
    boolean executable() {return mayExecuteTools;}
    /** 返回不可变执行栅栏；公开目录与模型决策不得获得它。 */
    Map<String,Object> fence() {return fence==null||fence.isEmpty()?null:fence;}
    /** 仅首次受理在保存前绑定完整栅栏，不能在后续重试中换代。 */
    void bindFence(Map<String,Object> value) {fence=new LinkedHashMap<>(value);}
    /** 保存首次完成回执并释放租约；同输入重试只能回读，不重执业务。 */
    void complete(Map<String,Object> value,String hash) {
        result=new LinkedHashMap<>(value);resultFingerprint=hash;state="completed";mayExecuteTools=false;
        leaseTaskKey=null;leaseUntil=null;completedAt=Instant.now();
    }
    /** 控制预备使旧turn不可执行；租约仍保留，直到安全停点/退出证据核实后释放。 */
    void fenceOff() {state="fenced";mayExecuteTools=false;}
    /** 安全交接后释放旧turn占位；不会恢复旧许可或重放旧写入。 */
    void release() {leaseTaskKey=null;leaseUntil=null;}
    /** 读取首次完成数据，HTTP丢失时补交相同结果而不是重新执行工作流。 */
    Map<String,Object> result() {return "completed".equals(state)?result:null;}
    /** 核对补交结果不可被同turn异文改写。 */
    String resultFingerprint() {return resultFingerprint;}
    /** 无工具/无任务轮次不签业务许可；有任务轮次结构仍由权威Schema校验。 */
    Map<String,Object> admission() {
        // 既有JSON转换器将SQL NULL还原为空Map；语义仍是没有许可，不能误签空栅栏。
        if(fence()==null)throw AgentTaskRegistryService.conflict("无业务轮次不签发执行许可");
        var out=new LinkedHashMap<String,Object>();out.put("fence",fence);out.put("sourceMessageId",sourceId);out.put("protocolVersion","2.2");
        out.put("admissionState","completed".equals(state)?"completed":"admitted".equals(state)?"admitted":"rejected");
        out.put("mayExecuteTools",mayExecuteTools);out.put("commandId",null);return out;
    }
}
