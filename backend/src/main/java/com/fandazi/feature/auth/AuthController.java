// 身份认证模块：提供接口并将请求交给业务层。
package com.fandazi.feature.auth;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AuthController {
    private final AuthService authService;
    private final AuthSessionService sessions;

    /** 注入密码认证与刷新会话服务；认证副作用由其事务完成，本层只映射HTTP状态和禁止缓存。 */
    public AuthController(AuthService authService,AuthSessionService sessions) {
        this.authService = authService;
        this.sessions=sessions;
    }

    /** 校验注册字段，返回201完整凭据包并创建账号/会话；冲突沿原异常映射，响应不得缓存。 */
    @PostMapping("/auth/register")
    public ResponseEntity<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        // 所有包含认证秘密的响应均禁止中间缓存，注册与登录不能遗漏此边界。
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(org.springframework.http.CacheControl.noStore()).body(authService.register(request));
    }

    /** 校验账号密码，返回200完整凭据包并新建sid；错误沿原语义映射，不在本层保存或记录密码。 */
    @PostMapping("/auth/login")
    public ResponseEntity<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(authService.login(request));
    }

    /** 无需旧访问JWT，凭刷新秘密和持久请求标识续期；响应禁止中间缓存。 */
    @PostMapping("/auth/refresh")
    public ResponseEntity<AuthDtos.AuthResponse> refresh(@Valid @RequestBody AuthDtos.RefreshRequest request) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(sessions.refresh(request));
    }
    /** 已过期JWT也能退出本会话，重复或未知秘密不暴露存在性。 */
    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody AuthDtos.LogoutRequest request) {
        sessions.revoke(request.refreshToken());return ResponseEntity.noContent().build();
    }
    /** 只有明确刷新拒绝映射401；数据库/网络故障保留5xx，客户端不能因此丢掉登录态。 */
    @ExceptionHandler(RefreshRejectedException.class)
    public ResponseEntity<Map<String,String>> refreshRejected(RefreshRejectedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(org.springframework.http.CacheControl.noStore())
            .body(Map.of("code","SESSION_INVALID","error",exception.getMessage()));
    }

// 处理 `me` 相关逻辑。
    @GetMapping("/users/me")
    public AuthDtos.UserResponse me(@AuthenticationPrincipal Long userId) {
        return authService.currentUser(userId);
    }

// 处理 `invalidCredentials` 相关逻辑。
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<Map<String, String>> invalidCredentials(InvalidCredentialsException exception) {
        return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
    }

// 处理 `authConflict` 相关逻辑。
    @ExceptionHandler(AuthConflictException.class)
    public ResponseEntity<Map<String, String>> authConflict(AuthConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", exception.getMessage()));
    }
}
