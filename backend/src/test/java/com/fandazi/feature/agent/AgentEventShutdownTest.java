package com.fandazi.feature.agent;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 应用关闭须主动结束自有SSE，不等待没有新事件的断开连接触发写错误；不启动HTTP或数据库。 */
class AgentEventShutdownTest {
    /** 一个容器上下文已结束不妨碍关闭其余订阅，关闭后禁止晚到订阅重新登记。 */
    @Test @SuppressWarnings("unchecked") void contextCloseDrainsSubscribersAndRefusesLateRegistration() {
        var repository=mock(AgentEventRepository.class);
        when(repository.findByUserIdAndSessionIdOrderByIdAsc(1L,"synthetic")).thenReturn(List.of());
        var context=new AnnotationConfigApplicationContext();
        context.registerBean(AgentEventService.class,()->new AgentEventService(repository));context.refresh();
        var service=context.getBean(AgentEventService.class);
        service.subscribe(1L,"synthetic",null,AgentGatewayController.EventResponse::from);
        var subscribers=(Map<Object,CopyOnWriteArrayList<SseEmitter>>)ReflectionTestUtils.getField(service,"subscribers");
        var broken=mock(SseEmitter.class);var healthy=mock(SseEmitter.class);
        var list=subscribers.values().iterator().next();list.clear();list.add(broken);list.add(healthy);
        doThrow(new IllegalStateException("合成已结束上下文")).when(broken).complete();
        try {context.close();} finally {if(context.isActive())context.close();}
        verify(broken).complete();verify(healthy).complete();assertTrue(subscribers.isEmpty(),"关闭后不能遗留订阅");
        var denied=assertThrows(ResponseStatusException.class,()->service.subscribe(1L,"synthetic",null,AgentGatewayController.EventResponse::from));
        assertEquals(503,denied.getStatusCode().value());
        verify(repository,times(1)).findByUserIdAndSessionIdOrderByIdAsc(1L,"synthetic");verify(repository,never()).save(any());
    }
}
