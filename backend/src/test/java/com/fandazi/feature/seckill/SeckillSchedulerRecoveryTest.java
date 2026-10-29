// 调度恢复时机回归：真实线程验证启动/退出维护不额外等待整个轮询周期，领域事务另由MySQL测试覆盖。
package com.fandazi.feature.seckill;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用已完成排空的替身只观察调度入口，不把本测试当成100笔积压性能结果。 */
class SeckillSchedulerRecoveryTest {
    /** 启动恢复已有到期单时立即触发首轮，不消耗一秒闲置预算。 */
    @Test void startupTriggersRecoveryWithoutWaitingForPollingInterval() {
        var worker=mock(SeckillExpiryWorker.class);when(worker.pauseAndDrain(anyLong())).thenReturn(true);
        var scheduler=new SeckillExpiryScheduler(worker,true);
        try {scheduler.start();verify(worker,timeout(500)).tick();}
        finally {scheduler.stop();}
    }

    /** 刚完成一轮后进入维护再恢复，必须立即重新查询；仍保持唯一调度线程。 */
    @Test void resumeTriggersRecoveryWithoutWaitingForOldSchedule() {
        var worker=mock(SeckillExpiryWorker.class);when(worker.pauseAndDrain(anyLong())).thenReturn(true);
        var scheduler=new SeckillExpiryScheduler(worker,true);
        try {
            scheduler.start();verify(worker,timeout(1500)).tick();
            assertThat(scheduler.pauseAndDrain(1000)).isTrue();clearInvocations(worker);
            scheduler.resume();verify(worker,timeout(500)).tick();verify(worker).resume();
        } finally {scheduler.stop();}
    }
}
