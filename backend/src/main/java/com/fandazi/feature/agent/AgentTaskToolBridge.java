// v2任务工具桥：同库校验栅栏/确认/幂等后复用唯一17工具领域分发，拒绝所有无栅栏降级。
package com.fandazi.feature.agent;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** session/task/turn锁与领域写、grant消费、执行记录共用一个事务；绝不跨HTTP持锁。 */
@Service
public class AgentTaskToolBridge {
    private final AgentTaskFenceService fences;private final AgentV1ToolBridge domain;private final AgentToolExecutionRepository executions;
    private final AgentToolSchemaValidator schema;private final ConfirmationGrantService grants;private final AgentTaskExecutionContext context;
    private final AgentGoalService goals;
    /** 唯一领域分派来自既有桥的dispatch，不复制17工具switch或绕回v1 execute。 */
    public AgentTaskToolBridge(AgentTaskFenceService fences,AgentV1ToolBridge domain,AgentToolExecutionRepository executions,
            AgentToolSchemaValidator schema,ConfirmationGrantService grants,AgentTaskExecutionContext context,AgentGoalService goals){
        this.fences=fences;this.domain=domain;this.executions=executions;this.schema=schema;this.grants=grants;this.context=context;this.goals=goals;
    }
    /** 2.1成功响应同时返回事务结束时的权威目标版本；业务数据仍按原十七工具输出校验。 */
    public record GoalToolResult(Map<String,Object> data,Map<String,Object> goalContext) {}
    /** 兼容既有组件调用，只返回原业务数据；目标协议HTTP入口使用带版本结果的方法。 */
    @Transactional
    public Map<String,Object> execute(String tool,InternalV2ToolRequest request) {
        return executeTracked(tool,request).data();
    }
    /** 在同一工具事务内取得业务结果和最终目标版本，避免业务已提交后再用旧effectVersion复核。 */
    @Transactional
    public GoalToolResult executeWithGoalContext(String tool,InternalV2ToolRequest request) {
        var result=executeTracked(tool,request);
        if(result.goalContext()==null)throw new ToolBoundaryException("INTERNAL_ERROR","目标工具结果缺少权威版本");
        return result;
    }
    /** 输入归属→持久栅栏→键/指纹→确认→唯一领域→出口校验→原子执行记录。 */
    private GoalToolResult executeTracked(String tool,InternalV2ToolRequest request) {
        @SuppressWarnings("unchecked") var scope=(Map<String,Object>)request.taskFence().get("scope");
        if(!request.userId().equals(scope.get("userId"))||!request.sessionId().equals(scope.get("sessionId"))||!request.runId().equals(request.taskFence().get("runId")))
            throw new ToolBoundaryException("FORBIDDEN","工具信封与任务身份不一致");
        var current=fences.requireCurrent(request.taskFence(),request.traceId());
        boolean goalProtocol="2.1".equals(request.schemaVersion());
        if(goalProtocol)fences.requireCurrentGoal(current,request.goalFence());
        else if(request.goalFence()!=null)throw new ToolBoundaryException("VALIDATION_ERROR","旧工具请求不能携带目标围栏");
        String expected=request.runId()+":"+request.taskId()+":"+request.actionVersion();
        if(!expected.equals(request.idempotencyKey())||expected.length()>255)throw new ToolBoundaryException("VALIDATION_ERROR","执行器动作键不匹配");
        var key=Map.<String,Object>of("tool",tool,"user",request.userId(),"session",request.sessionId(),"run",request.runId(),"dagTask",request.taskId(),
            "actionVersion",request.actionVersion(),"payload",request.payload(),"conversationTaskId",current.task().id(),"generation",current.task().generation());
        String hash=AgentTaskFingerprint.of(key);var existing=executions.findById(request.idempotencyKey());
        // 栅栏先于缓存；旧代次即使原键有结果也不能继续取得授权，须只读receipt核查原事实。
        if(existing.isPresent()){if(!hash.equals(existing.get().getFingerprint()))throw new ToolBoundaryException("CONFLICT","动作键关联不同请求或代次");
            return new GoalToolResult(existing.get().getResult(),goalProtocol?current.task().goalContext():null);}
        if(schema.isPreparation(tool)&&executions.countBySessionIdAndToolNameInAndCreatedAtAfter(request.sessionId(),
            List.of("create_purchase_quote","prepare_refund","prepare_complaint","internal_reprepare_payment"),Instant.now().minusSeconds(60))>=30)
            throw new ToolBoundaryException("RATE_LIMITED","确认准备过于频繁，请稍后重试");
        var data=context.within(request.taskFence(),()->{
            if(schema.isWrite(tool)) {
                if(!current.session().taskTurnConfirmed(current.turn().id()))throw new ToolBoundaryException("FORBIDDEN","业务写需要本轮明确确认");
                grants.requireTaskGrant(current.turn().scope().userId(),request.sessionId(),(String)request.payload().get("confirmationGrantId"));
                domain.validatePending(tool,current.turn().scope().userId(),request.domainRequest(),current.session());
            }
            var result=domain.dispatch(tool,current.turn().scope().userId(),request.domainRequest());
            result=AgentDomainProjection.times(AgentContractCompatibility.toolData(result));schema.validateData(tool,result);
            // 原支付资源只来自真实创建或本人订单读取；模型后续不能用任意orderId替换恢复对象。
            if("transaction".equals(current.task().kind())) {
                if("create_group_buy_order".equals(tool))current.task().bindPaymentOrder((String)result.get("orderId"));
                if("get_order_detail".equals(tool)&&result.get("order") instanceof Map<?,?> order&&"PENDING_PAYMENT".equals(order.get("status")))
                    current.task().bindPaymentOrder((String)order.get("orderId"));
            }
            // 非法领域输出连同订单/凭证消费全部回滚，不能返回失败却留下已执行业务。
            if(schema.isWrite(tool))current.session().consumeConfirmation();
            if(goalProtocol&&schema.isWrite(tool)) {
                // 只保存允许公开的真实资源摘要；效果版本、领域写和执行回执处于同一外层事务。
                var safe=new LinkedHashMap<String,Object>();
                if(result.get("orderId") instanceof String value)safe.put("orderId",value);
                if(result.get("requestId") instanceof String value)safe.put("refundRequestId",value);
                if(result.get("ticketId") instanceof String value)safe.put("complaintTicketId",value);
                if(result.get("status") instanceof String value)safe.put("status",value);
                goals.commitBusinessEffect(current.turn().scope().userId(),request.sessionId(),current.task().id(),request.idempotencyKey(),
                        AgentTaskFingerprint.version(request.goalFence().get("goalRevision")),
                        AgentTaskFingerprint.version(request.goalFence().get("planRevision")),
                        AgentTaskFingerprint.version(request.goalFence().get("effectVersion")),safe);
            }
            executions.saveAndFlush(new AgentToolExecution(request,current.turn().scope().userId(),tool,hash,result));return result;
        });
        // 写效果已在上面的同一事务内推进；此处读取托管实体即可得到与业务结果同提交点的版本。
        return new GoalToolResult(data,goalProtocol?current.task().goalContext():null);
    }
}
