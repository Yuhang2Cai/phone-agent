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
