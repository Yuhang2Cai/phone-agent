// 安全认证模块：定义该模块的数据与行为。
package com.fandazi.core.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final com.fandazi.feature.auth.AuthSessionService sessions;

    /** 注入JWT验签与会话核对服务；只有签名有效且sid仍归属当前账号的身份才能进入安全上下文。 */
    public JwtAuthenticationFilter(JwtService jwtService,com.fandazi.feature.auth.AuthSessionService sessions) {
        this.jwtService = jwtService;
        this.sessions=sessions;
    }

    /** 从请求Bearer头提取账号并核对sid；认证拒绝清上下文后继续原安全链，数据库故障不伪装为认证成功。 */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Long userId = jwtService.parseUserId(header.substring(7));
                // 新JWT必须同时匹配本人仍有效的会话；退出/刷新重用后的旧JWT不能继续访问业务。
                String sessionId=jwtService.parseSessionId(header.substring(7));
                if(sessionId!=null&&!sessions.accepts(sessionId,userId))throw new IllegalArgumentException("登录会话已失效");
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(userId, null, List.of()));
            } catch (JwtException | IllegalArgumentException exception) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);
    }
}
