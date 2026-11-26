package com.fandazi.feature.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** 发布边界使用真实事务同步，只有网络发送与仓储返回为替身；不把此项当数据库持久验收。 */
class AgentEventCommitBoundaryTest {
    @Test @SuppressWarnings("unchecked") void rollback_never_sends_and_commit_sends_only_after_commit() throws Exception {
        var repository=mock(AgentEventRepository.class);
        when(repository.findByUserIdAndSessionIdOrderByIdAsc(1L,"commit-boundary")).thenReturn(List.of());
        when(repository.save(any())).thenAnswer(call->{var event=(AgentEventEntity)call.getArgument(0);ReflectionTestUtils.setField(event,"id",1L);return event;});
        var service=new AgentEventService(repository);service.subscribe(1L,"commit-boundary",null,AgentGatewayController.EventResponse::from);
        var listeners=(Map<Object,CopyOnWriteArrayList<SseEmitter>>)ReflectionTestUtils.getField(service,"subscribers");
        var emitter=mock(SseEmitter.class);listeners.values().iterator().next().clear();listeners.values().iterator().next().add(emitter);
        var source=new DriverManagerDataSource("jdbc:h2:mem:ag08-event-commit;DB_CLOSE_DELAY=-1","sa","");
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.executeWithoutResult(status->{
            service.record(1L,"commit-boundary","run_completed","合成结果",null,"succeeded",Map.of());
            verifyNoInteractions(emitter);status.setRollbackOnly();
        });
        verifyNoInteractions(emitter);
        transaction.executeWithoutResult(status->{
            service.record(1L,"commit-boundary","run_completed","合成结果",null,"succeeded",Map.of());
            verifyNoInteractions(emitter);
        });
        verify(emitter,times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }
}
