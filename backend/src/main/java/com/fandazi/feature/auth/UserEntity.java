// 身份认证模块：定义持久化数据结构。
package com.fandazi.feature.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "app_users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    /** 用户可选昵称，属于后端身份资料；旧行为空，不替换唯一登录账号。 */
    @Column(length = 40)
    private String nickname;

// 初始化对象依赖与默认状态。
    protected UserEntity() {
    }

// 初始化对象依赖与默认状态。
    public UserEntity(String username, String email, String passwordHash) {
        this(username, email, passwordHash, null);
    }

    /** 接收注册请求中的可选昵称，去首尾空白，空内容按未填写保存，不伪造默认昵称。 */
    public UserEntity(String username, String email, String passwordHash, String nickname) {
        this.nickname = nickname == null || nickname.isBlank() ? null : nickname.trim();
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
    }

// 获取id。
    public Long getId() { return id; }
// 获取username。
    public String getUsername() { return username; }
// 获取email。
    public String getEmail() { return email; }
// 获取password、hash。
    public String getPasswordHash() { return passwordHash; }

    /** 返回真实保存的可选昵称，缺失时由展示层回退账号名。 */
    public String getNickname() { return nickname; }
}
