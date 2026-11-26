// 内部鉴权模块：校验共享密钥签名的短时 HS256 JWT，静态开发凭证不能访问 V1 工具。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 只接受 Bearer JWT；签名验证后要求 sub 与请求身份完全一致，并限制剩余有效期不超过 120 秒。 */
@Component
class InternalV1JwtValidator {
    private final byte[] secret;
    private final ObjectMapper mapper;
    /** 密钥只来自环境配置；未配置时拒绝调用，不启用默认凭据。 */
    InternalV1JwtValidator(@Value("${agent.internal-token:}") String secret, ObjectMapper mapper) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8); this.mapper = mapper;
    }
    /** 校验协议、签名、期限和用户绑定；非法编码等异常统一转换为安全的 FORBIDDEN。 */
    void validate(String header, String userId) {
        try {
            // 输入校验：限制令牌大小和分段数量，先校验签名再信任声明。
            if (secret.length < 32 || header == null || !header.startsWith("Bearer ") || header.length() > 4096) throw denied();
            String[] parts = header.substring(7).split("\\.", -1);
            if (parts.length != 3) throw denied();
            var decoder = Base64.getUrlDecoder();
            JsonNode metadata = mapper.readTree(decoder.decode(parts[0]));
            if (!"HS256".equals(metadata.path("alg").asText()) || metadata.has("crit")) throw denied();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            if (!MessageDigest.isEqual(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)), decoder.decode(parts[2]))) throw denied();
            // 规则判断：exp 必须是整数；允许网络延迟造成剩余时间缩短，但不接受长期凭证或尚未生效凭证。
            JsonNode claims = mapper.readTree(decoder.decode(parts[1]));
            long now = Instant.now().getEpochSecond();
            if (!claims.path("sub").isTextual() || !userId.equals(claims.path("sub").asText())
                    || !claims.path("exp").isIntegralNumber() || claims.path("exp").asLong() <= now
                    || claims.path("exp").asLong() > now + 120
                    || (claims.has("nbf") && (!claims.path("nbf").isIntegralNumber() || claims.path("nbf").asLong() > now))
                    || (claims.has("iat") && (!claims.path("iat").isIntegralNumber() || claims.path("iat").asLong() > now))) throw denied();
        } catch (Exception exception) { throw denied(); }
    }
    /** 所有鉴权失败使用同一公开消息，避免泄漏密钥配置或用户是否存在。 */
    private ToolBoundaryException denied() { return new ToolBoundaryException("FORBIDDEN", "内部调用凭证无效或已过期"); }
}
