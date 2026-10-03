// 身份认证模块：定义该模块的数据与行为。
package com.fandazi.feature.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {
// 初始化对象依赖与默认状态。
    private AuthDtos() {
    }

    /** 注册只要求账号密码；昵称由用户可选提供，限制40字符与持久化列一致。 */
    public record RegisterRequest(
            @NotBlank @Size(max = 64) String username,
            @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @Size(max = 40) String nickname) {
        /** 兼容现有三字段调用，未传昵称不能引入额外注册条件。 */
        public RegisterRequest(String username, String email, String password) {
            this(username, email, password, null);
        }
    }

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {
    }

    /** 昵称为服务端保存的可选事实；旧用户为空，客户端可回退账号名。 */
    public record UserResponse(Long id, String username, String email, String nickname) {
        /** 保持旧三字段调用兼容，禁止把账号名伪造成用户填写的昵称。 */
        public UserResponse(Long id, String username, String email) {
            this(id, username, email, null);
        }
// 处理 `from` 相关逻辑。
        static UserResponse from(UserEntity user) {
            return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getNickname());
        }
    }

    public record AuthResponse(String token, UserResponse user) {
    }
}
