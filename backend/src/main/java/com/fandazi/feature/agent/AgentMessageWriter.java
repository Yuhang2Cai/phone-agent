// 消息写入事务边界：保存完整正文与展示关联；依赖既有用户/消息仓库，不执行任何交易工具。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每次消息写入使用自己的服务事务；公开网关没有外层交易事务，故记录故障不会回滚已完成业务。 */
@Service
public class AgentMessageWriter {
    private final AgentMessageRepository messages;
    private final UserRepository users;
    private final AgentHistoryFaultRepository faults;
    private AgentConversationSourceRepository sources;
    private AgentSessionRepository sessions;

    /** 生产装配同库来源依赖；原三参构造保持兼容，不改变旧2.0消息写入路径。 */
    @org.springframework.beans.factory.annotation.Autowired
    void attachSources(AgentConversationSourceRepository sources,AgentSessionRepository sessions) {
        this.sources=sources;this.sessions=sessions;
    }

    /** 注入真实仓库，不接受模型构造的用户实体或授权信息。 */
    public AgentMessageWriter(AgentMessageRepository messages,UserRepository users,AgentHistoryFaultRepository faults) { this.messages=messages;this.users=users;this.faults=faults; }

    /**
     * 用户展示重试以client键、旧客户端和助手终态以trace及角色生成唯一键；保留首个完整结果。
     * clientMessageKey只关联显示，不能作为业务幂等键；数据库异常交由外层记录服务单独报告。
     */
    @Transactional
    public AgentMessageEntity write(Long userId,String sessionId,String runId,String traceId,String role,String content,String clientMessageKey) {
        // 展示重试只去重相同用户正文的记录；每次业务输入仍执行并产生自己的trace/助手结果。
        String identity="user".equals(role) && clientMessageKey!=null ? "client:"+clientMessageKey : traceId;
        String key=recordKey(userId,sessionId,identity,role);
        var existing=messages.findByRecordKey(key);
        if(existing.isPresent()) {
            // 同展示键不同正文是记录关联冲突，不能覆盖旧内容或静默把新内容当旧消息。
            if("user".equals(role) && clientMessageKey!=null && !existing.get().getContent().equals(content))
                throw new IllegalArgumentException("消息展示身份关联冲突");
            return existing.get();
        }
        // 正文直接保存，不为旧列长度截断；V16及实体LONGTEXT共同保证长消息可完整恢复。
        AgentMessageEntity row=new AgentMessageEntity(UUID.randomUUID().toString(),users.getReferenceById(userId),sessionId,traceId,runId,role,content,Instant.now(),clientMessageKey,key);
        return messages.saveAndFlush(row);
    }

    /**
     * 在一个短事务内写用户原文并关联已签发来源；来源分配仍在此前独立提交。
     * 参数来自鉴权网关，sourceId不是授权；先锁定会话并验证同用户/epoch，再写历史。
     * 历史或关联失败时整体回滚，异常交外层记录服务标记缺口，不跨越模型和业务工具。
     */
    @Transactional
    public AgentMessageEntity writeLinkedSource(Long userId,String sessionId,String runId,String traceId,
                                                String content,String clientMessageKey,String sourceId) {
        if(sources==null || sessions==null)throw new IllegalStateException("来源写入器尚未装配");
        var session=sessions.findBySessionIdAndUserIdForUpdate(sessionId,userId)
            .orElseThrow(() -> new com.fandazi.feature.DomainException("会话不存在或不属于当前用户"));
        var source=sources.findById(sourceId)
            .orElseThrow(() -> new com.fandazi.feature.DomainException("消息来源不存在"));
        if(!source.ownedBy(userId,sessionId,session.ensureConversationEpoch()))
            throw new com.fandazi.feature.DomainException("消息来源不属于当前会话");
        // 本类调用write复用当前事务；只有真实原文写入成功，才能把该来源标记为历史已记录。
        var message=write(userId,sessionId,runId,traceId,"user",content,clientMessageKey);
        source.linkMessage(message.getPublicId());
        return message;
    }

    /**
     * 为刚由同一网关签发的 2.1 来源写入原文，并用条件 UPDATE 再次验证权威归属。
     *
     * @param userId 已认证用户，不能取自模型输出
     * @param sessionId 当前会话，必须与来源和当前会话记录同时匹配
     * @param runId 本轮运行标识，只用于消息恢复
     * @param traceId 本轮追踪标识，只用于消息恢复与诊断
     * @param content 未改写的用户原文
     * @param clientMessageKey 客户端展示去重键，不授予业务执行权限
     * @param sourceId 紧邻输入事务签发的来源标识
     * @param epoch 签发时冻结的会话 epoch，必须仍是数据库当前值
     * @return 已提交关联的用户消息；归属或 epoch 变化时抛出领域异常并整体回滚
     */
    @Transactional
    public AgentMessageEntity writeLinkedSourceVerified(Long userId,String sessionId,String runId,String traceId,
                                                        String content,String clientMessageKey,String sourceId,String epoch) {
        if(sources==null)throw new IllegalStateException("来源写入器尚未装配");
        if(sourceId==null || sourceId.isBlank() || epoch==null || epoch.isBlank())
            throw new com.fandazi.feature.DomainException("消息来源身份不完整");
        // 先保存完整原文；后续条件更新若未命中会抛异常，使消息插入和来源关联一起回滚。
        var message=write(userId,sessionId,runId,traceId,"user",content,clientMessageKey);
        int linked=sources.linkOwnedCurrent(userId,sessionId,epoch,sourceId,message.getPublicId());
        // 正常及同消息幂等重试都必须精确匹配一条来源；零行表示归属、epoch 或已有关联已变化。
        if(linked!=1)throw new com.fandazi.feature.DomainException("消息来源不属于当前会话或已经失效");
        return message;
    }

    /**
     * 写入当前事务刚创建的来源及其首条用户历史；调用方必须持有会话锁并证明来源是新插入。
     * 新记录无需先按 record_key 查询，数据库唯一约束仍是最终防线；冲突会整体回滚并交给网关旧路径复核。
     */
    @Transactional
    public AgentMessageEntity writeFreshLinkedSourceVerified(Long userId,String sessionId,String runId,String traceId,
                                                             String content,String clientMessageKey,String sourceId,String epoch) {
        if(sources==null)throw new IllegalStateException("来源写入器尚未装配");
        if(sourceId==null || sourceId.isBlank() || epoch==null || epoch.isBlank())
            throw new com.fandazi.feature.DomainException("消息来源身份不完整");
        String identity=clientMessageKey!=null ? "client:"+clientMessageKey : traceId;
        String key=recordKey(userId,sessionId,identity,"user");
        // 来源和展示键都在同一会话锁下首次创建；直接 INSERT 消除并发 C5 中每请求一次无意义预读。
        var message=new AgentMessageEntity(UUID.randomUUID().toString(),users.getReferenceById(userId),sessionId,traceId,
            runId,"user",content,Instant.now(),clientMessageKey,key);
        message=messages.saveAndFlush(message);
        int linked=sources.linkOwnedCurrent(userId,sessionId,epoch,sourceId,message.getPublicId());
        if(linked!=1)throw new com.fandazi.feature.DomainException("消息来源不属于当前会话或已经失效");
        return message;
    }

    /** 并发唯一键冲突后重新读取首个已提交消息；只用于识别重复记录，不覆盖正文或重新执行业务。 */
    @Transactional(readOnly=true)
    public java.util.Optional<AgentMessageEntity> existing(Long userId,String sessionId,String traceId,String role,String clientMessageKey) {
        String identity="user".equals(role) && clientMessageKey!=null ? "client:"+clientMessageKey : traceId;
        return messages.findByRecordKey(recordKey(userId,sessionId,identity,role));
    }

    /** 首次缺口持久标记使用新事务，不能把失败消息事务的rollback-only状态带入业务链。 */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void markUnavailable(Long userId,String sessionId) {
        if(!faults.existsByUserIdAndSessionId(userId,sessionId))faults.saveAndFlush(new AgentHistoryFaultEntity(userId,sessionId));
    }

    /** 私有数据库故障事实不因服务重建或后续正常消息而消失，不自动掩盖历史缺口。 */
    @Transactional(readOnly=true)
    public boolean hasPersistentFault(Long userId,String sessionId) {
        return faults.existsByUserIdAndSessionId(userId,sessionId);
    }

    /** 长度前缀区分会话/trace边界；摘要只作数据库唯一索引，不是可执行授权凭证。 */
    private static String recordKey(Long userId,String sessionId,String traceId,String role) {
        String source="message-v1|"+userId+"|"+sessionId.length()+":"+sessionId+"|"+traceId.length()+":"+traceId+"|"+role;
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException error) { throw new IllegalStateException("运行环境缺少SHA-256",error); }
    }
}
