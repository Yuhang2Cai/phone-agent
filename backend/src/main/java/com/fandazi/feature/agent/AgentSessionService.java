// 助手会话服务：保存会话归属和上下文状态。
package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.auth.UserRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentSessionService {
    private final AgentSessionRepository sessions; private final UserRepository users;
// 初始化对象依赖与默认状态。
    public AgentSessionService(AgentSessionRepository sessions, UserRepository users) { this.sessions = sessions; this.users = users; }
    /** 创建2.2空目录会话；只供受控任务入口，绝不把旧活动会话静默迁移到新协议。 */
    @Transactional public AgentSession startTaskSession(Long userId,String sessionId,String traceId) {
        if(sessionId==null||sessionId.isBlank()||sessionId.length()>100)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"会话身份无效");
        var existing=sessions.findBySessionIdForUpdate(sessionId);
        if(existing.isPresent()) {
            var session=existing.get();
            // 越权与不存在使用相同404；旧活动run继续固定原协议，终态后才允许显式进入。
            if(!session.getUser().getId().equals(userId))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"会话不存在");
            if(!"2.2".equals(session.getContextProtocolVersion())) {
                String status=session.getActiveRunStatus();
                if(status!=null&&!java.util.Set.of("succeeded","cancelled","failed").contains(status))
                    throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"旧活动run必须按原协议完成");
                session.enterTaskProtocol(traceId);
            }
            return session;
        }
        var user=users.findById(userId).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND,"用户不存在"));
        var created=new AgentSession(user,sessionId,AgentMode.CONFIRM,traceId);
        created.enterTaskProtocol(traceId);
        return sessions.saveAndFlush(created);
    }
// 处理 `start` 相关逻辑。
    @Transactional public AgentSession start(Long userId, String sessionId, AgentMode mode, String traceId) {
        // 旧调用方保持 B2 版本；只有新受控入口显式要求 2.1。
        return start(userId,sessionId,mode,traceId,"2.0");
    }

    /** 原子建立任务及固定协议；等待中的旧任务沿用原版本直至结束。 */
    @Transactional public AgentSession start(Long userId,String sessionId,AgentMode mode,String traceId,String requestedVersion) {
        if(!java.util.Set.of("2.0","2.1").contains(requestedVersion))throw new DomainException("任务协议版本不受支持");
        if (sessionId == null || sessionId.isBlank()) throw new DomainException("会话不能为空");
        var existing = sessions.findBySessionIdForUpdate(sessionId);
        if (existing.isPresent()) {
            if (!existing.get().getUser().getId().equals(userId)) throw new DomainException("会话不属于当前用户");
            var session=existing.get();
            // 旧消息入口不能覆盖2.2目录/停点；新协议必须经任务轮次受理服务进入。
            if("2.2".equals(session.getContextProtocolVersion()))
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"请通过任务协议入口继续当前会话");
            boolean resume="waiting_input".equals(session.getActiveRunStatus()) || "waiting_confirmation".equals(session.getActiveRunStatus());
            if(!resume)session.bindContextProtocol(requestedVersion);
            session.update(mode, traceId); return session;
        }
        var created=new AgentSession(users.findById(userId).orElseThrow(() -> new DomainException("用户不存在")), sessionId, mode, traceId);
        created.bindContextProtocol(requestedVersion);
        return sessions.save(created);
    }
// 处理 `owned` 相关逻辑。
    @Transactional(readOnly = true) public AgentSession owned(Long userId, String sessionId) {
        return sessions.findBySessionIdAndUserId(sessionId, userId).orElseThrow(() -> new DomainException("会话不存在"));
    }
// 处理 `trusted` 相关逻辑。
    @Transactional(readOnly = true) public AgentSession trusted(String sessionId) {
        return sessions.findBySessionId(sessionId).orElseThrow(() -> new DomainException("会话不存在"));
    }
// 处理 `trustedForUpdate` 相关逻辑。
    @Transactional public AgentSession trustedForUpdate(String sessionId) {
        return sessions.findBySessionIdForUpdate(sessionId).orElseThrow(() -> new DomainException("会话不存在"));
    }
    /** 旧协议泛化确认只用于旧任务；2.2必须经明确task/turn/展示摘要校验，不能绕过新门禁。 */
    @Transactional public void confirm(Long userId, String sessionId) {
        var session=sessions.findBySessionIdAndUserIdForUpdate(sessionId, userId).orElseThrow(() -> new DomainException("会话不存在"));
        if("2.2".equals(session.getContextProtocolVersion()))throw new ToolBoundaryException("FORBIDDEN","任务协议必须确认当前轮次摘要");session.confirm();
    }
// 处理 `bindAuthorizedCart` 相关逻辑。
    @Transactional public void bindAuthorizedCart(Long userId, String sessionId, String workflowId, Long cartDraftId, Long addressId, BigDecimal total) {
        sessions.findBySessionIdAndUserIdForUpdate(sessionId, userId).orElseThrow(() -> new DomainException("会话不存在")).bindAuthorizedCart(workflowId, cartDraftId, addressId, total);
    }
// 处理 `bindAuthorizedCart` 相关逻辑。
    @Transactional public void bindAuthorizedCart(AgentSession session, String workflowId, Long cartDraftId, Long addressId, BigDecimal total) {
        session.bindAuthorizedCart(workflowId, cartDraftId, addressId, total);
    }
// 处理 `bindWorkflow` 相关逻辑。
    @Transactional public void bindWorkflow(AgentSession session, String workflowId) { session.bindWorkflow(workflowId); }
// 处理 `bindCreatedOrder` 相关逻辑。
    @Transactional public void bindCreatedOrder(AgentSession session, Long orderId, String workflowId) { session.bindCreatedOrder(orderId, workflowId); }
// 处理 `consumeConfirmation` 相关逻辑。
    @Transactional public void consumeConfirmation(AgentSession session) { session.consumeConfirmation(); }
// 处理 `settleCreatedOrder` 相关逻辑。
    @Transactional public void settleCreatedOrder(AgentSession session) { session.settleCreatedOrder(); }

    /** 保存暂停或终态；归属与行锁校验保证任务标识在跨输入和服务重启后可恢复。 */
    @Transactional public void finishRun(Long userId, String sessionId, String runId, AgentResult.Status status) {
        sessions.findBySessionIdAndUserIdForUpdate(sessionId, userId)
                .orElseThrow(() -> new DomainException("会话不存在")).finishRun(runId, status);
    }

    /** 把 Spring 已重查的待确认摘要绑定当前任务，下一次明确确认只能消费该动作和正文。 */
    @Transactional public void bindPendingConfirmation(Long userId, String sessionId, String runId, java.util.Map<String, Object> summary) {
        var session = sessions.findBySessionIdAndUserIdForUpdate(sessionId, userId).orElseThrow(() -> new DomainException("会话不存在"));
        if (!runId.equals(session.getActiveRunId())) throw new DomainException("任务已经变化");
        session.awaitConfirmation(summary);
    }
}
