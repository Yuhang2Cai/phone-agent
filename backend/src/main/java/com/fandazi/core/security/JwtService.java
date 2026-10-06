// 安全认证模块：集中处理业务规则与状态变更。
package com.fandazi.core.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final SecretKey secretKey;
    private final long expirationMinutes;

// 初始化对象依赖与默认状态。
    public JwtService(@Value("${security.jwt.secret}") String secret, @Value("${security.jwt.expiration-minutes:60}") long expirationMinutes) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMinutes = expirationMinutes;
    }

// 创建令牌。
    public String createToken(Long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expirationMinutes, ChronoUnit.MINUTES)))
                .signWith(secretKey)
                .compact();
    }

    /** 为长期会话签发短期JWT；sid只绑定已验证的服务端会话，不延长其绝对寿命。 */
    public String createSessionToken(Long userId,String sessionId) {
        Instant now=Instant.now();
        return Jwts.builder().subject(userId.toString()).claim("sid",sessionId).issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(expirationMinutes,ChronoUnit.MINUTES))).signWith(secretKey).compact();
    }
    /** 返回客户端发包前续期判断使用的毫秒截止时间，不暴露签名密钥。 */
    public long accessExpiresAtEpochMs() {return Instant.now().plus(expirationMinutes,ChronoUnit.MINUTES).toEpochMilli();}
    /** 只有通过签名及自然到期校验的JWT才能提取sid；历史无sid返回空，维持原短期兼容。 */
    public String parseSessionId(String token) {return Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload().get("sid",String.class);}

// 解析用户、id。
    public Long parseUserId(String token) {
        Claims claims = Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();
        return Long.parseLong(claims.getSubject());
    }
}
