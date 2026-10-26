package com.fandazi.feature.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 日志IO只触及自有临时目录，验证有界队列、真实轮换、失败与关闭回收。 */
class AgentOperationsSinkTest {
    @TempDir Path root;

    /** 指定缺失断言后构造测试限额；反射异常还原以验证真实失败类别。 */
    Object sink(int capacity,int bytes,int files) {
        Class<?> type=assertDoesNotThrow(()->Class.forName("com.fandazi.feature.agent.AgentOperationsSink"),"缺少AG07有界接收器");
        return assertDoesNotThrow(()->type.getConstructor(Path.class,int.class,int.class,int.class).newInstance(root,capacity,bytes,files));
    }
    /** 调用公开接口，保留原实现异常，不吞掉失败。 */
    Object call(Object sink,String name,Class<?>[] signature,Object... args) {
        try{return sink.getClass().getMethod(name,signature).invoke(sink,args);}
        catch(InvocationTargetException error){throw new AssertionError(error.getCause());}
        catch(ReflectiveOperationException error){throw new AssertionError(error);}
    }
    /** 固定合成事件交给产品入口执行过滤，不测试模拟日志写入。 */
    boolean offer(Object sink) {return (boolean)call(sink,"offer",new Class<?>[]{Map.class},Map.of("eventType","stage","stage","spring.history"));}
    /** 关闭返回实际资源统计，不能以close调用返回代替线程确实停止。 */
    Map<?,?> close(Object sink) {return (Map<?,?>)call(sink,"close",new Class<?>[]{long.class},5000L);}

    @Test void queueFullRejectsAndCloseReportsUnwritten() {
        // 未start不启动后台线程，容量2时第三条直接拒绝。
        Object sink=sink(2,4096,3);assertTrue(offer(sink));assertTrue(offer(sink));assertFalse(offer(sink));
        var state=close(sink);assertEquals(1L,state.get("dropped"));assertEquals(2L,state.get("unwritten"));
        assertEquals(false,state.get("worker_alive"));assertFalse(offer(sink));
    }
    @Test void actualRotationAndCloseKeepAudit() throws Exception {
        // 小限额走真实轮换；原审计不在可删范围，线程退出后文件可继续读取。
        Files.writeString(root.resolve("business-audit.log"),"保留");Object sink=sink(32,1600,3);
        for(int i=0;i<12;i++)assertTrue(offer(sink));call(sink,"start",new Class<?>[]{});
        var state=close(sink);assertEquals(12L,state.get("written"));assertEquals(0L,state.get("unwritten"));assertEquals(false,state.get("worker_alive"));
        try(var stream=Files.list(root)) {var paths=stream.filter(p->p.toString().endsWith(".jsonl")).toList();
            assertTrue(paths.size()<=3);for(var path:paths)assertTrue(Files.size(path)<=1600);}
        assertEquals("保留",Files.readString(root.resolve("business-audit.log")));
    }
    @Test void unwritableRootReportsFailureAndReleasesWorker() throws Exception {
        // 根路径被自有文件占用，不修改系统权限或真实日志目录。
        root=root.resolve("blocked");Files.writeString(root,"占用");Object sink=sink(2,4096,3);
        assertTrue(offer(sink));call(sink,"start",new Class<?>[]{});var state=close(sink);
        assertTrue((Long)state.get("sink_error")>=1);assertEquals(1L,state.get("unwritten"));assertEquals(false,state.get("worker_alive"));
    }

    @Test void idleWorkerExpiresOldOwnedFilesAndMarksConsumerStopped() throws Exception {
        // 不发送业务事件也必须执行保留期；收尾消费状态与线程真实退出分别核对。
        var old=root.resolve("events.jsonl");Files.writeString(old,"旧事件");
        Files.setLastModifiedTime(old,java.nio.file.attribute.FileTime.fromMillis(1));
        var audit=root.resolve("business-audit.log");Files.writeString(audit,"保留");
        Object sink=sink(2,4096,3);call(sink,"start",new Class<?>[]{});var state=close(sink);
        assertFalse(Files.exists(old));assertEquals("保留",Files.readString(audit));
        var status=new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(root.resolve("status.json")));
        assertTrue(status.get("consumer_stopped").asBoolean());assertEquals(false,state.get("worker_alive"));
    }

    @Test void zeroTimeoutDoesNotMeanUnboundedJoin() throws Exception {
        // 关闭零预算返回真实状态，不用Thread.join(0)造成无限等待；随后收束本对象。
        Object sink=sink(2,4096,3);assertTrue(offer(sink));call(sink,"start",new Class<?>[]{});
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),()->call(sink,"close",new Class<?>[]{long.class},0L));
        assertEquals(false,close(sink).get("worker_alive"));
    }
}
