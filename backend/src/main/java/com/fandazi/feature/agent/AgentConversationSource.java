// 会话来源模块：持久化服务端输入身份和顺序，只保存正文摘要与历史消息引用。
package com.fandazi.feature.agent;

import jakarta.persistence.*;
import java.time.Instant;

/** 来源不是业务幂等键；同展示键重试不因此重新授予交易权限。 */
@Entity
@Table(name="agent_conversation_sources", uniqueConstraints={
        @UniqueConstraint(name="ux_context_input",columnNames={"user_id","session_id","epoch","input_key"}),
        @UniqueConstraint(name="ux_context_turn",columnNames={"user_id","session_id","epoch","turn_sequence"})})
class AgentConversationSource {
    @Id @Column(name="source_id",length=100) private String sourceId;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(name="session_id",nullable=false,length=100) private String sessionId;
    @Column(nullable=false,length=100) private String epoch;
    @Column(name="input_key",nullable=false,length=110) private String inputKey;
    @Column(name="turn_sequence",nullable=false) private long turnSequence;
    @Column(name="body_sha256",nullable=false,length=64) private String bodySha256;
    @Column(name="message_id",length=80) private String messageId;
    @Column(name="created_at",nullable=false) private Instant createdAt;

    /** 仅供 JPA 还原记录，不作为未校验请求构造入口。 */
    protected AgentConversationSource() {}

    /** 由持有会话行锁的服务创建；参数不含身份令牌或交易凭证。 */
    AgentConversationSource(Long userId,String sessionId,String epoch,String inputKey,long turnSequence,String bodySha256) {
        this.sourceId=java.util.UUID.randomUUID().toString();this.userId=userId;this.sessionId=sessionId;
        this.epoch=epoch;this.inputKey=inputKey;this.turnSequence=turnSequence;this.bodySha256=bodySha256;
        this.createdAt=Instant.now();
    }
    /** 正文摘要仅用于冲突检测，不作为用户授权签名。 */
    String bodySha256() { return bodySha256; }
    /** 返回来源身份以关联历史写入结果。 */
    String sourceId() { return sourceId; }
    /** 精确核对归属及 epoch，拒绝别的会话来源。 */
    boolean ownedBy(Long user,String session,String currentEpoch) {
        return userId.equals(user) && sessionId.equals(session) && epoch.equals(currentEpoch);
    }
    /** 历史成功后关联消息 ID；失败保留空值，不能声称完整记录。 */
    void linkMessage(String value) { if(value!=null)messageId=value; }
    /** 公开来源不暴露正文摘要及内部重试键。 */
    java.util.Map<String,Object> publicSource() {
        return java.util.Map.of("sourceMessageId",sourceId,"conversationEpoch",epoch,
                "turnSequence",turnSequence,"historyRecorded",messageId!=null);
    }
}
