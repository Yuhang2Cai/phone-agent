package com.fandazi.feature.automation;

import com.fandazi.feature.auth.UserEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name="automation_grants", uniqueConstraints=@UniqueConstraint(columnNames={"user_id","session_id"}))
public class AutomationGrant {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private UserEntity user;
    @Column(name="session_id",nullable=false,length=100) private String sessionId;
    @Column(nullable=false) private Long addressId;
    @Column(nullable=false,precision=12,scale=2) private BigDecimal maxAmount;
    @Column(nullable=false) private Instant expiresAt;
    protected AutomationGrant() {}
    AutomationGrant(UserEntity user,String sessionId,Long addressId,BigDecimal maxAmount,Instant expiresAt){this.user=user;this.sessionId=sessionId;this.addressId=addressId;this.maxAmount=maxAmount;this.expiresAt=expiresAt;}
    public Long getAddressId(){return addressId;} public BigDecimal getMaxAmount(){return maxAmount;} public boolean isActiveAt(Instant now){return expiresAt.isAfter(now);} void update(Long address,BigDecimal amount,Instant expires){addressId=address;maxAmount=amount;expiresAt=expires;}
}
