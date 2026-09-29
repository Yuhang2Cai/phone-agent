// 客服支持模块：保存用户投诉及其关联订单或团购券。
package com.fandazi.feature.support;

import com.fandazi.feature.auth.UserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/** 团购投诉工单。 */
@Entity
@Table(name = "complaint_tickets")
public class ComplaintTicketEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "public_id", nullable = false, unique = true, length = 80) private String publicId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
    @Column(name = "order_id") private Long orderId;
    @Column(name = "voucher_id") private Long voucherId;
    @Column(nullable = false) private String subject;
    @Column(nullable = false, length = 2000) private String description;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 40) private ComplaintStatus status;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;

    protected ComplaintTicketEntity() {}

    /** 创建一条待处理投诉工单。 */
    public ComplaintTicketEntity(String publicId, UserEntity user, Long orderId, Long voucherId,
                                 String subject, String description) {
        this.publicId = publicId; this.user = user; this.orderId = orderId; this.voucherId = voucherId;
        this.subject = subject; this.description = description; this.status = ComplaintStatus.OPEN;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public UserEntity getUser() { return user; }
    public Long getOrderId() { return orderId; }
    public Long getVoucherId() { return voucherId; }
    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public ComplaintStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
