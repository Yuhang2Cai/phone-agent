// 智能助手模块：定义持久化数据结构。
package com.fandazi.feature.agent;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;

@Entity @Table(name = "agent_events")
public class AgentEventEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long userId; @Column(nullable = false, length = 100) private String sessionId;
    @Column(nullable = false, length = 40) private String type; // 终态摘要可能是完整长回复，不能因事件列上限把成功业务改成失败。
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String summary;
    @Column(length = 100) private String toolName; @Column(nullable = false, length = 40) private String status;
    @Convert(converter = StringMapJsonConverter.class) @Column(nullable = false, columnDefinition = "LONGTEXT") private Map<String, Object> payload;
    @Column(nullable = false) private Instant createdAt;
    // 内部交付关联独立于公开载荷；只有事件服务可设置，不接受模型或客户端提供的许可标识。
    @Column(name="knowledge_delivery_ref",length=255) private String knowledgeDeliveryRef;
    // 零明确表示取消全部知识目标后通过业务事实校验；空值表示旧版本没有保存数量证明。
    @Column(name="knowledge_delivery_scope_count") private Integer knowledgeDeliveryScopeCount;

    /** 返回原始交付关联供历史重新核验；公开DTO不暴露此内部字段，旧记录保持空值。 */
    public String getKnowledgeDeliveryRef(){return knowledgeDeliveryRef;}

    /** 仅同包事件持久化入口设置，必须与许可同事务保存；不允许覆盖已有交付身份。 */
    void attachKnowledgeDelivery(String reference,int scopeCount) {
        if(knowledgeDeliveryRef!=null)throw new IllegalStateException("知识交付身份不可覆盖");
        if(scopeCount<0)throw new IllegalArgumentException("来源数量不能为负");
        knowledgeDeliveryRef=reference;knowledgeDeliveryScopeCount=scopeCount;
    }
// 初始化对象依赖与默认状态。
    protected AgentEventEntity() {}
// 初始化对象依赖与默认状态。
    AgentEventEntity(Long userId, String sessionId, String type, String summary, String toolName, String status, Map<String, Object> payload) {
        this.userId=userId; this.sessionId=sessionId; this.type=type; this.summary=summary; this.toolName=toolName; this.status=status;
        // JSON 空值表示成功工具无错误，必须原样持久化；Map.copyOf 会因 null 抛异常。
        this.payload = payload == null ? Map.of() : new java.util.LinkedHashMap<>(payload);
        this.createdAt=Instant.now();
    }
// 获取id。
    // 获取用户、id。
    // 获取会话、id。
    // 获取type。
    // 获取summary。
    // 获取工具、name。
    // 获取status。
    // 获取payload。
    // 获取created、at。
    public Long getId(){return id;} public Long getUserId(){return userId;} public String getSessionId(){return sessionId;} public String getType(){return type;} public String getSummary(){return summary;} public String getToolName(){return toolName;} public String getStatus(){return status;}
    /** 返回保留合法空值的只读副本，调用方不能修改托管实体的事件载荷。 */
    public Map<String,Object> getPayload(){return java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));}
    public Instant getCreatedAt(){return createdAt;}
}
