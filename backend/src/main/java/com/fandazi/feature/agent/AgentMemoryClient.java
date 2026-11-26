package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Agent 记忆代理：Spring只签发本人短期身份，不复制长期记忆到业务数据库。 */
@Component
public class AgentMemoryClient {
    private final RestClient client;
    private final String secret;
    private final ObjectMapper mapper;

    /** 生产客户端复用服务地址与共享密钥；超时独立受限，不能降级到旧Agent。 */
    @Autowired
    public AgentMemoryClient(@Value("${agent.service.base-url:http://127.0.0.1:8091}") String url,
                             @Value("${agent.internal-token:}") String secret, ObjectMapper mapper) {
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(15));
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.secret = secret;
        this.mapper = mapper;
    }

    /** 测试显式注入HTTP客户端，不改变生产依赖或使用模拟记忆兜底。 */
    AgentMemoryClient(RestClient client, String secret, ObjectMapper mapper) {
        this.client = client;
        this.secret = secret;
        this.mapper = mapper;
    }

    /** 根据已认证principal调用固定管理路径；只透传可预期状态，不泄露内部异常正文。 */
    public ResponseEntity<String> request(Long userId, String method, String suffix, Object body) {
        if (userId == null) return ResponseEntity.status(401).body("{\"message\":\"请先登录\"}");
        try {
            var request = client.method(HttpMethod.valueOf(method)).uri("/internal/v1/agent/memories" + suffix)
                .header("Authorization", "Bearer " + token(userId)).contentType(MediaType.APPLICATION_JSON);
            if (body != null) request.body(body);
            return request.exchange((input, response) -> {
                int status = response.getStatusCode().value();
                // 主服务鉴权失败属于内部故障；不要把内部401冒充用户会话过期。
                if (status == 401 || status == 403 || status >= 500)
                    return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"AI记忆暂不可用\"}");
                return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .body(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
            });
        } catch (Exception error) {
            // 网络、签名、解析错误统一安全响应；不记录用户记忆和共享密钥。
            return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON).body("{\"message\":\"AI记忆暂不可用\"}");
        }
    }

    private String token(Long userId) throws Exception {
        // 身份唯一来源为认证principal，客户端body/path中不能替换sub。
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalStateException("内部密钥不足");
        var encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString(mapper.writeValueAsBytes(Map.of("alg", "HS256", "typ", "JWT")));
        String payload = encoder.encodeToString(mapper.writeValueAsBytes(Map.of("sub", userId.toString(), "exp", Instant.now().getEpochSecond() + 60)));
        String input = header + "." + payload;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return input + "." + encoder.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
    }
}
