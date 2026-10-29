// Agent 安全模块：签发并消费绑定用户、资源和版本的一次性确认凭证。
package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.auth.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 确认凭证服务。
 * 凭证只允许使用一次，并且消费时再次校验用户、会话、资源和规则版本，避免客户端或模型篡改报价。
 */
@Service
public class ConfirmationGrantService {
    private final ConfirmationGrantRepository grants;
    private final UserRepository users;
    private final AgentTaskExecutionContext taskContext;

    /** 注入凭证和用户仓储。 */
    public ConfirmationGrantService(ConfirmationGrantRepository grants, UserRepository users,AgentTaskExecutionContext taskContext) {
        this.grants = grants;
        this.users = users;
        this.taskContext=taskContext;
    }

    /** 为报价创建短期确认凭证。 */
    @Transactional
    public ConfirmationGrantEntity issue(Long userId, String sessionId, String resourceId, int amountCents, String ruleVersion) {
        return issueForAction(userId, sessionId, "CREATE_GROUP_BUY_ORDER", resourceId, amountCents, ruleVersion);
    }

    /** 为指定写动作签发独立凭证；资源与动作一起绑定，避免跨工具复用。 */
    @Transactional
    public ConfirmationGrantEntity issueForAction(Long userId, String sessionId, String action, String resourceId, int amountCents, String ruleVersion) {
        return issueForActionUntil(userId,sessionId,action,resourceId,amountCents,ruleVersion,Instant.now().plusSeconds(300));
    }

    /**
     * 按领域已计算的截止签发授权；截止只来自可信业务调用，不接受客户端或模型传值。
     * 用于秒杀报价结束和订单支付期限，避免授权摘要晚于实际可执行时间；签发不消费或提交业务。
     */
    @Transactional
    public ConfirmationGrantEntity issueForActionUntil(Long userId,String sessionId,String action,String resourceId,
            int amountCents,String ruleVersion,Instant expiresAt){
        if (action == null || action.isBlank() || resourceId == null || resourceId.isBlank()) throw new DomainException("确认资源不完整");
        if(expiresAt==null)throw new DomainException("确认截止不能为空");
        var user = users.findById(userId).orElseThrow(() -> new DomainException("用户不存在"));
        var grant=new ConfirmationGrantEntity(UUID.randomUUID().toString(), user, sessionId,
                action, resourceId, amountCents, ruleVersion, expiresAt);
        // 领域报价/退款/投诉/独立支付授权共享本路径，不能漏掉某个工具的新授权绑定。
        var fence=taskContext.current();if(fence!=null){requireContextOwner(userId,sessionId,fence);grant.bindTask((String)fence.get("conversationTaskId"),AgentTaskFingerprint.version(fence.get("executionGeneration")));}
        return grants.save(grant);
    }

    /** 创建指定过期时间的凭证，供边界测试和服务内部复用。 */
    @Transactional
    public String issueForTest(Long userId, String sessionId, String resourceId, int amountCents, String ruleVersion, Instant expiresAt) {
        var user = users.findById(userId).orElseThrow(() -> new DomainException("用户不存在"));
        var entity = grants.save(new ConfirmationGrantEntity(UUID.randomUUID().toString(), user, sessionId,
                "CREATE_GROUP_BUY_ORDER", resourceId, amountCents, ruleVersion, expiresAt));
        return entity.getGrantId();
    }

    /** 校验并消费一次性凭证。 */
    @Transactional
    public ConfirmationGrantEntity consume(Long userId, String sessionId, String grantId, String resourceId, String ruleVersion) {
        return consumeForAction(userId, sessionId, grantId, "CREATE_GROUP_BUY_ORDER", resourceId, ruleVersion);
    }

    /** 消费时同时校验动作、资源和版本，不能用购买确认凭证执行退款或投诉。 */
    @Transactional
    public ConfirmationGrantEntity consumeForAction(Long userId, String sessionId, String grantId, String action, String resourceId, String ruleVersion) {
        var grant = grants.findForUpdate(grantId).orElseThrow(() -> new DomainException("确认凭证不存在"));
        requireTaskBinding(userId,sessionId,grant);
        if (!grant.getUser().getId().equals(userId) || !grant.getSessionId().equals(sessionId)) throw new DomainException("确认凭证不属于当前会话");
        if (grant.isConsumed()) throw new DomainException("确认凭证已使用");
        if (grant.getExpiresAt().isBefore(Instant.now())) throw new DomainException("确认凭证已过期");
        if (!grant.getAction().equals(action)) throw new DomainException("确认凭证动作不匹配");
        if (!grant.getResourceId().equals(resourceId) || !grant.getRuleVersion().equals(ruleVersion)) throw new DomainException("资源已变化，请重新确认");
        grant.consume();
        return grant;
    }

    /** V1 写操作严格校验真实金额后消费；金额不匹配不会吞掉凭证，整个业务事务随后回滚。 */
    @Transactional
    public ConfirmationGrantEntity consumeBound(Long userId, String sessionId, String grantId, String action,
                                                  String resourceId, int amountCents, String ruleVersion) {
        // 输入校验与资源绑定：金额也属于签发时的授权摘要，不能只比较资源和版本。
        var grant = grants.findForUpdate(grantId).orElseThrow(() -> new ToolBoundaryException("NOT_FOUND", "确认凭证不存在"));
        requireTaskBinding(userId,sessionId,grant);
        if (!grant.getUser().getId().equals(userId) || !grant.getSessionId().equals(sessionId))
            throw new ToolBoundaryException("FORBIDDEN", "确认凭证不属于当前会话");
        if (!grant.getExpiresAt().isAfter(Instant.now())) throw new ToolBoundaryException("EXPIRED", "确认凭证已过期，请重新准备");
        if (!action.equals(grant.getAction())) throw new ToolBoundaryException("CONFLICT", "确认凭证动作不匹配");
        if (grant.isConsumed() || !resourceId.equals(grant.getResourceId())
                || !ruleVersion.equals(grant.getRuleVersion()) || !Integer.valueOf(amountCents).equals(grant.getAmountCents()))
            throw new ToolBoundaryException("CONFLICT", "确认动作或业务事实已变化，请重新确认");
        grant.consume();
        return grant;
    }

    /** 读取当前会话凭证元数据供内部准备结果显示期限；不消费授权，也不向公共摘要返回凭证。 */
    @Transactional(readOnly = true)
    public ConfirmationGrantEntity owned(Long userId, String sessionId, String grantId) {
        return grants.findByGrantId(grantId).filter(value -> value.getUser().getId().equals(userId)
                && value.getSessionId().equals(sessionId)).orElseThrow(() -> new ToolBoundaryException("FORBIDDEN", "确认凭证不属于当前会话"));
    }

    /** 用已准备凭证校验公开摘要事实；期限来自签发记录，不能采用模型期限或券有效期。 */
    @Transactional(readOnly = true)
    public ConfirmationGrantEntity pending(Long userId, String sessionId, String action, String resourceId, int amount, String rule) {
        var fence=taskContext.current();if(fence!=null)requireContextOwner(userId,sessionId,fence);
        return grants.findByUserIdAndSessionIdAndActionAndResourceIdOrderByIdDesc(userId, sessionId, action, resourceId).stream()
                .filter(value -> !value.isConsumed() && value.getExpiresAt().isAfter(Instant.now())
                        && !value.isRevoked()
                        // 旧页面只可取旧grant；可信任务摘要只能取该task当前代次，不能跨任务借用同资源凭证。
                        && (fence==null ? value.getConversationTaskId()==null :
                            java.util.Objects.equals(value.getConversationTaskId(),fence.get("conversationTaskId"))
                            && java.util.Objects.equals(value.getExecutionGeneration(),AgentTaskFingerprint.version(fence.get("executionGeneration"))))
                        && Integer.valueOf(amount).equals(value.getAmountCents()) && rule.equals(value.getRuleVersion()))
                .findFirst().orElseThrow(() -> new ToolBoundaryException("EXPIRED", "该动作没有有效确认准备，请重新准备"));
    }
    /**
     * 用户点击确认时按服务端任务绑定重新核验待消费凭证。
     *
     * <p>公开确认摘要不含 grantId，因此先使用动作与资源缩小候选，再以当前任务、执行代次、
     * 金额和规则定位服务端最近一次授权。客户端摘要已经由网关与会话快照比较，但授权可能在
     * 展示后自然过期、被控制命令撤销或被并发请求消费，必须在把本轮标记为已确认前再次读取。</p>
     *
     * @param user 当前鉴权用户
     * @param session 当前会话
     * @param task 当前前台任务
     * @param generation 当前执行代次
     * @param summary 已与服务端展示快照匹配的公开摘要
     * @throws ToolBoundaryException 授权不存在、已撤销、已消费或已过期时拒绝确认
     */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    void requireConfirmableTaskGrant(Long user,String session,String task,long generation,java.util.Map<String,Object> summary) {
        // 输入已来自服务端会话快照，但这里仍限定必需字段类型，防止历史脏数据绕过授权查询。
        if(!(summary.get("action") instanceof String action)||action.isBlank()
                ||!(summary.get("resourceId") instanceof String resource)||resource.isBlank()
                ||!(summary.get("amountCents") instanceof Number amount)
                ||!(summary.get("ruleVersion") instanceof String rule)||rule.isBlank())
            throw new ToolBoundaryException("CONFLICT","确认摘要不完整，请重新准备");
        String domainAction=action.toUpperCase(java.util.Locale.ROOT);
        // 同资源可能存在历史重准备记录；只接受当前任务当前代次、金额与规则完全一致的最新授权。
        var grant=grants.findByUserIdAndSessionIdAndActionAndResourceIdOrderByIdDesc(user,session,domainAction,resource).stream()
                .filter(value->java.util.Objects.equals(task,value.getConversationTaskId())
                        &&java.util.Objects.equals(generation,value.getExecutionGeneration())
                        &&Integer.valueOf(amount.intValue()).equals(value.getAmountCents())
                        &&rule.equals(value.getRuleVersion()))
                .findFirst().orElseThrow(()->new ToolBoundaryException("CONFLICT","确认授权已变化，请重新准备"));
        // 到期使用“必须晚于当前时刻”的闭区间边界；等于当前时刻也不能获得执行许可。
        if(!grant.getExpiresAt().isAfter(Instant.now()))throw new ToolBoundaryException("EXPIRED","确认凭证已过期，请重新准备");
        if(grant.isConsumed()||grant.isRevoked())throw new ToolBoundaryException("CONFLICT","确认授权已失效，请重新准备");
    }
    /** 核验线程可信上下文归属；模型无法通过业务payload提供另一账号或会话。 */
    private void requireContextOwner(Long user,String session,java.util.Map<String,Object> fence) {
        @SuppressWarnings("unchecked") var scope=(java.util.Map<String,Object>)fence.get("scope");
        if(!String.valueOf(user).equals(scope.get("userId"))||!session.equals(scope.get("sessionId")))throw new ToolBoundaryException("FORBIDDEN","任务凭证归属不一致");
    }
    /** 所有领域消费入口共用门禁：任务凭证无可信上下文拒绝，新任务也不能借旧凭证。 */
    private void requireTaskBinding(Long user,String session,ConfirmationGrantEntity grant) {
        var fence=taskContext.current();
        if(grant.isRevoked())throw new ToolBoundaryException("CONFLICT","确认已撤销，请重新准备");
        if(fence==null){if(grant.getConversationTaskId()!=null)throw new ToolBoundaryException("FORBIDDEN","任务确认不能通过旧业务入口消费");return;}
        requireContextOwner(user,session,fence);
        if(!java.util.Objects.equals(grant.getConversationTaskId(),fence.get("conversationTaskId"))||!java.util.Objects.equals(grant.getExecutionGeneration(),AgentTaskFingerprint.version(fence.get("executionGeneration"))))
            throw new ToolBoundaryException("CONFLICT","确认不属于当前任务代次");
    }
    /** 写工具在领域执行前锁住并核验凭证；金额/动作/资源仍由既有唯一领域路径再次验证。 */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    ConfirmationGrantEntity requireTaskGrant(Long user,String session,String grantId) {
        var grant=grants.findForUpdate(grantId).orElseThrow(()->new ToolBoundaryException("NOT_FOUND","确认凭证不存在"));requireTaskBinding(user,session,grant);
        if(!grant.getUser().getId().equals(user)||!grant.getSessionId().equals(session))throw new ToolBoundaryException("FORBIDDEN","确认凭证不属于当前会话");return grant;
    }
    /** 控制预备只撤销明确受影响task的未消费授权；后台取消不能误伤当前前台。 */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    void revokeTasks(Long user,String session,java.util.Collection<String> tasks) {
        if(tasks.isEmpty())return;for(var grant:grants.lockTaskGrants(user,session,tasks))grant.revoke();
    }
    /** 领域入口取得用户/订单/券锁之前先识别任务凭证，避免旧页面先锁订单再等grant造成锁序反转。 */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireDomainEntry(Long user,String session,String grantId){
        var grant=grants.findByGrantId(grantId).orElseThrow(()->new ToolBoundaryException("NOT_FOUND","确认凭证不存在"));
        if(!grant.getUser().getId().equals(user)||!grant.getSessionId().equals(session))throw new ToolBoundaryException("FORBIDDEN","确认凭证不属于当前会话");
        // 此处只读不可变绑定；真正消费仍在领域资格核验后锁grant并再次校验，避免TOCTOU。
        requireTaskBinding(user,session,grant);
    }
}
