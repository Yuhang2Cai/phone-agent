// 可信输入来源模块：模型调用前签发输入身份、epoch 和顺序，依赖会话行锁及来源仓库。
package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 事务只覆盖来源分配，不跨越历史写入、HTTP 或模型调用。 */
@Service
public class AgentConversationSourceService {
    private final AgentSessionRepository sessions;
    private final AgentConversationSourceRepository sources;
    private final AgentSessionService sessionService;

    /** 注入真实仓库；身份来自已鉴权网关，不能使用模型输出。 */
    public AgentConversationSourceService(AgentSessionRepository sessions,AgentConversationSourceRepository sources,AgentSessionService sessionService) {
        this.sessions=sessions;this.sources=sources;this.sessionService=sessionService;
    }

    /** 同一事务建立本轮任务和来源；正文冲突必须一起回滚，不能破坏旧确认停点。 */
    @Transactional
    public StartedInput beginInput(Long userId,String sessionId,String body,String clientMessageKey,AgentMode mode,String traceId) {
        var session=sessionService.start(userId,sessionId,mode,traceId,"2.1");
        String version=session.getContextProtocolVersion();
        Map<String,Object> source="2.1".equals(version) ? issue(userId,sessionId,body,clientMessageKey) : Map.of();
        return new StartedInput(session.getActiveRunId(),version,source);
    }

    /** 外部调用只获取已冻结的本轮身份，不携带可被交错请求修改的实体。 */
    public record StartedInput(String runId,String protocolVersion,Map<String,Object> source) {}

    /** 同展示键同正文复用来源，不同正文冲突；无键时每次真实输入独立签发。 */
    @Transactional
    public Map<String,Object> issue(Long userId,String sessionId,String body,String clientMessageKey) {
        if(body==null || body.isBlank())throw new DomainException("消息不能为空");
        if(clientMessageKey!=null && !clientMessageKey.matches("[A-Za-z0-9_-]{1,100}"))throw new DomainException("消息展示键无效");
        // 先核验归属并持有行锁，再查重及递增；顺序不依赖模型完成顺序。
        var session=sessions.findBySessionIdAndUserIdForUpdate(sessionId,userId)
                .orElseThrow(() -> new DomainException("会话不存在或不属于当前用户"));
        String epoch=session.ensureConversationEpoch();
        String inputKey=clientMessageKey==null ? "input:"+UUID.randomUUID() : "client:"+clientMessageKey;
        String fingerprint=hash(body);
        var existing=sources.findByUserIdAndSessionIdAndEpochAndInputKey(userId,sessionId,epoch,inputKey);
        if(existing.isPresent()) {
            // 同键不同正文不能覆盖原始历史，也不能把新输入绑定旧对象。
            if(!existing.get().bodySha256().equals(fingerprint))throw new DomainException("消息展示身份关联冲突");
            return existing.get().publicSource();
        }
        var created=new AgentConversationSource(userId,sessionId,epoch,inputKey,session.nextConversationTurn(),fingerprint);
        return sources.saveAndFlush(created).publicSource();
    }

    /** 关联历史实际结果；记录失败必须返回 false，不能伪装成完整历史。 */
    @Transactional
    public Map<String,Object> linkHistory(Long userId,String sessionId,String sourceId,String messageId) {
        var session=sessions.findBySessionIdAndUserIdForUpdate(sessionId,userId)
                .orElseThrow(() -> new DomainException("会话不存在"));
        var source=sources.findById(sourceId).orElseThrow(() -> new DomainException("消息来源不存在"));
        if(!source.ownedBy(userId,sessionId,session.ensureConversationEpoch()))throw new DomainException("消息来源不属于当前会话");
        source.linkMessage(messageId);
        return source.publicSource();
    }

    /** 对精确 UTF-8 原文取摘要，不做清洗；空白改动也算不同输入。 */
    private static String hash(String body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("运行环境缺少 SHA-256",error); }
    }
}
