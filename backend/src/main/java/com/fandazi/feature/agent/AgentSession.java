package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import jakarta.persistence.*;

@Entity @Table(name = "agent_sessions", uniqueConstraints = @UniqueConstraint(columnNames = "session_id"))
public class AgentSession {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private UserEntity user;
    @Column(name = "session_id", nullable = false, length = 100) private String sessionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AgentMode mode;
    @Column(nullable = false, length = 100) private String traceId;
    @Column(nullable = false) private boolean confirmed;
    protected AgentSession() {}
    AgentSession(UserEntity user, String sessionId, AgentMode mode, String traceId) {
        this.user = user; this.sessionId = sessionId; this.mode = mode; this.traceId = traceId;
    }
    public Long getId() { return id; } public UserEntity getUser() { return user; }
    public String getSessionId() { return sessionId; } public AgentMode getMode() { return mode; }
    public String getTraceId() { return traceId; } public boolean isConfirmed() { return confirmed; }
    void update(AgentMode value, String newTraceId) { mode = value; traceId = newTraceId; }
    void confirm() { confirmed = true; }
}
