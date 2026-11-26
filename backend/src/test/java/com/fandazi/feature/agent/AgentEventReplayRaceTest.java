package com.fandazi.feature.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 重连历史读取与实时提交竞态：仓储等待模拟数据库占用，不以此替代MySQL事务验收。 */
class AgentEventReplayRaceTest {
    /** 历史等待期间实时提交必须完成；同一事件同时出现在历史与缓冲时只能投影一次。 */
    @Test void pending_replay_does_not_block_commit_and_deduplicates_buffer() throws Exception {
        var repository=mock(AgentEventRepository.class);
        var service=new AgentEventService(repository);
        var waiting=new CountDownLatch(1);var release=new CountDownLatch(1);
        var event=new AgentEventEntity(1L,"race","progress","合成进度",null,"running",Map.of());
        ReflectionTestUtils.setField(event,"id",1L);
        when(repository.save(any())).thenReturn(event);
        when(repository.findByUserIdAndSessionIdOrderByIdAsc(1L,"race")).thenAnswer(call->{
            waiting.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return List.of(event);
        });
        var sent=new AtomicInteger();var executor=Executors.newFixedThreadPool(2);
        try {
            var subscribe=executor.submit(()->service.subscribe(1L,"race",null,value->{sent.incrementAndGet();return Map.of("id",value.getId());}));
            assertTrue(waiting.await(3,TimeUnit.SECONDS));
            executor.submit(()->service.record(1L,"race","progress","合成进度",null,"running",Map.of())).get(2,TimeUnit.SECONDS);
            assertEquals(0,sent.get(),"历史未校验前不得抢先外发缓冲");
            release.countDown();subscribe.get(3,TimeUnit.SECONDS);
            assertEquals(1,sent.get(),"同一持久主键只应重放一次");
        } finally {release.countDown();service.closeSubscriptions();executor.shutdownNow();}
    }

    /** 历史读取发生非IllegalState数据库异常时，也不能泄漏预登记订阅和缓冲。 */
    @Test void failed_history_read_removes_pending_subscription() {
        var repository=mock(AgentEventRepository.class);var service=new AgentEventService(repository);
        when(repository.findByUserIdAndSessionIdOrderByIdAsc(1L,"broken")).thenThrow(new org.springframework.dao.QueryTimeoutException("合成超时"));
        assertThrows(org.springframework.dao.QueryTimeoutException.class,()->service.subscribe(1L,"broken",null,value->value));
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(service,"subscribers")).isEmpty());
        assertTrue(((Map<?,?>)ReflectionTestUtils.getField(service,"replayBuffers")).isEmpty());
    }
}
