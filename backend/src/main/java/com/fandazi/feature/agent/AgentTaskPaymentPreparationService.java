// 原订单支付重准备模块：核验原任务停点/原订单/当前资格，只签新授权，不创建订单或支付。
package com.fandazi.feature.agent;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fandazi.feature.grouporder.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 内部管理能力不进入17业务工具列表；幂等结果与新grant同事务提交，失败均回滚。 */
@Service
public class AgentTaskPaymentPreparationService {
    private final AgentTaskFenceService fences;private final AgentTaskTurnRepository turns;private final GroupBuyOrderRepository orders;
    private final ConfirmationGrantService grants;private final AgentTaskExecutionContext context;private final AgentToolExecutionRepository executions;
    private final AgentProtocolValidator contracts;
    private final com.fandazi.feature.seckill.SeckillTimeSource databaseTime;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    /** 注入原订单只读/锁仓储及授权服务，刻意不注入创建订单或支付领域方法。 */
    public AgentTaskPaymentPreparationService(AgentTaskFenceService fences,AgentTaskTurnRepository turns,GroupBuyOrderRepository orders,
            ConfirmationGrantService grants,AgentTaskExecutionContext context,AgentToolExecutionRepository executions,ObjectMapper mapper,
            com.fandazi.feature.seckill.SeckillTimeSource databaseTime){
        this.fences=fences;this.turns=turns;this.orders=orders;this.grants=grants;this.context=context;this.executions=executions;contracts=new AgentProtocolValidator(mapper);this.databaseTime=databaseTime;
    }
    /** 校验结构→当前许可/原停点→原键去重→订单资格→新grant/安全摘要→原子回执。 */
    @Transactional
    public Map<String,Object> reprepare(Map<String,Object> raw){
        try{contracts.validateTaskPayload("taskPaymentPreparationRequest",raw);}catch(AgentServiceUnavailableException invalid){throw new ToolBoundaryException("VALIDATION_ERROR","支付重准备结构无效");}
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)raw.get("fence");
        var turn=turns.findById((String)fence.get("turnId")).orElseThrow(()->new ToolBoundaryException("FORBIDDEN","支付轮次不存在"));
        String protocol=(String)raw.get("schemaVersion");
        if(!turn.protocolVersion().equals(protocol))throw new ToolBoundaryException("CONFLICT","支付重准备协议与原轮次不一致");
        var current=fences.requireCurrent(fence,turn.traceId());String orderId=(String)raw.get("orderId");
        if(!"transaction".equals(current.task().kind())||!"waiting_confirmation".equals(current.task().stopKind())||!orderId.equals(current.task().pendingPaymentOrderId()))
            throw new ToolBoundaryException("CONFLICT","支付重准备必须绑定原订单确认停点");
        int version=Math.toIntExact(AgentTaskFingerprint.version(raw.get("actionVersion")));String node="internal-payment-"+orderId;
        String key=current.run().id()+":"+node+":"+version;if(key.length()>255)throw new ToolBoundaryException("VALIDATION_ERROR","支付恢复身份过长");
        var request=new InternalV2ToolRequest("2.0",String.valueOf(current.turn().scope().userId()),current.turn().scope().sessionId(),turn.traceId(),current.run().id(),node,key,version,
            Map.of("requestId",raw.get("requestId"),"orderId",orderId),fence);
        String hash=AgentTaskFingerprint.of(raw);var old=executions.findById(key);
        if(old.isPresent()){if(!hash.equals(old.get().getFingerprint()))throw new ToolBoundaryException("CONFLICT","支付准备键关联不同请求或代次");return old.get().getResult();}
        if(executions.countBySessionIdAndToolNameInAndCreatedAtAfter(current.turn().scope().sessionId(),List.of("create_purchase_quote","prepare_refund","prepare_complaint","internal_reprepare_payment"),Instant.now().minusSeconds(60))>=30)
            throw new ToolBoundaryException("RATE_LIMITED","确认准备过于频繁，请稍后重试");
        // 订单行锁核验提交时资格；查不到/过期/已支付不能靠延长grant复活原业务。
        var order=orders.findOwnedForUpdate(current.turn().scope().userId(),orderId).orElseThrow(()->new ToolBoundaryException("FORBIDDEN","原订单不可用"));
        // MySQL可重复读下必须锁定刷新托管实例，避免沿旧快照签发已被支付/关闭订单的授权。
        em.refresh(order,jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        boolean payable=order.getSeckillActivity()==null
                ? order.getExpiresAt().isAfter(com.fandazi.core.time.BusinessTime.now())
                : order.getPaymentExpiresAt()!=null && order.getPaymentExpiresAt().isAfter(databaseTime.now());
        if(order.getStatus()!=GroupBuyOrderStatus.PENDING_PAYMENT||!payable)
            throw new ToolBoundaryException("CONFLICT","原订单已支付、关闭或过期");
        return context.within(fence,()->{
            // 业务资格核验通过后才撤销本任务旧授权；错误对象和过期订单不能破坏仍有效的原停点。
            grants.revokeTasks(current.turn().scope().userId(),current.turn().scope().sessionId(),Set.of(current.task().id()));
            current.session().clearTaskConfirmation(Set.of(current.task().id()));
            // 重准备只替换授权，不延长原预留。普通订单继续采用原授权期限规则。
            var grant=order.getSeckillActivity()==null
                    ? grants.issueForAction(current.turn().scope().userId(),current.turn().scope().sessionId(),"PAY_MOCK_ORDER",orderId,order.getTotalCents(),"pay-v1")
                    : grants.issueForActionUntil(current.turn().scope().userId(),current.turn().scope().sessionId(),"PAY_MOCK_ORDER",orderId,order.getTotalCents(),"pay-v1",order.getPaymentExpiresAt());
            var summary=Map.<String,Object>of("action","pay_mock_order","resourceId",orderId,"title","确认原订单支付","amountCents",order.getTotalCents(),"ruleVersion","pay-v1","expiresAt",grant.getExpiresAt().toString());
            var privateGrant=Map.<String,Object>of("confirmationGrantId",grant.getGrantId(),"userId",request.userId(),"sessionId",request.sessionId(),"action","pay_mock_order","resourceId",orderId,
                "amountCents",order.getTotalCents(),"ruleVersion","pay-v1","expiresAt",grant.getExpiresAt().toString(),"consumed",false,
                "conversationTaskId",current.task().id());
            var bound=new LinkedHashMap<>(privateGrant);bound.put("executionGeneration",current.task().generation());
            var result=Map.<String,Object>of("schemaVersion",protocol,"requestId",raw.get("requestId"),"summary",summary,"grant",bound);
            contracts.validateTaskPayload("taskPaymentPreparationResult",result);
            executions.saveAndFlush(new AgentToolExecution(request,current.turn().scope().userId(),"internal_reprepare_payment",hash,result));return result;
        });
    }
}
