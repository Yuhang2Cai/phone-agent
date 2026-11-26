// AG06阶段观测回归：计时与日志故障不能改变业务返回、异常或调用次数。
package com.fandazi.feature.agent;

import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

/** 通过公开测量边界验证真实调用行为，不依赖日志文本或只检查类是否存在。 */
class AgentStageMetricsTest {
    /** 缺失功能以指定断言失败，避免把编译错误当作有效红测；其余验证都执行真实工具。 */
    @SuppressWarnings("unchecked")
    private <T> T measure(String stage,String trace,Supplier<T> operation,LongSupplier clock,Consumer<Map<String,Object>> sink) {
        Class<?> type=assertDoesNotThrow(() -> Class.forName("com.fandazi.feature.agent.AgentStageMetrics"),"缺少Spring安全阶段观测");
        try {
            return (T)type.getMethod("measure",String.class,String.class,Supplier.class,LongSupplier.class,Consumer.class)
                .invoke(null,stage,trace,operation,clock,sink);
        } catch(InvocationTargetException error) {
            if(error.getCause() instanceof RuntimeException runtime)throw runtime;
            throw new AssertionError(error.getCause());
        } catch(ReflectiveOperationException error) {throw new AssertionError(error);}
    }

    @Test void preservesResultAndCountsOnlyCompletedCall() {
        // 独立固定时钟给出2毫秒金标，操作只能执行一次，正文不应出现在安全字段。
        var records=new ArrayList<Map<String,Object>>();var calls=new AtomicInteger();var clock=new AtomicLong();
        int result=measure("spring.source_history","敏感关联哨兵",()->{calls.incrementAndGet();return 17;},
            ()->clock.getAndAdd(2_000_000),records::add);
        assertEquals(17,result);assertEquals(1,calls.get());assertEquals(1,records.size());
        assertEquals(2.0,records.get(0).get("durationMs"));
        assertFalse(records.toString().contains("敏感"));assertEquals("ok",records.get(0).get("status"));
    }

    @Test void failingSinkDoesNotReplaceBusinessError() {
        // 遥测接收器故障不能把原事务异常替换成日志异常，也不能重试业务。
        var original=new IllegalStateException("原业务失败");var calls=new AtomicInteger();
        var actual=assertThrows(IllegalStateException.class,()->measure("spring.task_interpret","trace",
            ()->{calls.incrementAndGet();throw original;},System::nanoTime,row->{throw new IllegalArgumentException("日志失败");}));
        assertSame(original,actual);assertEquals(1,calls.get());
    }

    @Test void invalidClockDoesNotSuppressBusinessError() {
        // 时间倒退只丢观测，不得像finally返回值那样吞掉业务异常。
        var original=new IllegalStateException("原业务失败");var counter=new AtomicInteger();
        assertSame(original,assertThrows(IllegalStateException.class,()->measure("spring.agent_run","trace",
            ()->{throw original;},()->counter.getAndIncrement()==0?10L:1L,row->fail("非法时钟不能写成零耗时"))));
    }

    @Test void unknownStageIsNotLoggedAndStillCallsBusinessOnce() {
        // 阶段目录只允许代码常量，用户输入不能进入动态标签造成正文泄露。
        var records=new ArrayList<Map<String,Object>>();
        assertEquals(19,measure("用户原文哨兵","trace",()->19,System::nanoTime,records::add));
        assertTrue(records.isEmpty());
    }
}
