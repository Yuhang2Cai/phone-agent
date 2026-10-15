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
    // 固化受理时的任务协议版本；完成补交必须使用原许可版本，不能读取任务当前值后静默升级。
    @Column(name="protocol_version",nullable=false,length=10,updatable=false) private String protocolVersion;
    @Column(nullable=false,length=30) private String state;
    @Column(name="may_execute_tools",nullable=false) private boolean mayExecuteTools;
    @Convert(converter=StringMapJsonConverter.class) @Column(name="fence_json",columnDefinition="TEXT") private Map<String,Object> fence;
    @Convert(converter=StringMapJsonConverter.class) @Column(name="result_json",columnDefinition="TEXT") private Map<String,Object> result;
    @Column(name="result_fingerprint",length=64) private String resultFingerprint;
    // 网关完整原始输入摘要绑定按钮/展示确认；只看业务子句不能发现同requestId附加了另一控制指令。
    @Column(name="gateway_input_hash",length=64) private String gatewayInputHash;
    // 保存首次受理的原文业务子句，响应切断重试不把完整插问/返回后缀送回原run。
    @Column(name="gateway_query",columnDefinition="TEXT") private String gatewayQuery;
    @Convert(converter=StringMapJsonConverter.class) @Column(name="gateway_context_json",columnDefinition="TEXT") private Map<String,Object> gatewayContext;
    @Column(name="lease_task_key",length=100,unique=true) private String leaseTaskKey;
    @Column(name="lease_until") private Instant leaseUntil;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="completed_at") private Instant completedAt;
    /** 仅供JPA恢复持久许可行。 */
    protected AgentTaskTurnEntity() {}
    /** 受理身份由Spring分配；有工具轮次才占单任务租约，无任务短回复的task/run为空。 */
    AgentTaskTurnEntity(AgentTaskRegistryService.Scope scope,String request,String hash,String source,String task,String run,String trace,String protocol,boolean executable) {
        if(!Set.of("2.2","2.3").contains(protocol))throw AgentTaskRegistryService.bad("轮次协议版本无效");
        id=UUID.randomUUID().toString();userId=scope.userId();sessionId=scope.sessionId();epoch=scope.epoch();requestId=request;
        fingerprint=hash;sourceId=source;taskId=task;runId=run;traceId=trace;protocolVersion=protocol;mayExecuteTools=executable;state="admitted";createdAt=Instant.now();
        if(executable){leaseTaskKey=task;leaseUntil=createdAt.plusSeconds(90);}
    }
    /** 返回普通轮次身份；它不是生命周期commandId。 */
    public String id() {return id;}
    /** 返回受理时固化的协议版本；完成补交必须逐字匹配，不能以当前任务版本替换。 */
    String protocolVersion() {return protocolVersion;}
    /** 返回原始输入链路；重试不能用新trace替换首次受理身份。 */
    public String traceId() {return traceId;}
    /** 返回精确归属，以拒绝跨账号/epoch完成补交。 */
    AgentTaskRegistryService.Scope scope() {return new AgentTaskRegistryService.Scope(userId,sessionId,epoch);}
    /** 返回请求指纹，来源相同不等于允许改写正文或目标。 */
    String fingerprint() {return fingerprint;}
    /** 返回受理前归属解释关联ID；只用于内部权威回读，客户端不能用此字段改写业务许可。 */
    String requestId() {return requestId;}
    /** 回读完整网关输入摘要，HTTP重试先核同文再返回完成回执。 */
    String gatewayInputHash() {return gatewayInputHash;}
    /** 返回首次业务子句，仅本人内部重试使用，不进入任务公开目录。 */
    String gatewayQuery() {return gatewayQuery;}
    /** 只返回首次核验的显式公开引用，重试不能通过另一个页面更换对象。 */
    Map<String,Object> gatewayContext(){return gatewayContext==null?Map.of():gatewayContext;}
    /** 与原始输入同事务绑定，公开引用也必须同文；调用方已排除所有凭证/任意客户端候选。 */
    void bindGatewayContext(Map<String,Object> value){
        if(gatewayContext!=null&&!gatewayContext.isEmpty()&&!AgentTaskFingerprint.of(gatewayContext).equals(AgentTaskFingerprint.of(value)))
            throw AgentTaskRegistryService.conflict("同输入选择证据已变化");
        gatewayContext=new LinkedHashMap<>(value);
    }
    /** 受理事务一次绑定原始输入，禁止同轮次改写按钮/确认对象。 */
    void bindGatewayInput(String hash,String body) {
        if(gatewayInputHash!=null&&!gatewayInputHash.equals(hash))throw AgentTaskRegistryService.conflict("同输入身份内容已变化");
        if(gatewayQuery!=null&&!gatewayQuery.equals(body))throw AgentTaskRegistryService.conflict("同输入业务原文已变化");
        gatewayInputHash=hash;gatewayQuery=body;
    }
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
        var out=new LinkedHashMap<String,Object>();out.put("fence",fence);out.put("sourceMessageId",sourceId);out.put("protocolVersion",protocolVersion);
        out.put("admissionState","completed".equals(state)?"completed":"admitted".equals(state)?"admitted":"rejected");
        out.put("mayExecuteTools",mayExecuteTools);out.put("commandId",null);return out;
    }
}
