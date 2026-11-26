package com.fandazi.feature.agent;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/** AG07进程内许可：真实对象验证并发/速率/归还，不连接模型、数据库或远端。 */
class AgentRequestAdmissionTest {
    /** 首轮缺实现命中指定断言，不将编译失败当作红测。 */
    private Object gate(AtomicLong clock,int accounts) throws Exception {
        Class<?> type;
        try {type=Class.forName("com.fandazi.feature.agent.AgentRequestAdmission");}
        catch(ClassNotFoundException absent) {fail("缺少AG07请求准入实现");return null;}
        return type.getDeclaredConstructor(boolean.class,LongSupplier.class,int.class)
            .newInstance(true,(LongSupplier)clock::get,accounts);
    }
    /** 调用生产准入，展开业务异常，避免反射包装掩盖拒绝状态。 */
    private AutoCloseable acquire(Object gate,String account,boolean control) throws Exception {
        try {return (AutoCloseable)gate.getClass().getMethod("acquire",String.class,boolean.class).invoke(gate,account,control);}
        catch(InvocationTargetException wrapped) {throw (RuntimeException)wrapped.getCause();}
    }
    /** 状态只读，用于核计数而非代替真实占用。 */
    @SuppressWarnings("unchecked") private Map<String,Object> state(Object gate) throws Exception {
        return (Map<String,Object>)gate.getClass().getMethod("snapshot").invoke(gate);
    }
    @Test void ordinaryAndControlAreIndependentAndCloseIsIdempotent() throws Exception {
        var gate=gate(new AtomicLong(100000),4096);var normal=acquire(gate,"a",false);
        assertThrows(RuntimeException.class,()->acquire(gate,"b",false));
        var one=acquire(gate,"a",true);var two=acquire(gate,"b",true);
        assertThrows(RuntimeException.class,()->acquire(gate,"c",true));
        normal.close();normal.close();one.close();two.close();assertEquals(0,state(gate).get("ordinary"));assertEquals(0,state(gate).get("control"));
    }
    @Test void accountBurstAndSlidingMinuteAreBothBounded() throws Exception {
        var clock=new AtomicLong(100000);var gate=gate(clock,4096);
        acquire(gate,"a",false).close();acquire(gate,"a",false).close();
        assertThrows(RuntimeException.class,()->acquire(gate,"a",false));
        for(long time:new long[]{110000,120000,130000,140000}) {clock.set(time);acquire(gate,"a",false).close();}
        clock.set(150000);assertThrows(RuntimeException.class,()->acquire(gate,"a",false));
        clock.set(161000);acquire(gate,"a",false).close();
    }
    @Test void capacityRejectDoesNotStealOtherAccountBurst() throws Exception {
        var gate=gate(new AtomicLong(100000),4096);var normal=acquire(gate,"a",false);
        assertThrows(RuntimeException.class,()->acquire(gate,"b",false));normal.close();
        acquire(gate,"b",false).close();acquire(gate,"b",false).close();
    }
    @Test void maintenanceAndAccountTableKeepControlOpen() throws Exception {
        var gate=gate(new AtomicLong(100000),2);acquire(gate,"a",false).close();acquire(gate,"b",false).close();
        assertThrows(RuntimeException.class,()->acquire(gate,"c",false));
        gate.getClass().getMethod("setMaintenance",boolean.class).invoke(gate,true);
        assertThrows(RuntimeException.class,()->acquire(gate,"a",false));acquire(gate,"a",true).close();
    }
}
