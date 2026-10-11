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
    private final Map<SubscriptionKey, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    /** 记录可重放的公开事件；入库前移除内部凭证，避免历史查询与实时 SSE 泄漏授权信息。 */
    @Transactional public AgentEventEntity record(Long userId,String sessionId,String type,String summary,String toolName,String status,Map<String,Object> payload) {
        Map<String, Object> publicPayload = AgentContractCompatibility.publicEventPayload(payload);
        AgentEventEntity event = events.save(new AgentEventEntity(userId, sessionId, type, summary, toolName, status, publicPayload));
        publish(event);
        return event;
    }
// 处理 `after` 相关逻辑。
    @Transactional(readOnly=true) public List<AgentEventEntity> after(Long userId,String sessionId,Long lastId) { return lastId == null ? events.findByUserIdAndSessionIdOrderByIdAsc(userId,sessionId) : events.findByUserIdAndSessionIdAndIdGreaterThanOrderByIdAsc(userId,sessionId,lastId); }
// 处理 `subscribe` 相关逻辑。
    public SseEmitter subscribe(Long userId,String sessionId,Long lastId,java.util.function.Function<AgentEventEntity,Object> responseFactory) {
        SseEmitter emitter=new SseEmitter(0L); SubscriptionKey key=new SubscriptionKey(userId,sessionId);
        try {
            synchronized (subscribers) {
                for (AgentEventEntity event : after(userId,sessionId,lastId)) send(emitter,event,responseFactory);
                subscribers.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
            }
        } catch (IOException | IllegalStateException exception) { disconnect(key,emitter,exception); }
        Runnable remove=()->remove(key,emitter); emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(ignored->remove.run());
        return emitter;
    }
// 处理 `publish` 相关逻辑。
    private void publish(AgentEventEntity event) {
        SubscriptionKey key=new SubscriptionKey(event.getUserId(),event.getSessionId());
        synchronized (subscribers) {
            for (SseEmitter emitter : subscribers.getOrDefault(key,new CopyOnWriteArrayList<>())) {
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
    private void remove(SubscriptionKey key,SseEmitter emitter) { CopyOnWriteArrayList<SseEmitter> values=subscribers.get(key); if(values!=null){values.remove(emitter);if(values.isEmpty())subscribers.remove(key,values);} }
    private record SubscriptionKey(Long userId,String sessionId) {}
}
