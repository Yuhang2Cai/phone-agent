// 身份认证模块：集中处理业务规则与状态变更。
package com.fandazi.feature.auth;

import com.fandazi.core.security.JwtService;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

// 初始化对象依赖与默认状态。
    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

// 注册当前数据。
    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        // 当前产品只要求账号和密码即可注册；数据库仍保留历史 email 字段，因此没有填写时使用
        // 与账号一一对应的内部占位值，既不要求验证码，也不会把虚构邮箱展示为用户输入。
        String email = request.email() == null || request.email().isBlank()
                ? request.username().trim() + "@local.fandazi"
                : request.email().trim();
        if (users.existsByUsername(request.username())) {
            throw new InvalidCredentialsException("Username is already in use");
        }
        if (users.existsByEmail(email)) {
            throw new InvalidCredentialsException("Email is already in use");
        }
        try {
            UserEntity user = users.saveAndFlush(new UserEntity(
                    request.username(), email, passwordEncoder.encode(request.password())));
            return responseFor(user);
        } catch (DataIntegrityViolationException exception) {
            if (isUsernameOrEmailUniqueConstraint(exception)) {
                throw new AuthConflictException("Username or email is already in use");
            }
            throw exception;
        }
    }

// 登录当前数据。
    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        UserEntity user = users.findByUsername(request.username())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }
        return responseFor(user);
    }

// 处理 `currentUser` 相关逻辑。
    @Transactional(readOnly = true)
    public AuthDtos.UserResponse currentUser(Long userId) {
        return users.findById(userId)
                .map(AuthDtos.UserResponse::from)
                .orElseThrow(() -> new InvalidCredentialsException("User no longer exists"));
    }

// 处理 `responseFor` 相关逻辑。
    private AuthDtos.AuthResponse responseFor(UserEntity user) {
        return new AuthDtos.AuthResponse(jwtService.createToken(user.getId()), AuthDtos.UserResponse.from(user));
    }

// 判断username、email、unique、constraint。
    private boolean isUsernameOrEmailUniqueConstraint(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            boolean namesAuthField = normalized.contains("username") || normalized.contains("email");
            boolean signalsUniqueViolation = normalized.contains("unique")
                    || normalized.contains("duplicate key")
                    || normalized.contains("duplicate entry");
            if (namesAuthField && signalsUniqueViolation) {
                return true;
            }
        }
        return false;
    }
}
