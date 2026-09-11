package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.auth.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentSessionService {
    private final AgentSessionRepository sessions; private final UserRepository users;
    public AgentSessionService(AgentSessionRepository sessions, UserRepository users) { this.sessions = sessions; this.users = users; }
    @Transactional public AgentSession start(Long userId, String sessionId, AgentMode mode, String traceId) {
        if (sessionId == null || sessionId.isBlank()) throw new DomainException("会话不能为空");
        var existing = sessions.findBySessionId(sessionId);
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
    @Transactional public void confirm(Long userId, String sessionId) { owned(userId, sessionId).confirm(); }
}
