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
    private final AuthSessionService sessions;

    /** 仅兼容历史手工单元构造：接收账号仓库、密码编码器和JWT服务，返回旧短期响应，不参与生产装配。 */
    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this(users,passwordEncoder,jwtService,null);
    }

    /** 生产装配必须使用刷新会话服务；三参数入口只保留历史手工单元构造兼容。 */
    @org.springframework.beans.factory.annotation.Autowired
    public AuthService(UserRepository users,PasswordEncoder passwordEncoder,JwtService jwtService,AuthSessionService sessions) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.sessions=sessions;
    }

    /** 接收已验证注册字段，在同一事务保存账号与完整会话；唯一冲突转业务异常，其余数据库异常继续抛出。 */
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
                    request.username(), email, passwordEncoder.encode(request.password()), request.nickname()));
            return responseFor(user);
        } catch (DataIntegrityViolationException exception) {
            if (isUsernameOrEmailUniqueConstraint(exception)) {
                throw new AuthConflictException("Username or email is already in use");
            }
            throw exception;
        }
    }

    /** 核对请求账号和密码摘要，成功创建可撤销会话；账号不存在或密码不符抛认证异常，不保存密码明文。 */
    @Transactional
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

    /** 根据已核对账号构造认证响应；生产创建刷新会话，历史手工测试只使用旧JWT，不生成虚假刷新资格。 */
    private AuthDtos.AuthResponse responseFor(UserEntity user) {
        // 真实生产路径在账号认证事务内创建可撤销会话；旧手工测试构造不获得伪造刷新凭据。
        if(sessions!=null)return sessions.issue(user);
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
