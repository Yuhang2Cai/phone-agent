// Agent 安全模块：保存一次性购买、支付、退款和投诉确认凭证。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 一次性确认凭证。
 *
 * <p>凭证绑定用户、会话、动作和资源，后续写工具必须校验未过期且未消费，
 * 防止模型重复提交或篡改金额。</p>
 */
@Entity
@Table(name = "confirmation_grants")
public class ConfirmationGrantEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "grant_id", nullable = false, unique = true, length = 80) private String grantId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
    @Column(name = "session_id", nullable = false, length = 100) private String sessionId;
    @Column(nullable = false, length = 80) private String action;
    @Column(name = "resource_id", nullable = false, length = 80) private String resourceId;
    // Java 保留整数分接口，数据库权威列使用 DECIMAL 元，显式转换防止误改其他整数。
    @jakarta.persistence.Convert(converter = com.fandazi.core.money.CentsDecimalConverter.class)
    @Column(name = "amount", precision = 12, scale = 2) private Integer amountCents;
    @Column(name = "rule_version", nullable = false, length = 40) private String ruleVersion;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(nullable = false) private boolean consumed;

    protected ConfirmationGrantEntity() {}

    /** 创建未消费的确认凭证。 */
    public ConfirmationGrantEntity(String grantId, UserEntity user, String sessionId, String action,
                                   String resourceId, Integer amountCents, String ruleVersion, Instant expiresAt) {
        this.grantId = grantId; this.user = user; this.sessionId = sessionId; this.action = action;
        this.resourceId = resourceId; this.amountCents = amountCents; this.ruleVersion = ruleVersion;
        this.expiresAt = expiresAt; this.consumed = false;
    }

    public Long getId() { return id; }
    public String getGrantId() { return grantId; }
    public UserEntity getUser() { return user; }
    public String getSessionId() { return sessionId; }
    public String getAction() { return action; }
    public String getResourceId() { return resourceId; }
    public Integer getAmountCents() { return amountCents; }
    public String getRuleVersion() { return ruleVersion; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isConsumed() { return consumed; }

    /** 将凭证标记为已消费，防止同一确认结果被重复用于写操作。 */
    public void consume() { this.consumed = true; }
}
