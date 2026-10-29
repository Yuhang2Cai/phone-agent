// 秒杀后台装配回归：不手工调用tick或scanOnce，验证默认关闭Agent时仍实际产生定时扫描。
package com.fandazi.feature.seckill;

import com.fandazi.feature.grouporder.GroupBuyOrderRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 只替代持久入口以观察真实调度线程；领域释放和真实300秒另用实际MySQL验证。 */
@SpringBootTest(properties={"seed.enabled=false","group.seed.enabled=false","seckill.expiry.enabled=true",
        "agent.task-lifecycle.enabled=false","spring.datasource.url=jdbc:h2:mem:seckill-scheduler;MODE=MySQL;DB_CLOSE_DELAY=-1"})
class SeckillSchedulingTest {
    @Autowired SeckillExpiryScheduler scheduler;
    @MockitoBean GroupBuyOrderRepository orders;
    @MockitoBean SeckillExpiryService expiry;
    @MockitoBean SeckillTimeSource time;

    /** 未开启Agent时，3.5秒内仍自动处理候选，且不能与Agent慢请求共用调度线程。 */
    @Test void expiryRunsWithoutAgentSchedulingSwitch() throws Exception {
        var thread=new AtomicReference<String>();
        when(time.now()).thenReturn(Instant.now());
        when(orders.expiredSeckillIds(any(),any())).thenReturn(List.of("scheduled-order"));
        when(expiry.expire("scheduled-order")).thenAnswer(call->{thread.set(Thread.currentThread().getName());return true;});
        verify(expiry,timeout(3500).atLeastOnce()).expire("scheduled-order");
        assertThat(thread.get()).startsWith("seckill-expiry-");
        // 维护证明必须包含登记身份及真实领域门禁；停写后即使计时器仍存活也不能访问结算服务。
        assertThat(scheduler.snapshot().get("writerId")).isEqualTo("seckill-expiry");
        assertThat(scheduler.pauseAndDrain(1000)).isTrue();
        clearInvocations(expiry);
        Thread.sleep(1200);
        verifyNoInteractions(expiry);
        // 恢复后的首轮查询异常不能永久取消定时任务，后一轮须继续补偿原候选。
        when(orders.expiredSeckillIds(any(),any())).thenThrow(new IllegalStateException("合成查询故障")).thenReturn(List.of("scheduled-order"));
        scheduler.resume();
        verify(expiry,timeout(3500).atLeastOnce()).expire("scheduled-order");
    }
}
