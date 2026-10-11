// 安全认证模块：定义该模块的数据与行为。
package com.fandazi.core.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfiguration {
// 处理 `passwordEncoder` 相关逻辑。
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

// 处理 `securityFilterChain` 相关逻辑。
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
                                             ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // 初始REQUEST仍按下方JWT及接口策略鉴权；ERROR/ASYNC仅为容器的二次分发。
                        // SSE断连后的ASYNC不应因原请求线程已清空身份再次拒绝并产生大批错误日志；
                        // 客户端不能通过HTTP参数设置DispatcherType，普通私有入口和直接/error仍受保护。
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR,jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        // 旧接口即使残留在历史代码也永远拒绝；不能凭用户JWT或静态内部令牌重新启用。
                        .requestMatchers("/internal/tools/**", "/api/addresses/**", "/api/cart/**",
                                "/api/carts/**", "/api/orders/**", "/api/automation/**").denyAll()
                        // V1 入口由专用短时内部 JWT 校验器保护，不能由用户 JWT 过滤器代替其身份绑定。
                        .requestMatchers("/internal/v1/tools/**").permitAll()
                        // v2同样在专用内部JWT入口核验签名/账号，随后校验数据库任务栅栏。
                        .requestMatchers("/internal/v2/tools/**", "/internal/v2/task-executions/**", "/internal/v2/task-admissions/**", "/internal/v2/task-payments/**").permitAll()
                        // 版本化餐图是公开目录静态资源；仅开放读取，数据库与私有接口仍需各自授权。
                        .requestMatchers(HttpMethod.GET, "/catalog-images/**").permitAll()
                        // 图片审计使用无正文的 HEAD 请求；只读权限与 GET 相同。
                        .requestMatchers(HttpMethod.HEAD, "/catalog-images/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/merchants/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/stores/**", "/api/deals/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint((request, response, exception) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    objectMapper.writeValue(response.getOutputStream(), Map.of("error", "Authentication is required"));
                }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
