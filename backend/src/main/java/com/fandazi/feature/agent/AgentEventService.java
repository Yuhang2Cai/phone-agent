// 助手事件服务：持久化公开事件并通知会话订阅；断连只移除订阅，不能反向使业务事务失败。
package com.fandazi.feature.agent;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service public class AgentEventService {
// 初始化对象依赖与默认状态。
    private final AgentEventRepository events; public AgentEventService(AgentEventRepository events) { this.events=events; }
    private AgentKnowledgeDelivery knowledge;
    /** 生产知识门控不能依赖模型引用文本；历史纯订阅测试仍可显式构造无目录的事件服务。 */
    @org.springframework.beans.factory.annotation.Autowired
    void attachKnowledge(AgentKnowledgeDelivery knowledge) { this.knowledge=knowledge; }
    /** 调用方据此把知识许可失败移出可忽略的审计catch；判断本身失败也必须传播。 */
    public boolean requiresKnowledgeDelivery(Long user,String session,String type,Map<String,Object> payload) {
        return knowledge!=null && knowledge.required(user,session,type,payload);
    }
    private final Map<SubscriptionKey, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    // 初始历史读取期间先缓冲实时提交，避免持有订阅锁等待数据库会话锁而与提交回调互锁。
    private final Map<SseEmitter,java.util.ArrayList<AgentEventEntity>> replayBuffers = new ConcurrentHashMap<>();
    // 与订阅登记共用锁，防止关闭已清空后晚到请求再次挂入无限期SSE。
    private boolean stopping;

    /** ContextClosedEvent先于Web服务器优雅关闭阶段：主动结束本Bean拥有的订阅，不等待下一次业务事件。 */
    @org.springframework.context.event.EventListener(org.springframework.context.event.ContextClosedEvent.class)
    public void closeSubscriptions() {
        synchronized(subscribers) {
            stopping=true;
            var closing=subscribers.values().stream().flatMap(List::stream).toList();
            subscribers.clear();
            replayBuffers.clear();
            for(var emitter:closing) {
                try {emitter.complete();}
                catch(IllegalStateException ignored) { /* 容器已清理的订阅不影响其余订阅完成。 */ }
            }
        }
    }
    /** 记录可重放的公开事件；入库前移除内部凭证，避免历史查询与实时 SSE 泄漏授权信息。 */
    @Transactional public AgentEventEntity record(Long userId,String sessionId,String type,String summary,String toolName,String status,Map<String,Object> payload) {
        Map<String, Object> publicPayload = AgentContractCompatibility.publicEventPayload(payload);
        var pending = new AgentEventEntity(userId, sessionId, type, summary, toolName, status, publicPayload);
        if(requiresKnowledgeDelivery(userId,sessionId,type,publicPayload)) {
            // 内部身份在授权前生成，许可成功后才插入事件；失败不留下可重放正文。
            String reference="event:"+java.util.UUID.randomUUID();
            int scopeCount=knowledge.authorize(userId,sessionId,type,publicPayload,reference);
            pending.attachKnowledgeDelivery(reference,scopeCount);
        }
        AgentEventEntity event = events.save(pending);
        // 真实事务必须提交成功后才向SSE发送；回滚事件与许可不能先变成客户端可见事实。
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { publish(event); }
                });
        } else publish(event);
        return event;
    }
    /** 历史查询先取得事件，再由独立事务边界重验知识；禁止复用旧许可或只读事务跳过许可落库。 */
    public List<AgentEventEntity> after(Long userId,String sessionId,Long lastId) {
        var history=lastId == null ? events.findByUserIdAndSessionIdOrderByIdAsc(userId,sessionId)
                : events.findByUserIdAndSessionIdAndIdGreaterThanOrderByIdAsc(userId,sessionId,lastId);
        return knowledge==null?history:knowledge.replay(userId,sessionId,history);
    }
// 处理 `subscribe` 相关逻辑。
    public SseEmitter subscribe(Long userId,String sessionId,Long lastId,java.util.function.Function<AgentEventEntity,Object> responseFactory) {
        SseEmitter emitter=new SseEmitter(0L); SubscriptionKey key=new SubscriptionKey(userId,sessionId);
        try {
            synchronized (subscribers) {
                // 关闭窗口内不读取历史、不添加新订阅；上游账号归属检查仍由正式控制器完成。
                if(stopping)throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"服务正在关闭，请稍后重连");
                subscribers.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
                replayBuffers.put(emitter,new java.util.ArrayList<>());
            }
            // 数据库锁在订阅锁之外获取；提前登记缓冲保证查询历史与进入实时订阅之间不漏事件。
            var history=after(userId,sessionId,lastId);
            synchronized(subscribers) {
                var buffered=replayBuffers.remove(emitter);
                if(buffered==null)return emitter; // 关闭或断连已移除，不能向已完成容器发送。
                long cursor=lastId==null?0:lastId;
                for(var event:history) {send(emitter,event,responseFactory);cursor=Math.max(cursor,event.getId());}
                // 历史查询可能已包含刚提交的事件，按持久主键去重并按提交事件顺序补发。
                buffered.sort(java.util.Comparator.comparing(AgentEventEntity::getId));
                for(var event:buffered)if(event.getId()>cursor) {send(emitter,event,responseFactory);cursor=event.getId();}
            }
        } catch (IOException | IllegalStateException exception) { disconnect(key,emitter,exception); }
        catch (RuntimeException exception) {
            // 数据库超时等异常也必须移除提前登记的缓冲，不能留下永远等待历史的订阅。
            disconnect(key,emitter,exception);throw exception;
        }
        Runnable remove=()->remove(key,emitter); emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(ignored->remove.run());
        return emitter;
    }
// 处理 `publish` 相关逻辑。
    private void publish(AgentEventEntity event) {
        SubscriptionKey key=new SubscriptionKey(event.getUserId(),event.getSessionId());
        synchronized (subscribers) {
            for (SseEmitter emitter : subscribers.getOrDefault(key,new CopyOnWriteArrayList<>())) {
                var buffer=replayBuffers.get(emitter);
                if(buffer!=null) {buffer.add(event);continue;}
                // 发送和容器清理都可能遇到断连；将故障限定在当前订阅，其他订阅与持久事务继续。
                try { send(emitter,event,AgentGatewayController.EventResponse::from); }
                catch (IOException | IllegalStateException exception) { disconnect(key,emitter,exception); }
            }
        }
    }
    /** 先移除失效订阅再尝试结束容器；异步上下文已经销毁时清理异常不能传播到业务HTTP。 */
    private void disconnect(SubscriptionKey key,SseEmitter emitter,Exception failure) {
        remove(key,emitter);
        try { emitter.completeWithError(failure); }
        catch (IllegalStateException ignored) {
            // Tomcat错误回调可能已经结束AsyncContext；订阅已移除，不再访问容器或回滚事件。
        }
    }
// 发送当前数据。
    private void send(SseEmitter emitter,AgentEventEntity event,java.util.function.Function<AgentEventEntity,Object> responseFactory) throws IOException { emitter.send(SseEmitter.event().id(event.getId().toString()).name("agent-event").data(responseFactory.apply(event))); }
// 移除当前数据。
    private void remove(SubscriptionKey key,SseEmitter emitter) { synchronized(subscribers) {replayBuffers.remove(emitter);CopyOnWriteArrayList<SseEmitter> values=subscribers.get(key); if(values!=null){values.remove(emitter);if(values.isEmpty())subscribers.remove(key,values);}} }
    private record SubscriptionKey(Long userId,String sessionId) {}
}
