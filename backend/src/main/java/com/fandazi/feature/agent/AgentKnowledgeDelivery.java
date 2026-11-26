package com.fandazi.feature.agent;

import com.fandazi.feature.support.KnowledgeCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 知识最终交付门控：可信动作绑定证明来源，数据库许可与公开事件共享事务；正文不能授予权限。 */
@Service
public class AgentKnowledgeDelivery {
    private final KnowledgeCatalog catalog;
    private final AgentSessionRepository sessions;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /**
     * 消息恢复也重新交付正文。用户输入不作为知识回答；助手消息以持久run身份核对来源。
     * 澄清必须与同用户、会话、run、trace的未完成事件正文精确一致，不能按句式猜测其状态。
     * 加入分页事务，异常向外传播并撤销本页全部许可；不重新执行工具或修改历史消息。
     */
    @Transactional
    public void replayMessages(Long user,String session,List<AgentMessageEntity> history) {
        // 同分页可能先读无run短回复再读知识消息；统一先锁session，避免与轮次完成的锁序相反。
        lockSession(user,session);
        for(var message:history) {
            if(!user.equals(message.getUser().getId()) || !session.equals(message.getSessionId()))
                throw new IllegalStateException("历史消息归属无效");
            if(!"assistant".equals(message.getRole()))continue;
            // 无run正文无法区分旧知识与普通答案，不能猜测为安全；只有持久无工具短回复能够正向证明。
            if(message.getRunId()==null || message.getRunId().isBlank()) {
                if(recordedControlReply(user,session,message))continue;
                throw new IllegalStateException("旧助手历史缺少运行身份，请重新查询");
            }
            var payload=Map.<String,Object>of("runId",message.getRunId(),"message",message.getContent());
            if(!required(user,session,"run_completed",payload))continue;
            if(isRecordedClarification(user,session,message))continue;
            // 消息是终态事件的展示副本；必须先找到同trace且正文精确一致的原交付，再重新许可其来源。
            var references=jdbc.query("SELECT knowledge_delivery_ref,payload FROM agent_events WHERE user_id=? AND session_id=? AND type='run_completed' AND knowledge_delivery_ref IS NOT NULL FOR UPDATE",
                (row,index)->new RecordedDelivery(row.getString(1),read(row.getString(2))),user,session).stream()
                .filter(event->message.getRunId().equals(event.payload().get("runId"))
                    && java.util.Objects.equals(message.getTraceId(),event.payload().get("traceId"))
                    && message.getContent().equals(event.payload().get("message"))).toList();
            if(references.isEmpty())throw new IllegalStateException("历史知识消息缺少原交付身份");
            authorizeReplay(user,session,"run_completed",payload,references.get(0).reference(),"history-message:"+message.getPublicId()+":"+java.util.UUID.randomUUID());
        }
    }

    /** 原事件内部交付身份和协议载荷，只在数据库回读中构造，不能由外部请求直接传入。 */
    private record RecordedDelivery(String reference,Map<String,Object> payload) {}

    /** 核对无工具完成回执及原trace；允许合法控制提示，不按任意文本前缀豁免来源检查。 */
    private boolean recordedControlReply(Long user,String session,AgentMessageEntity message) {
        if(message.getTraceId()==null)return false;
        return jdbc.query("SELECT result_json FROM agent_task_turns WHERE user_id=? AND session_id=? AND trace_id=? AND run_id IS NULL AND state='completed' AND may_execute_tools=false AND result_json IS NOT NULL FOR UPDATE",
            (row,index)->read(row.getString(1)),user,session,message.getTraceId()).stream()
            .anyMatch(result->message.getContent().equals(result.get("message")) && result.get("sourceMessageId") instanceof String);
    }

    /**
     * 已完成Task的HTTP回放不会重新经过NDJSON记录入口，故在DTO返回前核验原事件交付集合。
     * 依据持久事件身份和精确正文匹配，不能借当前活动run、模型引用或其它用户许可补造归属。
     */
    @Transactional
    public void replayResult(Long user,String session,List<AgentResult.Event> history) {
        for(var event:history) {
            var payload=event.payload();
            if(!required(user,session,event.type(),payload))continue;
            String bodyKey="text_delta".equals(event.type())?"delta":"message";
            if(payload.get("eventId")==null || payload.get("traceId")==null || payload.get(bodyKey)==null)
                throw new IllegalStateException("完成回执缺少原知识事件身份");
            var matches=jdbc.query("SELECT knowledge_delivery_ref,payload FROM agent_events WHERE user_id=? AND session_id=? AND type=? AND knowledge_delivery_ref IS NOT NULL FOR UPDATE",
                (row,index)->new RecordedDelivery(row.getString(1),read(row.getString(2))),user,session,event.type()).stream()
                .filter(original->List.of("eventId","runId","traceId",bodyKey).stream()
                    .allMatch(key->java.util.Objects.equals(payload.get(key),original.payload().get(key)))).toList();
            if(matches.isEmpty())throw new IllegalStateException("完成回执无法对应原知识交付");
            authorizeReplay(user,session,event.type(),payload,matches.get(0).reference(),"http-replay:"+java.util.UUID.randomUUID());
        }
    }

    /** 仅持久的未完成追问证明这是澄清；没有事件、身份不匹配或正文被替换均不能豁免。 */
    private boolean isRecordedClarification(Long user,String session,AgentMessageEntity message) {
        if(message.getTraceId()==null)return false;
        return jdbc.query("SELECT payload FROM agent_events WHERE user_id=? AND session_id=? AND type='input_required' AND status='waiting_input' FOR UPDATE",
                (row,index)->read(row.getString(1)),user,session).stream()
                .anyMatch(event->message.getRunId().equals(event.get("runId"))
                        && message.getTraceId().equals(event.get("traceId"))
                        && message.getContent().equals(event.get("question")));
    }

    /** 重连也是一次新交付：批量历史许可同事务提交；任一知识已撤回则整批不外发。 */
    @Transactional
    public List<AgentEventEntity> replay(Long user,String session,List<AgentEventEntity> history) {
        for(var event:history) {
            if(!user.equals(event.getUserId()) || !session.equals(event.getSessionId()))
                throw new IllegalStateException("历史事件归属无效");
            if(required(user,session,event.getType(),event.getPayload()))
                authorizeReplay(user,session,event.getType(),event.getPayload(),event.getKnowledgeDeliveryRef(),"replay:"+event.getId()+":"+java.util.UUID.randomUUID());
        }
        return history;
    }

    /** 使用现有同库会话锁和事件表，不创建第二套用户身份或模型工具。 */
    public AgentKnowledgeDelivery(KnowledgeCatalog catalog, AgentSessionRepository sessions, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.catalog=catalog;this.sessions=sessions;this.jdbc=jdbc;this.mapper=mapper;
    }
    /** 判断是否必须走不可忽略的交付路径；目录不可读时异常向外传播，不能猜测为普通审计。 */
    @Transactional
    public boolean required(Long user, String session, String type, Map<String,Object> payload) {
        if (!List.of("run_completed","text_delta").contains(type)) return false;
        if (!(payload.get("runId") instanceof String run) || run.isBlank()) return false;
        lockSession(user,session);
        if (!catalog.scopes(user.toString(),session,run).isEmpty()) return true;
        // 升级前的复合任务可能只有query_faq账本且run_id为空，一级意图不是faq。
        // 使用工具桥原本校验过的动作键前缀恢复“曾读知识”的事实，仅用于要求门控，绝不补造来源许可。
        // Java精确前缀避免SQL通配符把含下划线的run误匹配到其它运行。
        if(jdbc.query("SELECT execution_key,run_id FROM agent_tool_executions WHERE user_id=? AND session_id=? AND tool_name='query_faq' FOR UPDATE",
                (row,index)->run.equals(row.getString(2)) || row.getString(1).startsWith(run+":"),user,session).stream().anyMatch(Boolean.TRUE::equals))return true;
        // 没有绑定的旧FAQ结果也不能交付。只读取持久协议身份，不从答案显示文字推导来源。
        return jdbc.query("SELECT payload FROM agent_events WHERE user_id=? AND session_id=? AND type='intent_decided' FOR UPDATE",
                (row,index)->read(row.getString(1)),user,session).stream()
                .anyMatch(event -> run.equals(event.get("runId")) && "faq".equals(event.get("intent")));
    }
    /** 在事件持久化前核对完整正文并记录许可；必须加入调用方的最终交付事务。 */
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public int authorize(Long user, String session, String type, Map<String,Object> payload, String deliveryRef) {
        lockSession(user,session);
        String run=(String)payload.get("runId");
        var original=latestKnowledgeActions(catalog.scopes(user.toString(),session,run));
        // 只有可信工具目标已全部移除才排除该节点；缺元数据的旧动作继续要求完整来源，不能猜测已取消。
        var scopes=original.stream().filter(scope->!removedGoalAction(user,session,run,scope.executionKey())).toList();
        if(original.isEmpty())throw new IllegalStateException("知识回答缺少可信来源，不能交付旧结果");
        authorizeScopes(user,session,type,payload,deliveryRef,scopes,false);
        return scopes.size();
    }

    /** 重放仅复用原交付的身份集合，不复用旧有效性；旧无关联结果明确不可恢复。 */
    private void authorizeReplay(Long user,String session,String type,Map<String,Object> payload,String original,String reference) {
        lockSession(user,session);
        if(original==null || original.isBlank())throw new IllegalStateException("旧知识事件缺少原交付身份");
        // 历史业务事实表示原交付当时的读取结果，不能拿后续筛选的计数改写它。
        // 只有同库原事件类型、运行身份及完整正文都相同，才允许复用“原业务正文已授权”的事实。
        String bodyKey="text_delta".equals(type)?"delta":"message";
        var originals=jdbc.query("SELECT payload FROM agent_events WHERE user_id=? AND session_id=? AND type=? AND knowledge_delivery_ref=? FOR UPDATE",
            (row,index)->read(row.getString(1)),user,session,type,original);
        if(originals.size()!=1 || !java.util.Objects.equals(payload.get("runId"),originals.get(0).get("runId"))
                || payload.get(bodyKey)==null || !payload.get(bodyKey).equals(originals.get(0).get(bodyKey)))
            throw new IllegalStateException("历史正文与原交付不一致");
        var scopes=catalog.deliveryScopes(user.toString(),session,(String)payload.get("runId"),original);
        // 原数量为零才证明当时交付的是已核对的纯业务正文；不能把许可丢失解释为无需知识校验。
        var counts=jdbc.query("SELECT knowledge_delivery_scope_count FROM agent_events WHERE user_id=? AND session_id=? AND type=? AND knowledge_delivery_ref=? FOR UPDATE",
            (row,index)->(Integer)row.getObject(1),user,session,type,original);
        Integer expected=counts.get(0);
        if((expected==null && scopes.isEmpty()) || (expected!=null && expected!=scopes.size()))
            throw new IllegalStateException("原交付来源集合不完整");
        authorizeScopes(user,session,type,payload,reference,scopes,true);
    }

    /** 新交付与历史重放共用完整正文校验；区别仅是可信来源集合的取得方式。 */
    private void authorizeScopes(Long user,String session,String type,Map<String,Object> payload,String deliveryRef,List<KnowledgeCatalog.Scope> scopes,boolean originalBodyVerified) {
        String run=(String)payload.get("runId");
        var fragments=new LinkedHashSet<String>();
        for(var scope:scopes) {
            // 空绑定代表某个知识目标没有证据；不能丢弃该目标后用其它命中掩盖不完整。
            for(var row:catalog.permit(scope,deliveryRef).answers()) fragments.add(row.get("answer"));
        }
        Object raw=payload.get("text_delta".equals(type)?"delta":"message");
        if (!(raw instanceof String text)) throw new IllegalStateException("知识交付缺少完整正文");
        String remainder=text;
        // 只有原文完整存在才消去对应片段；任意删否定、条件或来源都会留下不匹配并失败。
        for(String fragment:fragments) {
            int at=remainder.indexOf(fragment);
            if(at<0)throw new IllegalStateException("知识回答缺少必要来源原文");
            remainder=remainder.substring(0,at)+remainder.substring(at+fragment.length());
        }
        // 历史仍逐来源取得当前许可、逐字核对规则；业务部分此前已通过原事件授权且整段未改变。
        // 这里不宣称旧订单状态是当前状态，历史出口只恢复当时事实，不再从最新动作拼接旧回答。
        if(originalBodyVerified)return;
        // 组合回答的非知识文字仅允许来自本轮已提交工具事实的固定模板，不授权模型自由扩写。
        for(String fact:businessFragments(user,session,run)) remainder=remainder.replace(fact,"");
        if(!remainder.isBlank())throw new IllegalStateException("知识回答包含未授权扩写，拒绝交付");
    }

    /**
     * 只按成功工具的持久目标围栏与同任务当前投影判定取消，不使用模型正文或当前焦点替换原任务。
     * 缺少旧元数据保守保留；损坏、跨代次或投影缺失则拒绝交付，不能以异常作为删除知识要求的理由。
     */
    private boolean removedGoalAction(Long user,String session,String run,String executionKey) {
        return removedGoalAction(user,session,run,executionKey,false);
    }

    /** 已提交写只可核对原效果后作为历史事实展示；此参数不授予工具重放、确认或新写入权限。 */
    private boolean removedGoalAction(Long user,String session,String run,String executionKey,boolean committedWrite) {
        var records=jdbc.query("SELECT e.knowledge_goal_fence,t.goal_projection_json,e.execution_generation,t.execution_generation FROM agent_tool_executions e LEFT JOIN agent_conversation_tasks t ON t.conversation_task_id=e.conversation_task_id WHERE e.execution_key=? AND e.user_id=? AND e.session_id=? AND e.run_id=? FOR UPDATE",
            (row,index)->new Object[]{row.getString(1),row.getString(2),row.getObject(3),row.getObject(4)},executionKey,user,session,run);
        if(records.isEmpty() || records.get(0)[0]==null)return false;
        Object[] row=records.get(0);
        var fence=read((String)row[0]);
        // 既有JSON转换器把未设置的Map保存为{}；这与旧空列一样，只能保留来源要求，不能推定取消。
        if(fence.isEmpty())return false;
        if(row[1]==null)throw new IllegalStateException("知识目标任务代次或投影不可核验");
        // 只读结果和知识必须来自当前代次；已提交写无法通过重新执行来刷新，只能核验原效果。
        // 不允许未来/缺失代次以“历史写”名义放行，也不能仅相信工具名或模型提供的资源摘要。
        if(!java.util.Objects.equals(row[2],row[3]) && !(committedWrite
                && row[2] instanceof Number previous && row[3] instanceof Number current
                && previous.longValue()>0 && previous.longValue()<current.longValue()
                && verifiedCommittedEffect(user,session,run,executionKey,fence)))
            throw new IllegalStateException("知识目标任务代次或投影不可核验");
        var projection=read((String)row[1]);
        if(!(fence.get("goalIds") instanceof List<?> ids) || ids.isEmpty()
                || !(projection.get("outcomes") instanceof List<?> outcomes))throw new IllegalStateException("知识目标归属不可核验");
        var active=new java.util.HashSet<String>();
        for(Object value:outcomes) {
            if(!(value instanceof Map<?,?> goal) || !(goal.get("goalId") instanceof String id))throw new IllegalStateException("当前目标投影无效");
            if(!"cancelled".equals(goal.get("status")))active.add(id);
        }
        // 一个工具可以服务多个目标；只要仍有一个目标保留，就不能丢弃其知识或业务事实。
        return ids.stream().noneMatch(active::contains);
    }

    /** 比对同任务同作用域的不可变效果及原结果；只读取数据库事实，不回填、重放或修复缺失记录。 */
    private boolean verifiedCommittedEffect(Long user,String session,String run,String key,Map<String,Object> fence) {
        var rows=jdbc.query("SELECT e.tool_name,e.result_json,g.receipt_json,g.fingerprint,g.effect_version,t.effect_version,g.goal_revision,g.plan_revision FROM agent_tool_executions e JOIN agent_conversation_tasks t ON t.conversation_task_id=e.conversation_task_id AND t.user_id=e.user_id AND t.session_id=e.session_id AND t.epoch=e.conversation_epoch JOIN agent_goal_effects g ON g.conversation_task_id=e.conversation_task_id AND g.effect_key=e.execution_key WHERE e.user_id=? AND e.session_id=? AND e.run_id=? AND e.execution_key=? FOR UPDATE",
            (row,index)->new Object[]{row.getString(1),row.getString(2),row.getString(3),row.getString(4),row.getLong(5),row.getLong(6),row.getLong(7),row.getLong(8)},user,session,run,key);
        if(rows.size()!=1)return false;
        var row=rows.get(0);
        if(!isCommittedWrite((String)row[0]))return false;
        // 效果版本必须恰为原写前版本加一，且不超当前累计版本；目标和计划版本与原围栏严格一致。
        if(!(fence.get("effectVersion") instanceof Number before) || !(fence.get("goalRevision") instanceof Number goal)
                || !(fence.get("planRevision") instanceof Number plan) || (Long)row[4]!=before.longValue()+1
                || (Long)row[4]>(Long)row[5] || (Long)row[6]!=goal.longValue() || (Long)row[7]!=plan.longValue())return false;
        var result=read((String)row[1]);var receipt=read((String)row[2]);var expected=new java.util.LinkedHashMap<String,Object>();
        // 与写入事务保存的公开安全摘要相同，不携带确认凭证，也不以泛化字段猜测对象类型。
        if(result.get("orderId") instanceof String value)expected.put("orderId",value);
        if(result.get("requestId") instanceof String value)expected.put("refundRequestId",value);
        if(result.get("ticketId") instanceof String value)expected.put("complaintTicketId",value);
        if(result.get("status") instanceof String value)expected.put("status",value);
        return !expected.isEmpty() && expected.equals(receipt) && AgentTaskFingerprint.of(receipt).equals(row[3]);
    }

    /** 只有这四个既有工具产生不可撤销的已提交业务事实；准备和只读结果不能使用此豁免。 */
    private static boolean isCommittedWrite(String tool) {
        return java.util.Set.of("create_group_buy_order","pay_mock_order","request_refund","submit_complaint").contains(tool);
    }

    /** 同节点只消费最大动作版本，历史绑定保留审计；版本来自工具桥已核验的规范动作键。 */
    private List<KnowledgeCatalog.Scope> latestKnowledgeActions(List<KnowledgeCatalog.Scope> scopes) {
        var latest=new java.util.LinkedHashMap<String,KnowledgeCatalog.Scope>();
        var versions=new java.util.HashMap<String,Long>();
        for(var scope:scopes) {
            String prefix=scope.runId()+":"+scope.taskId()+":";
            if(!scope.executionKey().startsWith(prefix))throw new IllegalStateException("知识动作键身份不匹配");
            long version;
            try {version=Long.parseLong(scope.executionKey().substring(prefix.length()));}
            catch(NumberFormatException failure) {throw new IllegalStateException("知识动作版本无法核验",failure);}
            if(version<1 || !scope.executionKey().equals(prefix+version))throw new IllegalStateException("知识动作版本不规范");
            // 只替换同一可信节点的更早动作；不同节点仍全部保留，不丢弃未完成的独立知识目标。
            if(version>versions.getOrDefault(scope.taskId(),0L)) {
                latest.put(scope.taskId(),scope);versions.put(scope.taskId(),version);
            }
        }
        return List.copyOf(latest.values());
    }

    /** 按可信run读取成功账本；旧记录无run身份时不猜测其归属，不从用户文字获取数量。 */
    private java.util.Set<String> businessFragments(Long user,String session,String run) {
        var facts=new LinkedHashSet<String>();
        var resources=new java.util.LinkedHashMap<String,java.util.Set<String>>();
        var labels=Map.of("stores","门店","deals","套餐","orders","订单","vouchers","团购券","coupons","优惠券");
        var writes=Map.of("request_refund","退款申请已提交，正在等待处理。","submit_complaint","投诉已提交，等待客服处理。",
                "create_group_buy_order","订单已创建，请确认是否进行模拟支付。","pay_mock_order","模拟支付已完成。");
        for(var action:latestBusinessActions(user,session,run)) {
            String tool=action.tool();var result=action.result();
            // 数量按真实公开对象去重，与组合卡片汇总一致；失败或仅准备动作不在成功写模板集合。
            for(String key:labels.keySet())if(result.get(key) instanceof List<?> items) {
                var ids=resources.computeIfAbsent(key,ignored->new LinkedHashSet<>());
                for(Object item:items) {
                    // 订单公开契约唯一身份是orderId，其余列表使用publicId；不得用数据库ID或猜测字段兜底。
                    String identityKey="orders".equals(key)?"orderId":"publicId";
                    if(!(item instanceof Map<?,?> object) || !(object.get(identityKey) instanceof String id) || id.isBlank())
                        throw new IllegalStateException("组合业务资源缺少可核验身份");
                    ids.add(id);
                }
            }
            if(writes.containsKey(tool))facts.add(writes.get(tool));
            // 位置说明只能来自该次成功门店查询保存的缺位置事实，不以Agent文本自证输入条件。
            if("search_stores".equals(tool) && Boolean.TRUE.equals(action.locationAbsent()))
                facts.add("未获取定位，门店结果不按距离排序。");
            // 评价是后端原始用户内容，只允许既有预览的完整五条；不把评价当审核知识来源。
            if("search_reviews".equals(tool) && result.get("reviews") instanceof List<?> reviews)
                for(Object item:reviews.subList(0,Math.min(5,reviews.size())))
                    if(item instanceof Map<?,?> review && review.get("content") instanceof String text && !text.isBlank())facts.add(text);
        }
        resources.forEach((key,ids)->facts.add("查询到 "+ids.size()+" 条"+labels.get(key)+"记录"));
        return facts;
    }

    /** 仅存本次数据库查询的成功动作事实，不包含执行权限、确认凭证或用户输入。 */
    private record BusinessAction(String key,String tool,Map<String,Object> result,Boolean locationAbsent) {}

    /** 同节点新动作代表新筛选或恢复结果；保留独立节点，避免把不同条件的历史数组混合计数。 */
    private List<BusinessAction> latestBusinessActions(Long user,String session,String run) {
        var rows=jdbc.query("SELECT execution_key,tool_name,result_json,knowledge_location_absent FROM agent_tool_executions WHERE user_id=? AND session_id=? AND run_id=? ORDER BY created_at,execution_key FOR UPDATE",
            (row,index)->new BusinessAction(row.getString(1),row.getString(2),read(row.getString(3)),row.getObject(4,Boolean.class)),user,session,run);
        var latest=new java.util.LinkedHashMap<String,BusinessAction>();
        var versions=new java.util.HashMap<String,Long>();
        for(var action:rows) {
            String prefix=run+":";int separator=action.key().lastIndexOf(':');
            if(!action.key().startsWith(prefix) || separator<=prefix.length())throw new IllegalStateException("业务动作身份不规范");
            String node=action.key().substring(prefix.length(),separator);String suffix=action.key().substring(separator+1);
            long version;
            try {version=Long.parseLong(suffix);}
            catch(NumberFormatException failure) {throw new IllegalStateException("业务动作版本无法核验",failure);}
            if(version<1 || !Long.toString(version).equals(suffix))throw new IllegalStateException("业务动作版本不规范");
            if(version>versions.getOrDefault(node,0L)) {latest.put(node,action);versions.put(node,version);}
        }
        // 恢复前的旧代次记录只作审计，先按节点选最新动作，才校验当前交付要使用的目标围栏。
        // 不能先过滤失效记录再选版本，否则旧动作既可能错误阻断新读取，也可能在新目标取消后回退复活。
        // 只校验确实参与上述固定正文的动作；旧套餐详情/准备记录未被用作文字授权，不能阻断原订单事实。
        // FAQ另经来源许可路径核验；此筛选不允许任何额外文字，因为最终remainder仍必须为空。
        return latest.values().stream()
            .filter(action->isCommittedWrite(action.tool()) || "search_reviews".equals(action.tool())
                || ("search_stores".equals(action.tool()) && Boolean.TRUE.equals(action.locationAbsent()))
                || java.util.Set.of("stores","deals","orders","vouchers","coupons").stream().anyMatch(key->action.result().get(key) instanceof List<?>))
            .filter(action->!removedGoalAction(user,session,run,action.key(),isCommittedWrite(action.tool()))).toList();
    }
    /** 会话锁先于知识head；重连可读取旧run，但仍必须属于同一用户会话。 */
    private void lockSession(Long user,String session) {
        sessions.findBySessionIdAndUserIdForUpdate(session,user).orElseThrow(()->new IllegalStateException("知识交付会话归属无效"));
    }
    /** 数据库损坏同样失败关闭，不将不可解码旧事件当作没有FAQ需求。 */
    private Map<String,Object> read(String value) {
        try { return mapper.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}); }
        catch(Exception failure) { throw new IllegalStateException("知识事件身份无法核验",failure); }
    }
}
