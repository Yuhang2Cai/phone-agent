// 受理前解释HTTP：固定路径/短期JWT/严格字段边界，所有网络等待均在调度事务外。
package com.fandazi.feature.agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 不接受模型URL/工具指令；解释服务不可用时稳定失败，不把未知结果当作继续或取消。 */
@Component
public class HttpAgentTaskInputClient implements AgentTaskInputClient {
    private final URI uri;private final String secret;private final ObjectMapper mapper;
    // 内部Uvicorn只协商HTTP/1.1；Java默认h2c升级会使当前服务把POST正文判为空，必须在传输层显式关闭。
    private final HttpClient http=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    /** 新归属/原分类/类内解释可能串行调用；连接有界且不持DB锁，性能在TL-10固定条件实测。 */
    public HttpAgentTaskInputClient(@Value("${agent.service.base-url:http://127.0.0.1:8091}") String base,
            @Value("${agent.internal-token:}") String secret,ObjectMapper mapper) {
        this.uri=URI.create(base.replaceAll("/+$","")+"/internal/v2/agent/task-inputs/interpret");this.secret=secret;this.mapper=mapper;
    }
    /** 原样发送内部公开输入，只接收固定决策结构；HTTP重复不会让Agent重新解释已保存requestId。 */
    @Override public Map<String,Object> interpret(Long user,String sessionId,Map<String,Object> request) {
        try {
            if(secret.getBytes(StandardCharsets.UTF_8).length<32)throw new IllegalStateException("内部凭据未配置");
            String token=Jwts.builder().claims(AgentRequestBudget.claims(Map.of())).subject(String.valueOf(user)).expiration(Date.from(Instant.now().plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)),Jwts.SIG.HS256).compact();
            // 归属解释消耗入口同一余额；后续运行继续原截止，不再获得第二个110秒。
            var message=HttpRequest.newBuilder(uri).timeout(AgentRequestBudget.remaining(110000)).header("Authorization","Bearer "+token)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))).build();
            var response=http.send(message,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // 输入解释和后续执行均可能被独立防御限流；仅传递固定状态/等待秒，不暴露上游正文。
            if(response.statusCode()==429 || response.statusCode()==503)
                throw new AgentUpstreamAdmissionException(response.statusCode(),response.headers().firstValue("Retry-After").orElse(null));
            if(response.statusCode()!=200||response.body().length()>100000)throw new IllegalStateException("归属响应无效");
            Map<String,Object> result=mapper.readValue(response.body(),new TypeReference<>(){});
            var fields=Set.of("schemaVersion","requestId","action","targetTaskId","targetTaskVersion","newRequest","reasonCode","question",
                "intent","purpose","taskKind","focusVersion","returnTo","bodySha256","executionQuerySha256");
            if(!result.keySet().equals(fields)||!"2.2".equals(result.get("schemaVersion"))||!request.get("requestId").equals(result.get("requestId")))
                throw new IllegalStateException("归属协议无效");
            return result;
        } catch(AgentUpstreamAdmissionException rejected){throw rejected;}
        catch(InterruptedException error){Thread.currentThread().interrupt();throw new AgentServiceUnavailableException("任务归属请求已中断");}
        catch(Exception error){throw new AgentServiceUnavailableException("任务归属暂不可用，请稍后重试");}
    }
}
