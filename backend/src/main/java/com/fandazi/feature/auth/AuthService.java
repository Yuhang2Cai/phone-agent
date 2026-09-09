package com.fandazi.feature.auth;

import com.fandazi.core.security.JwtService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest request) {
        if (users.existsByUsername(request.username())) {
            throw new InvalidCredentialsException("Username is already in use");
        }
        if (users.existsByEmail(request.email())) {
            throw new InvalidCredentialsException("Email is already in use");
        }
        UserEntity user = users.save(new UserEntity(request.username(), request.email(), passwordEncoder.encode(request.password())));
        return responseFor(user);
    }

    @Transactional(readOnly = true)
    public AuthDtos.AuthResponse login(AuthDtos.LoginRequest request) {
        UserEntity user = users.findByUsername(request.username())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid username or password"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid username or password");
        }
        return responseFor(user);
    }

    @Transactional(readOnly = true)
    public AuthDtos.UserResponse currentUser(Long userId) {
        return users.findById(userId)
                .map(AuthDtos.UserResponse::from)
                .orElseThrow(() -> new InvalidCredentialsException("User no longer exists"));
    }

    private AuthDtos.AuthResponse responseFor(UserEntity user) {
        return new AuthDtos.AuthResponse(jwtService.createToken(user.getId()), AuthDtos.UserResponse.from(user));
    }
}
