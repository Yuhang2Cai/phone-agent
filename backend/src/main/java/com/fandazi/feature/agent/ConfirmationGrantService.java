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

    /** 注入凭证和用户仓储。 */
    public ConfirmationGrantService(ConfirmationGrantRepository grants, UserRepository users) {
        this.grants = grants;
        this.users = users;
    }

    /** 为报价创建短期确认凭证。 */
    @Transactional
    public ConfirmationGrantEntity issue(Long userId, String sessionId, String resourceId, int amountCents, String ruleVersion) {
        return issueForAction(userId, sessionId, "CREATE_GROUP_BUY_ORDER", resourceId, amountCents, ruleVersion);
    }

    /** 为指定写动作签发独立凭证；资源与动作一起绑定，避免跨工具复用。 */
    @Transactional
    public ConfirmationGrantEntity issueForAction(Long userId, String sessionId, String action, String resourceId, int amountCents, String ruleVersion) {
        if (action == null || action.isBlank() || resourceId == null || resourceId.isBlank()) throw new DomainException("确认资源不完整");
        var user = users.findById(userId).orElseThrow(() -> new DomainException("用户不存在"));
        return grants.save(new ConfirmationGrantEntity(UUID.randomUUID().toString(), user, sessionId,
                action, resourceId, amountCents, ruleVersion, Instant.now().plusSeconds(300)));
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
        return grants.findByUserIdAndSessionIdAndActionAndResourceIdOrderByIdDesc(userId, sessionId, action, resourceId).stream()
                .filter(value -> !value.isConsumed() && value.getExpiresAt().isAfter(Instant.now())
                        && Integer.valueOf(amount).equals(value.getAmountCents()) && rule.equals(value.getRuleVersion()))
                .findFirst().orElseThrow(() -> new ToolBoundaryException("EXPIRED", "该动作没有有效确认准备，请重新准备"));
    }
}
