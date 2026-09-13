package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.auth.UserRepository;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentSessionService {
    private final AgentSessionRepository sessions; private final UserRepository users;
    public AgentSessionService(AgentSessionRepository sessions, UserRepository users) { this.sessions = sessions; this.users = users; }
    @Transactional public AgentSession start(Long userId, String sessionId, AgentMode mode, String traceId) {
        if (sessionId == null || sessionId.isBlank()) throw new DomainException("会话不能为空");
        var existing = sessions.findBySessionIdForUpdate(sessionId);
        if (existing.isPresent()) {
            if (!existing.get().getUser().getId().equals(userId)) throw new DomainException("会话不属于当前用户");
            existing.get().update(mode, traceId); return existing.get();
        }
        return sessions.save(new AgentSession(users.findById(userId).orElseThrow(() -> new DomainException("用户不存在")), sessionId, mode, traceId));
    }
    @Transactional(readOnly = true) public AgentSession owned(Long userId, String sessionId) {
        return sessions.findBySessionIdAndUserId(sessionId, userId).orElseThrow(() -> new DomainException("会话不存在"));
    }
    @Transactional(readOnly = true) public AgentSession trusted(String sessionId) {
        return sessions.findBySessionId(sessionId).orElseThrow(() -> new DomainException("会话不存在"));
    }
    @Transactional public AgentSession trustedForUpdate(String sessionId) {
        return sessions.findBySessionIdForUpdate(sessionId).orElseThrow(() -> new DomainException("会话不存在"));
    }
    @Transactional public void confirm(Long userId, String sessionId) {
        sessions.findBySessionIdAndUserIdForUpdate(sessionId, userId).orElseThrow(() -> new DomainException("会话不存在")).confirm();
    }
    @Transactional public void bindAuthorizedCart(Long userId, String sessionId, String workflowId, Long cartDraftId, Long addressId, BigDecimal total) {
        sessions.findBySessionIdAndUserIdForUpdate(sessionId, userId).orElseThrow(() -> new DomainException("会话不存在")).bindAuthorizedCart(workflowId, cartDraftId, addressId, total);
    }
    @Transactional public void bindAuthorizedCart(AgentSession session, String workflowId, Long cartDraftId, Long addressId, BigDecimal total) {
        session.bindAuthorizedCart(workflowId, cartDraftId, addressId, total);
    }
    @Transactional public void bindWorkflow(AgentSession session, String workflowId) { session.bindWorkflow(workflowId); }
    @Transactional public void bindCreatedOrder(AgentSession session, Long orderId, String workflowId) { session.bindCreatedOrder(orderId, workflowId); }
    @Transactional public void consumeConfirmation(AgentSession session) { session.consumeConfirmation(); }
    @Transactional public void settleCreatedOrder(AgentSession session) { session.settleCreatedOrder(); }
}
