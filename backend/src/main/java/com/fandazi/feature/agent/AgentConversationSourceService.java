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

/** 事务覆盖本库会话、来源及可选用户历史写入；不跨越HTTP或模型调用。 */
@Service
public class AgentConversationSourceService {
    private final AgentSessionRepository sessions;
    private final AgentConversationSourceRepository sources;
    private final AgentSessionService sessionService;
    private AgentMessageWriter messageWriter;

    /** 注入真实仓库；身份来自已鉴权网关，不能使用模型输出。 */
    public AgentConversationSourceService(AgentSessionRepository sessions,AgentConversationSourceRepository sources,AgentSessionService sessionService) {
        this.sessions=sessions;this.sources=sources;this.sessionService=sessionService;
    }

    /** 生产环境注入消息写入器；可选装配保留只验证来源领域的旧手工构造测试。 */
    @org.springframework.beans.factory.annotation.Autowired
    void attachMessageWriter(AgentMessageWriter messageWriter) { this.messageWriter=messageWriter; }

    /** 同一事务建立本轮任务和来源；正文冲突必须一起回滚，不能破坏旧确认停点。 */
    @Transactional
    public StartedInput beginInput(Long userId,String sessionId,String body,String clientMessageKey,AgentMode mode,String traceId) {
        return beginInput(userId,sessionId,body,clientMessageKey,mode,traceId,Map.of());
    }

    /** 同一展示键必须同时绑定原句与已校验选择引用，防止相同文字重试被悄悄换到另一列表。 */
    @Transactional
    public StartedInput beginInput(Long userId,String sessionId,String body,String clientMessageKey,AgentMode mode,String traceId,Map<String,Object> selectionRef) {
        return beginInputLocked(userId,sessionId,body,clientMessageKey,mode,traceId,selectionRef);
    }

    /**
     * 生产 2.1 输入在同一短事务内完成会话启动、来源签发、用户原文写入和来源关联。
     * 外部 Agent HTTP 不在本事务内；消息写入失败会以专用异常退出并整体回滚，网关随后按旧隔离路径降级。
     */
    @Transactional
    public StartedInput beginInputRecorded(Long userId,String sessionId,String body,String clientMessageKey,AgentMode mode,
                                           String traceId,Map<String,Object> selectionRef) {
        if(messageWriter==null)throw new IllegalStateException("消息写入器尚未装配");
        StartedInput started=beginInputLocked(userId,sessionId,body,clientMessageKey,mode,traceId,selectionRef);
        if(!"2.1".equals(started.protocolVersion()))return started;
        var source=started.source();
        if(!started.sourceCreated() && Boolean.TRUE.equals(source.get("historyRecorded")))return started;
        try {
            // 新来源可直接插入首条消息；真实重试仍先查 record_key，两个分支都加入当前事务并复核来源归属。
            if(started.sourceCreated())messageWriter.writeFreshLinkedSourceVerified(userId,sessionId,started.runId(),traceId,body,
                clientMessageKey,(String)source.get("sourceMessageId"),(String)source.get("conversationEpoch"));
            else messageWriter.writeLinkedSourceVerified(userId,sessionId,started.runId(),traceId,body,clientMessageKey,
                (String)source.get("sourceMessageId"),(String)source.get("conversationEpoch"));
        } catch(RuntimeException error) {
            // 专用异常让网关只对历史写入故障降级，来源冲突和归属错误仍直接拒绝，不能被重试掩盖。
            throw new InputHistoryWriteException(error);
        }
        var linkedSource=new java.util.LinkedHashMap<String,Object>(source);
        linkedSource.put("historyRecorded",true);
        return new StartedInput(started.runId(),started.protocolVersion(),linkedSource,started.sourceCreated());
    }

    /**
     * 已持有外层事务时执行输入分配；该私有边界既供来源单独提交，也供生产合并事务复用。
     * 输入校验、会话锁、版本冻结和来源顺序必须保持同一顺序，避免两条入口产生语义漂移。
     */
    private StartedInput beginInputLocked(Long userId,String sessionId,String body,String clientMessageKey,AgentMode mode,
                                          String traceId,Map<String,Object> selectionRef) {
        String fingerprintBody=body;
        if(!selectionRef.isEmpty()) {
            try {fingerprintBody=body+"\u0000"+new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new java.util.TreeMap<>(selectionRef));}
            catch(com.fasterxml.jackson.core.JsonProcessingException error) {throw new DomainException("展示引用无效");}
        }
        // 统一经会话服务先原子创建、再锁实际行；外层先锁缺失行会重新引入MySQL RR创建死锁。
        // start加入本事务并返回已锁实体，来源分配和消息失败仍与会话创建一起回滚。
        AgentSession session=sessionService.start(userId,sessionId,mode,traceId,"2.1");
        String version=session.getContextProtocolVersion();
        if(!"2.1".equals(version)&&!selectionRef.isEmpty())throw new DomainException("当前活动任务暂不支持展示引用");
        // 引用加入来源摘要，不改变记录及发给 Agent 的用户原句，也不成为交易幂等键。
        IssuedSource issued=new IssuedSource(Map.of(),false);
        if("2.1".equals(version)) {
            validateInput(fingerprintBody,clientMessageKey);
            // 会话锁已串行同会话输入；来源普通查询不会对其他会话的缺失键取得共享间隙锁。
            issued=issueStateLocked(session,userId,sessionId,fingerprintBody,clientMessageKey);
        }
        return new StartedInput(session.getActiveRunId(),version,issued.source(),issued.created());
    }

    /** 外部调用只获取已冻结的本轮身份，不携带可被交错请求修改的实体。 */
    public record StartedInput(String runId,String protocolVersion,Map<String,Object> source,boolean sourceCreated) {}

    /** 仅在 Spring 内部携带“本事务新建”事实；公开上下文仍只发送固定 conversationSource 投影。 */
    private record IssuedSource(Map<String,Object> source,boolean created) {}

    /** 仅表示合并事务中的历史写入阶段失败；来源阶段异常保持原类型并由网关直接返回。 */
    public static final class InputHistoryWriteException extends RuntimeException {
        /** 保存根因供日志定位；异常消息不包含用户正文、JWT或业务凭据。 */
        InputHistoryWriteException(RuntimeException cause) { super("输入历史写入失败",cause); }
    }

    /** 同展示键同正文复用来源，不同正文冲突；无键时每次真实输入独立签发。 */
    @Transactional
    public Map<String,Object> issue(Long userId,String sessionId,String body,String clientMessageKey) {
        validateInput(body,clientMessageKey);
        // 先核验归属并持有行锁，再查重及递增；顺序不依赖模型完成顺序。
        var session=sessions.findBySessionIdAndUserIdForUpdate(sessionId,userId)
                .orElseThrow(() -> new DomainException("会话不存在或不属于当前用户"));
        return issueLocked(session,userId,sessionId,body,clientMessageKey);
    }

    /** 仅复用本类公开入口已校验且持锁的会话；私有方法不接受外部传入的实体或省略正文校验。 */
    private Map<String,Object> issueLocked(AgentSession session,Long userId,String sessionId,String body,String clientMessageKey) {
        return issueStateLocked(session,userId,sessionId,body,clientMessageKey).source();
    }

    /** 返回来源投影及是否本事务新建，供首次历史写入安全省略重复防重查询。 */
    private IssuedSource issueStateLocked(AgentSession session,Long userId,String sessionId,String body,String clientMessageKey) {
        String inputKey=inputKey(clientMessageKey);
        var existing=sources.findByUserIdAndSessionIdAndEpochAndInputKey(
            userId,sessionId,session.ensureConversationEpoch(),inputKey).orElse(null);
        return issueStateLocked(session,userId,sessionId,body,inputKey,existing);
    }

    /**
     * 使用锁读中已经取得的可选来源完成冲突判断或新建；不得在此重新查询相同展示键。
     * 会话行锁保证不存在来源时的插入和轮次递增与其他输入串行提交。
     */
    private IssuedSource issueStateLocked(AgentSession session,Long userId,String sessionId,String body,
                                          String inputKey,AgentConversationSource existing) {
        // 身份仍逐项核对；当前实体来自同一活动事务的行锁读取，不使用缓存中的旧epoch。
        if(!session.ownerId().equals(userId)||!session.getSessionId().equals(sessionId))
            throw new DomainException("会话不存在或不属于当前用户");
        String epoch=session.ensureConversationEpoch();
        String fingerprint=hash(body);
        if(existing!=null) {
            // 同键不同正文不能覆盖原始历史，也不能把新输入绑定旧对象。
            if(!existing.bodySha256().equals(fingerprint))throw new DomainException("消息展示身份关联冲突");
            return new IssuedSource(existing.publicSource(),false);
        }
        var created=new AgentConversationSource(userId,sessionId,epoch,inputKey,session.nextConversationTurn(),fingerprint);
        return new IssuedSource(sources.saveAndFlush(created).publicSource(),true);
    }

    /** 展示键参与数据库唯一约束；无展示键的每次输入必须获得独立随机身份，不能按相同正文猜测重试。 */
    private static String inputKey(String clientMessageKey) {
        return clientMessageKey==null ? "input:"+UUID.randomUUID() : "client:"+clientMessageKey;
    }

    /** 原文和展示键验证统一保留；独立issue仍在数据库读取前拒绝非法输入。 */
    private static void validateInput(String body,String clientMessageKey) {
        if(body==null || body.isBlank())throw new DomainException("消息不能为空");
        if(clientMessageKey!=null && !clientMessageKey.matches("[A-Za-z0-9_-]{1,100}"))throw new DomainException("消息展示键无效");
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
