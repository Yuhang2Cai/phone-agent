// 身份认证模块：定义该模块的数据与行为。
package com.fandazi.feature.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
// 初始化对象依赖与默认状态。
    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 64) String username,
            @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password) {
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    public record UserResponse(Long id, String username, String email) {
// 处理 `from` 相关逻辑。
        static UserResponse from(UserEntity user) {
            return new UserResponse(user.getId(), user.getUsername(), user.getEmail());
        }
    }

    public record AuthResponse(String token, UserResponse user) {
    }
}
