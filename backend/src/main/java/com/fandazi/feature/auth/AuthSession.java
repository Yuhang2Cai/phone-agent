/* 长期登录会话持久化模型：只存秘密摘要与时间，不存密码、完整JWT或刷新凭据。 */
package com.fandazi.feature.auth;

import jakarta.persistence.*;
import java.time.Instant;

/** 一行代表一次明确登录；轮换共享此身份，退出和重用检测撤销整行。 */
@Entity
@Table(name="auth_sessions")
public class AuthSession {
    /** 服务端随机会话身份，既是查询键也是JWT的sid，不具备单独认证能力。 */
    @Id @Column(length=36) String id;
    /** 本人用户主键；鉴权还需同时匹配JWT用户和仍存在的账号。 */
    @Column(nullable=false) Long userId;
    /** 当前与上一轮刷新凭据摘要，只允许当前凭据续期。 */
    @Column(nullable=false,length=64) String tokenHash;
    @Column(length=64) String previousTokenHash;
    /** 上一轮请求标识摘要允许精确幂等恢复，不能用新的标识借旧凭据续期。 */
    @Column(length=64) String previousRequestHash;
    /** 空闲到期可随成功轮换移动，绝对到期固定，不因使用刷新无限延长。 */
    @Column(nullable=false) Instant idleExpiresAt;
    @Column(nullable=false) Instant absoluteExpiresAt;
    /** 非空即撤销；事务不能因对外返回401回滚这一安全事实。 */
    Instant revokedAt;
    /** JPA只供框架构造，应用通过服务端认证服务设置所有必要字段。 */
    protected AuthSession() {}
}
