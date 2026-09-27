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

// 初始化对象依赖与默认状态。
    protected UserEntity() {
    }

// 初始化对象依赖与默认状态。
    public UserEntity(String username, String email, String passwordHash) {
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
}
