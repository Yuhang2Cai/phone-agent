// 展示登记实体：保存最终可公开卡片的有序 ID，依赖会话 epoch 和可信输入来源。
package com.fandazi.feature.agent;

import jakarta.persistence.*;
import java.util.*;

/** 不存价格、凭证或模型候选；版本只说明同一展示列表的顺序修订。 */
@Entity @Table(name="agent_presentations",uniqueConstraints=@UniqueConstraint(name="ux_presentation_source_type",columnNames={"source_id","resource_type"}))
class AgentPresentation {
    @Id @Column(name="presentation_id",length=100) private String presentationId;
    @Column(name="user_id",nullable=false) private Long userId;
    @Column(name="session_id",nullable=false,length=100) private String sessionId;
    @Column(nullable=false,length=100) private String epoch;
    @Column(name="source_id",nullable=false,length=100) private String sourceId;
    @Column(name="resource_type",nullable=false,length=20) private String resourceType;
    @Column(name="presentation_version",nullable=false) private int version;
    @Convert(converter=StringMapJsonConverter.class) @Column(name="ordered_ids",nullable=false,columnDefinition="TEXT")
    private Map<String,Object> content;

    /** JPA 还原入口，不供客户端构造。 */
    protected AgentPresentation() {}
    /** 参数由持会话行锁且核验来源的服务提供；初版从一开始。 */
    AgentPresentation(Long userId,String sessionId,String epoch,String sourceId,String resourceType,List<String> ids) {
        this.presentationId=UUID.randomUUID().toString();this.userId=userId;this.sessionId=sessionId;this.epoch=epoch;
        this.sourceId=sourceId;this.resourceType=resourceType;this.version=1;this.content=Map.of("ids",List.copyOf(ids));
    }
    /** 精确归属检查包含 epoch，知道 UUID 不获得别的会话或历史 epoch 的访问权。 */
    boolean ownedBy(Long user,String session,String currentEpoch) {return userId.equals(user)&&sessionId.equals(session)&&epoch.equals(currentEpoch);}
    /** 仅发生实际重排或成员变化才推进版本；同来源相同结果重试保持引用稳定。 */
    void revise(List<String> ids) {if(!ids().equals(ids)){content=Map.of("ids",List.copyOf(ids));version++;}}
    /** 返回有序只读资源副本，不允许调用方原地改变登记顺序。 */
    List<String> ids() {return ((List<?>)content.get("ids")).stream().map(String.class::cast).toList();}
    /** 公开证据只用于上下文，不能作为工具身份或交易确认。 */
    Map<String,Object> evidence() {return Map.of("presentationId",presentationId,"version",version,"resourceType",resourceType,"orderedIds",ids(),"sourceMessageId",sourceId);}
}
