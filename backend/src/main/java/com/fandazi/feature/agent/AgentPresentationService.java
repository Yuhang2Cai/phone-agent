// 展示登记服务：最终重查卡片后登记顺序，后续只从已登记列表生成可信证据。
package com.fandazi.feature.agent;

import com.fandazi.feature.DomainException;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 不相信客户端候选数组或模型展示版本；列表关联不证明用户视觉注意，不授予写入资格。 */
@Service
public class AgentPresentationService {
    private static final Set<String> TYPES=Set.of("store","deal","order","voucher","coupon");
    private final AgentSessionRepository sessions;
    private final AgentConversationSourceRepository sources;
    private final AgentPresentationRepository presentations;
    /** 注入持久仓库，进程重启后仍能校验历史列表引用。 */
    public AgentPresentationService(AgentSessionRepository sessions,AgentConversationSourceRepository sources,AgentPresentationRepository presentations) {
        this.sessions=sessions;this.sources=sources;this.presentations=presentations;
    }

    /** 出口登记同时返回已锁定会话；调用方只能在当前事务内更新确认摘要和终态。 */
    record CompletionRegistration(AgentSession session,List<Map<String,Object>> cards) {}

    /** 仅接收网关最终重查的卡片；保留顺序，非资源卡不登记，异常不产生半份列表。 */
    @Transactional
    public List<Map<String,Object>> register(Long userId,String sessionId,String sourceId,List<Map<String,Object>> finalCards) {
        var session=sessions.findBySessionIdAndUserIdForUpdate(sessionId,userId).orElseThrow(() -> invalid());
        String epoch=session.ensureConversationEpoch();
        var source=sources.findById(sourceId).orElseThrow(() -> invalid());
        if(!source.ownedBy(userId,sessionId,epoch))throw invalid();
        return registerVerified(userId,sessionId,epoch,sourceId,finalCards);
    }

    /**
     * 会话出口用一次联合锁读核验用户、会话、epoch 与来源，并在同一事务返回受管会话。
     * 该入口不接受客户端来源；sourceId 来自本轮已冻结的服务端输入，联合查询仍执行最终归属复核。
     */
    @Transactional
    CompletionRegistration registerForCompletion(Long userId,String sessionId,String sourceId,List<Map<String,Object>> finalCards) {
        var session=sessions.findOwnedSourceForUpdate(userId,sessionId,sourceId).orElseThrow(() -> invalid());
        return new CompletionRegistration(session,registerVerified(userId,sessionId,session.conversationEpoch(),sourceId,finalCards));
    }

    /** 已完成归属锁读后登记或失效展示；不再次读取同一会话和来源。 */
    private List<Map<String,Object>> registerVerified(Long userId,String sessionId,String epoch,String sourceId,List<Map<String,Object>> finalCards) {
        var grouped=new LinkedHashMap<String,List<String>>();
        // 在卡片已重查的出口按实际类型登记，不能登记被过滤项或从确认卡猜关联资源。
        for(var card:finalCards) {
            String type=type(card);
            if(!TYPES.contains(type))continue;
            if(!(card.get("resourceId") instanceof String id)||id.isBlank()||id.length()>100)throw invalid();
            var ids=grouped.computeIfAbsent(type,key -> new ArrayList<>());
            if(ids.contains(id))throw invalid();
            ids.add(id);if(ids.size()>100)throw invalid();
        }
        var evidence=new HashMap<String,Map<String,Object>>();
        for(var previous:presentations.findAllBySourceId(sourceId)) {
            if(!grouped.containsKey(previous.evidence().get("resourceType")))previous.revise(List.of());
        }
        for(var entry:grouped.entrySet()) {
            var row=presentations.findBySourceIdAndResourceType(sourceId,entry.getKey())
                .orElseGet(() -> new AgentPresentation(userId,sessionId,epoch,sourceId,entry.getKey(),entry.getValue()));
            if(!row.ownedBy(userId,sessionId,epoch))throw invalid();
            row.revise(entry.getValue());evidence.put(entry.getKey(),presentations.saveAndFlush(row).evidence());
        }
        var output=new ArrayList<Map<String,Object>>();
        for(var card:finalCards) {
            var copy=new LinkedHashMap<>(card);copy.remove("presentation");
            String type=type(card);var proof=evidence.get(type);
            if(proof!=null) {
                var metadata=new LinkedHashMap<>(proof);metadata.remove("orderedIds");
                metadata.put("ordinal",grouped.get(type).indexOf(card.get("resourceId"))+1);copy.put("presentation",metadata);
            }
            output.add(copy);
        }
        return List.copyOf(output);
    }

    /** 调用 Agent 前校验客户端引用并从库生成 evidence；任何非法字段/版本/范围明确拒绝。 */
    @Transactional(readOnly=true)
    public Map<String,Object> context(Long userId,String sessionId,Map<String,Object> reference) {
        if(reference==null)return Map.of();
        if(!Set.of("presentationId","version","ordinal").containsAll(reference.keySet())||!reference.keySet().containsAll(Set.of("presentationId","version")))throw invalid();
        if(!(reference.get("presentationId") instanceof String id)||id.isBlank()||id.length()>100)throw invalid();
        var session=sessions.findBySessionIdAndUserId(sessionId,userId).orElseThrow(() -> invalid());
        var row=presentations.findById(id).orElseThrow(() -> invalid());
        if(!row.ownedBy(userId,sessionId,session.ensureConversationEpoch())||row.ids().isEmpty())throw invalid();
        var proof=row.evidence();int version=positiveInteger(reference.get("version"));
        if(version!=((Number)proof.get("version")).intValue())throw invalid();
        var selection=new LinkedHashMap<String,Object>();selection.put("presentationId",id);selection.put("version",version);
        if(reference.containsKey("ordinal")) {
            int ordinal=positiveInteger(reference.get("ordinal"));if(ordinal>row.ids().size())throw invalid();selection.put("ordinal",ordinal);
        }
        return Map.of("selectionRef",selection,"presentationEvidence",List.of(proof));
    }
    /** JSON 整数只接受 Java 整数类型，拒绝布尔、小数、数值字符串及溢出。 */
    private static int positiveInteger(Object value) {
        if(!(value instanceof Integer||value instanceof Long)||((Number)value).longValue()<1||((Number)value).longValue()>Integer.MAX_VALUE)throw invalid();
        return ((Number)value).intValue();
    }
    /** 售后选择卡仍是团购券；退款确认卡不是券列表，不能据此造选择引用。 */
    private static String type(Map<String,Object> card) {return "after_sale_voucher_choice".equals(card.get("type")) ? "voucher" : String.valueOf(card.get("type"));}
    /** 对外仅返回稳定错误，不泄露另一用户列表的成员或存在性。 */
    private static DomainException invalid() {return new DomainException("展示引用无效或已过期，请重新选择");}
}
