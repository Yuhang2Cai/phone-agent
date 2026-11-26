// 团购券模块：生成短时、签名的二维码展示令牌，不暴露数据库中的固定字段。
package com.fandazi.feature.voucher;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 负责团购券二维码展示令牌的签发和校验。 */
@Service
public class VoucherTokenService {
    private final SecretKey key;
    private final long ttlMinutes;

    /** 使用与服务认证同等级别的配置密钥，并限制展示令牌有效期。 */
    public VoucherTokenService(@Value("${security.jwt.secret}") String secret,
                               @Value("${voucher.display-token-minutes:5}") long ttlMinutes) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttlMinutes = ttlMinutes;
    }

    /**
     * 签发二维码展示令牌。
     *
     * @param voucher 团购券实体
     * @return 含券、门店、签发时间和过期时间的签名令牌
     */
    public String issue(VoucherEntity voucher) {
        // 签名本身不证明付款；所有旧展示入口签发前先核秒杀券的真实支付及使用日期资格。
        if(voucher.getOrder().getSeckillActivity()!=null&&!voucher.canUseAt(com.fandazi.core.time.BusinessTime.now()))
            throw new com.fandazi.feature.seckill.SeckillRejectedException("FORBIDDEN","当前秒杀券尚不可使用");
        Instant now = Instant.now();
        return Jwts.builder().subject(voucher.getPublicId())
                .claim("storeId", voucher.getDeal().getStore().getPublicId())
                .claim("type", "voucher-display")
                .issuedAt(Date.from(now)).expiration(Date.from(now.plus(ttlMinutes, ChronoUnit.MINUTES)))
                .signWith(key).compact();
    }

    /** 校验核销端提交的展示令牌，并返回其中的团购券公开标识。 */
    public String parseVoucherPublicId(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
    }
}
