// Agent控制HTTP客户端：短时HS256身份绑定、固定内部路径、严格回执结构，无旧接口回退。
package com.fandazi.feature.agent;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 所有调用发生在控制服务短事务之外；超时仅表示未收到回执，不能推断任务已停止。 */
@Component
class HttpAgentTaskControlClient implements AgentTaskControlClient {
    private final String base;private final String secret;private final ObjectMapper mapper;
    private final HttpClient http;private final AgentProtocolValidator contracts;
    /** 配置地址只来自部署环境；本机直连不受用户桌面代理影响。 */
    HttpAgentTaskControlClient(@Value("${agent.service.base-url:http://127.0.0.1:8091}") String base,
            @Value("${agent.internal-token:}") String secret,ObjectMapper mapper) {
        this.base=base.replaceAll("/$","");this.secret=secret;this.mapper=mapper;contracts=new AgentProtocolValidator(mapper);
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).proxy(new ProxySelector() {
            /** 内部服务地址由配置受控，禁用系统代理避免本机控制被意外转发。 */
            @Override public List<Proxy> select(URI uri) {return List.of(Proxy.NO_PROXY);}
            /** 连接失败向上按不可用处理，不尝试其他代理或旧服务。 */
            @Override public void connectFailed(URI uri,SocketAddress address,java.io.IOException failure) {}
        }).build();
    }
    /** 显式HS256保持Python内部鉴权兼容，不随密钥长度自动改为HS512。 */
    private String token(String user) {
        if(secret.getBytes(StandardCharsets.UTF_8).length<32)throw new AgentServiceUnavailableException("任务控制凭据未配置");
        Instant now=Instant.now();return Jwts.builder().subject(user).issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60)))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)),Jwts.SIG.HS256).compact();
    }
    /** 只读取受控2.2回执；非法JSON/HTTP/Schema均为不可用，不伪造成功。 */
    private Map<String,Object> call(String path,String user,Map<String,Object> body) {
        try {
            var request=HttpRequest.newBuilder(URI.create(base+path)).timeout(Duration.ofSeconds(2))
                    .header("Authorization","Bearer "+token(user));
            if(body==null)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if(body==null&&response.statusCode()==404)return null;
            if(response.statusCode()!=200||response.body().length()>65536)throw new AgentServiceUnavailableException("任务控制回执暂不可用");
            Map<String,Object> ack=mapper.readValue(response.body(),new TypeReference<>(){});contracts.validateTaskPayload("controlAck",ack);return ack;
        } catch(InterruptedException error) {Thread.currentThread().interrupt();throw new AgentServiceUnavailableException("任务控制查询已中断");}
        catch(Exception error) {throw new AgentServiceUnavailableException("任务控制回执暂不可用");}
    }
    /** 唯一写控制入口；字段必须已由任务服务生成，不接受模型指定URL或工具动作。 */
    @Override public Map<String,Object> dispatch(Map<String,Object> command) {
        @SuppressWarnings("unchecked") var scope=(Map<String,Object>)command.get("scope");
        return call("/internal/v2/agent/task-controls",(String)scope.get("userId"),command);
    }
    /** 固定GET路径与scope查询参数，仅回读已有Agent收件箱，不重发apply。 */
    @Override public Map<String,Object> lookup(String commandId,AgentTaskRegistryService.Scope scope) {
        String path="/internal/v2/agent/task-controls/"+URLEncoder.encode(commandId,StandardCharsets.UTF_8)
                +"?sessionId="+URLEncoder.encode(scope.sessionId(),StandardCharsets.UTF_8)
                +"&conversationEpoch="+URLEncoder.encode(scope.epoch(),StandardCharsets.UTF_8);
        return call(path,String.valueOf(scope.userId()),null);
    }
}
