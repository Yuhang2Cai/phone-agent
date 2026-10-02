package com.fandazi.feature.agent;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 账号归属管理入口：Android只连接Spring，用户ID永不从body或查询参数读取。 */
@RestController
@Validated
@RequestMapping("/api/agent/memories")
public class AgentMemoryController {
    private final AgentMemoryClient client;
    /** 客户端内容不能携带权限、状态或TTL；版本由Agent实施乐观锁。 */
    public record Edit(@NotBlank @Size(max=256) String value, @Min(1) int version) {}
    /** 敏感确认必须由用户单独点击，普通确认默认false。 */
    public record Confirm(boolean sensitive, @Min(1) int version) {}
    /** 注入唯一新Agent代理，不提供旧服务fallback。 */
    public AgentMemoryController(AgentMemoryClient client) { this.client = client; }
    /** 读取本人记录，匿名请求由安全链拒绝。 */
    @GetMapping public ResponseEntity<String> list(@AuthenticationPrincipal Long user) { return client.request(user,"GET","",null); }
    /** UUID仅标识记录，不代表归属；Agent还会复查sub。 */
    @PutMapping("/{id}") public ResponseEntity<String> edit(@AuthenticationPrincipal Long user,
        @PathVariable @Pattern(regexp="[a-f0-9-]{36}") String id, @Valid @RequestBody Edit body) {
        return client.request(user,"PUT","/"+id,body);
    }
    /** 确认已读取版本，拒绝客户端猜测内部状态。 */
    @PostMapping("/{id}/confirm") public ResponseEntity<String> confirm(@AuthenticationPrincipal Long user,
        @PathVariable @Pattern(regexp="[a-f0-9-]{36}") String id, @Valid @RequestBody Confirm body) {
        return client.request(user,"POST","/"+id+"/confirm",body);
    }
    /** 删除本人一条记录，索引待补偿不阻碍权威删除。 */
    @DeleteMapping("/{id}") public ResponseEntity<String> delete(@AuthenticationPrincipal Long user,
        @PathVariable @Pattern(regexp="[a-f0-9-]{36}") String id) { return client.request(user,"DELETE","/"+id,null); }
    /** 清空只作用当前登录用户，不能传owner覆盖。 */
    @DeleteMapping public ResponseEntity<String> clear(@AuthenticationPrincipal Long user) { return client.request(user,"DELETE","",null); }
}
