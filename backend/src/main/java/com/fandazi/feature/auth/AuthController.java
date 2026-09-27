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

// 初始化对象依赖与默认状态。
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

// 注册当前数据。
    @PostMapping("/auth/register")
    public ResponseEntity<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

// 登录当前数据。
    @PostMapping("/auth/login")
    public AuthDtos.AuthResponse login(@Valid @RequestBody AuthDtos.LoginRequest request) {
        return authService.login(request);
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
