package com.fandazi.feature.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实线程与文件验证应用归属、作用域传播和生命周期，不启动数据库或模型。 */
class AgentOperationsRuntimeTest {
    @TempDir Path root;

    /** 指定功能缺失红测，反射只用于避免把编译错误算作断言。 */
    Object runtime(Path path, boolean enabled) throws Exception {
        Class<?> cls=assertDoesNotThrow(()->Class.forName("com.fandazi.feature.agent.AgentOperationsRuntime"),
            "缺少应用拥有的AG07生命周期接入");
        var env=new MockEnvironment().withProperty("AG07_OPERATIONS_ENABLED",String.valueOf(enabled))
            .withProperty("AG07_OPERATIONS_ROOT",path.toString()).withProperty("SPRING_BUILD_VERSION","1234567");
        return cls.getConstructor(org.springframework.core.env.Environment.class).newInstance(env);
    }
    /** 反射调用发生的产品异常保留真实原因，绝不吞错写成通过。 */
    Object call(Object target,String name,Class<?>[] signature,Object... args) throws Exception {
        try {return target.getClass().getMethod(name,signature).invoke(target,args);}
        catch(java.lang.reflect.InvocationTargetException error) {
            if(error.getCause() instanceof RuntimeException runtime)throw runtime;
            throw new AssertionError(error.getCause());
        }
    }
    /** 读取本实例全部事件，关闭后核精确执行事实而不只核方法返回。 */
    List<Map> records(Path path) throws Exception {
        var rows=new ArrayList<Map>();
        try(var files=Files.walk(path)) {
            for(Path file:files.filter(p->p.toString().endsWith(".jsonl")).toList())
                for(String line:Files.readAllLines(file))rows.add(new com.fasterxml.jackson.databind.ObjectMapper().readValue(line,Map.class));
        }
        return rows;
    }

    @Test void disabledRuntimeCreatesNoResources() throws Exception {
        // 默认关闭的应用不创建目录和线程，兼容既有测试/部署。
        Path path=root.resolve("disabled");Object runtime=runtime(path,false);
        call(runtime,"start",new Class<?>[]{});call(runtime,"close",new Class<?>[]{});
        assertFalse(Files.exists(path));
    }
    @Test void scopesPropagateToOwnedThreadAndDoNotLeakToOtherApp() throws Exception {
        // 嵌套与显式跨线程传播带父span，两个应用独立；离开作用域后不继续写旧实例。
        Object first=runtime(root.resolve("one"),true), second=runtime(root.resolve("two"),true);
        call(first,"start",new Class<?>[]{});call(second,"start",new Class<?>[]{});
        try {
            call(first,"run",new Class<?>[]{Runnable.class},(Runnable)()->AgentStageMetrics.measure("spring.history","random-a",()->{
                try {
                    Runnable task=(Runnable)call(first,"capture",new Class<?>[]{Runnable.class},(Runnable)()->
                        AgentStageMetrics.measure("spring.complete","random-a",()->17));
                    Thread thread=new Thread(task);thread.start();thread.join(2000);assertFalse(thread.isAlive());
                    call(second,"run",new Class<?>[]{Runnable.class},(Runnable)()->
                        AgentStageMetrics.measure("spring.history","random-b",()->18));
                } catch(Exception failure) {throw new AssertionError(failure);}
                return 19;
            }));
            AgentStageMetrics.measure("spring.history","outside",()->20);
        } finally {call(first,"close",new Class<?>[]{});call(second,"close",new Class<?>[]{});}
        var one=records(root.resolve("one"));var two=records(root.resolve("two"));
        assertEquals(2,one.size());assertEquals(1,two.size());
        Map parent=one.stream().filter(x->x.get("stage").equals("spring.history")).findFirst().orElseThrow();
        Map child=one.stream().filter(x->x.get("stage").equals("spring.complete")).findFirst().orElseThrow();
        assertEquals(parent.get("spanId"),child.get("parentSpanId"));
        assertNotEquals(parent.get("instanceId"),two.get(0).get("instanceId"));
    }
    @Test void businessExceptionPreservedWhenSinkCannotWrite() throws Exception {
        // 文件占据自有根导致日志失败，原业务异常仍是同一个对象且不会重试。
        Path path=root.resolve("blocked");Files.writeString(path,"占用");
        Object runtime=runtime(path,true);call(runtime,"start",new Class<?>[]{});
        var expected=new IllegalStateException("业务失败");var calls=new java.util.concurrent.atomic.AtomicInteger();
        try {
            var actual=assertThrows(IllegalStateException.class,()->call(runtime,"run",new Class<?>[]{Runnable.class},
                (Runnable)()->AgentStageMetrics.measure("spring.history","random-c",()->{calls.incrementAndGet();throw expected;})));
            assertSame(expected,actual);assertEquals(1,calls.get());
        } finally {call(runtime,"close",new Class<?>[]{});}
    }
}
