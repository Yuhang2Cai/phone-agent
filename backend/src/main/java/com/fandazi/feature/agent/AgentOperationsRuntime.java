package com.fandazi.feature.agent;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 应用拥有的运维资源与作用域；默认关闭，启动/销毁由Spring生命周期负责。
 * ThreadLocal仅保存当前显式作用域，退出必恢复；跨线程必须capture，不使用继承线程变量。
 */
@Component
public final class AgentOperationsRuntime {
    /** 不可变作用域快照；只含资源归属和随机span，不持有用户/会话或业务实体。 */
    private record Frame(AgentOperationsRuntime runtime, String spanId) {}
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private final AgentOperationsSink sink;
    private final Map<String,Object> identity;

    /** 只读取受信部署环境；非法启用配置明确拒绝，关闭时不创建线程/目录。 */
    public AgentOperationsRuntime(Environment environment) {
        String enabled=environment.getProperty("AG07_OPERATIONS_ENABLED","false");
        if(!enabled.equalsIgnoreCase("true") && !enabled.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("invalid_operations_switch");
        if(!Boolean.parseBoolean(enabled)) {sink=null;identity=Map.of();return;}
        String configured=environment.getProperty("AG07_OPERATIONS_ROOT");
        if(configured==null || !Path.of(configured).isAbsolute())
            throw new IllegalArgumentException("private_operations_root_required");
        String instance=UUID.randomUUID().toString();
        identity=Map.of("service","spring","instanceId",instance,
            "releaseId",environment.getProperty("SPRING_BUILD_VERSION","unknown"));
        AgentOperationsEvent.make("heartbeat",identity);
        sink=new AgentOperationsSink(Path.of(configured).resolve(instance),256,20*1024*1024,5);
    }

    /** 应用初始化显式启用自有worker；构造/类加载本身不启动资源。 */
    @PostConstruct public void start() {if(sink!=null)sink.start();}

    /** 应用销毁有界等待；未写/超时状态保留在sink，不能强杀别的应用线程。 */
    @PreDestroy public void close() {if(sink!=null)sink.close(5000);}

    /** 返回本应用观测状态；默认关闭明确enabled=false，不伪造完整观测。 */
    public Map<String,Object> snapshot() {
        if(sink==null)return Map.of("enabled",false);
        var state=sink.snapshot();state.put("enabled",true);return state;
    }

    /** 在当前线程绑定本应用；业务异常原样抛出，退出后还原上层作用域。 */
    public void run(Runnable work) {
        try(var scope=openScope()) {work.run();}
    }

    /** 供Servlet的受检异常链使用；作用域资源close不抛受检异常，原请求错误不受影响。 */
    public Scope openScope() {
        Frame previous=CURRENT.get();
        if(sink==null)CURRENT.remove();else CURRENT.set(new Frame(this,null));
        return new Scope(previous);
    }

    /** 同线程幂等恢复句柄；不能从其他线程移除当前请求的作用域。 */
    public static final class Scope implements AutoCloseable {
        private final Frame previous;
        private final Thread owner=Thread.currentThread();
        private boolean restored;
        private Scope(Frame previous) {this.previous=previous;}
        /** 仅恢复创建句柄时的上下文，不关闭应用sink或执行任何业务。 */
        @Override public void close() {
            if(Thread.currentThread()!=owner)throw new IllegalStateException("scope_thread_mismatch");
            if(!restored) {restored=true;restore(previous);}
        }
    }

    /** 捕获当前本应用作用域用于自有线程；拒绝从其他应用偷取上下文。 */
    public Runnable capture(Runnable work) {
        Frame captured=CURRENT.get();
        if(captured!=null && captured.runtime()!=this)throw new IllegalStateException("foreign_operations_scope");
        return ()->{
            Frame previous=CURRENT.get();
            try {restore(captured);work.run();}
            finally {restore(previous);}
        };
    }

    /** 只向当前显式绑定的应用返回资源，不建立跨应用静态全局sink。 */
    static AgentOperationsRuntime current() {
        Frame frame=CURRENT.get();return frame==null?null:frame.runtime();
    }

    /** 原阶段观测适配器入口；新旧接收器异常彼此隔离，不改变原业务计数。 */
    <T> T measure(String stage,String trace,Supplier<T> operation,Consumer<Map<String,Object>> legacy) {
        Frame parent=CURRENT.get();
        String span=UUID.randomUUID().toString();
        CURRENT.set(new Frame(this,span));
        try {
            return AgentStageMetrics.measure(stage,trace,operation,System::nanoTime,metric->{
                // 老AG06格式继续交给原接收器；新sink仅重建安全字段，不复制旧计时原值。
                if(legacy!=null)try {legacy.accept(metric);}catch(RuntimeException ignored) {}
                try {
                    var fields=new LinkedHashMap<String,Object>(identity);
                    fields.put("stage",metric.get("stage"));fields.put("status",metric.get("status"));
                    fields.put("durationMs",metric.get("durationMs"));
                    fields.put("traceKey",trace==null?null:AgentOperationsEvent.identityKey(trace));
                    fields.put("spanId",span);fields.put("parentSpanId",parent==null?null:parent.spanId());
                    sink.offer(AgentOperationsEvent.make("stage",fields));
                } catch(RuntimeException invalid) {
                    // 以非法安全事件计丢弃，不打印传入字段或原异常，原业务仍照常返回/抛错。
                    sink.offer(Map.of("eventType","invalid"));
                }
            });
        } finally {restore(parent);}
    }

    /** 删除空作用域，避免线程池线程永久保留已经关闭的应用资源。 */
    private static void restore(Frame frame) {if(frame==null)CURRENT.remove();else CURRENT.set(frame);}
}
