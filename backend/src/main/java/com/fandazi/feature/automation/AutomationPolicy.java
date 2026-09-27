// 自动下单授权模块：集中定义判定规则。
package com.fandazi.feature.automation;

import com.fandazi.feature.auth.UserEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity @Table(name="automation_policies", uniqueConstraints=@UniqueConstraint(columnNames="user_id"))
public class AutomationPolicy {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional=false) private UserEntity user;
    @Column(nullable=false) private boolean enabled;
    @Column(nullable=false) private Long defaultAddressId;
    @Column(nullable=false,precision=12,scale=2) private BigDecimal maxAmount;
    protected AutomationPolicy() {}
    AutomationPolicy(UserEntity user,boolean enabled,Long addressId,BigDecimal maxAmount){this.user=user;this.enabled=enabled;this.defaultAddressId=addressId;this.maxAmount=maxAmount;}
    public boolean isEnabled(){return enabled;} public Long getDefaultAddressId(){return defaultAddressId;} public BigDecimal getMaxAmount(){return maxAmount;}
    void update(boolean value,Long address,BigDecimal amount){enabled=value;defaultAddressId=address;maxAmount=amount;}
}
