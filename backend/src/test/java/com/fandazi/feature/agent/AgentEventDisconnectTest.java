// SSE断连回归：订阅通知失败不得回滚已记录事件或中断同会话的业务HTTP响应。
package com.fandazi.feature.agent;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 只模拟底层断开连接，实际发布、移除和持久调用使用生产事件服务。 */
class AgentEventDisconnectTest {
    /** 发送及容器清理可能均失败；坏订阅只移除一次，其他订阅与后续记录继续工作。 */
    @ParameterizedTest @ValueSource(booleans={false,true})
    @SuppressWarnings("unchecked")
    void disconnectedSubscriberCannotFailRecordOrOtherSubscribers(boolean alreadyComplete) throws Exception {
        AgentEventRepository repository=mock(AgentEventRepository.class);
        when(repository.findByUserIdAndSessionIdOrderByIdAsc(17L,"synthetic-disconnect")).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(call -> {
            // 固定合成事件ID只为构建SSE，不伪造业务执行或真实数据库结果。
            AgentEventEntity event=call.getArgument(0);ReflectionTestUtils.setField(event,"id",99L);return event;
        });
        AgentEventService service=new AgentEventService(repository);
        service.subscribe(17L,"synthetic-disconnect",null,AgentGatewayController.EventResponse::from);
        Map<Object,CopyOnWriteArrayList<SseEmitter>> subscriptions=(Map<Object,CopyOnWriteArrayList<SseEmitter>>)ReflectionTestUtils.getField(service,"subscribers");
        SseEmitter broken=mock(SseEmitter.class),healthy=mock(SseEmitter.class);
        var listeners=subscriptions.values().iterator().next();listeners.clear();listeners.add(broken);listeners.add(healthy);
        if(alreadyComplete)doThrow(new IllegalStateException("合成订阅已结束")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        else doThrow(new IOException("合成断连")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        // Tomcat出错回调已经返回时completeWithError也会抛异常，不能让清理再次影响业务事务。
        doThrow(new IllegalStateException("合成容器已清理")).when(broken).completeWithError(any());
        assertThatCode(() -> service.record(17L,"synthetic-disconnect","completed","已取消",null,"cancelled",Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> service.record(17L,"synthetic-disconnect","completed","已结束",null,"succeeded",Map.of())).doesNotThrowAnyException();
        verify(repository,times(2)).save(any());verify(broken,times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(healthy,times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
