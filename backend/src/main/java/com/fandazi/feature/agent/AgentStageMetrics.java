// AG06安全阶段观测：在事务代理和外部调用边界测量，不记录正文、业务参数或凭证。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Spring内部阶段计时；只通过受信环境开启，任何遥测失败均不能替换业务结果。 */
public final class AgentStageMetrics {
    /** 固定阶段目录，用户输入不能作为动态标签写入日志。 */
    private static final Set<String> STAGES=Set.of("spring.task_source","spring.task_interpret",
        "spring.source_history","spring.agent_run","spring.history","spring.presentation","spring.complete");
    private static final Logger LOG=LoggerFactory.getLogger(AgentStageMetrics.class);
    private static final ObjectMapper JSON=new ObjectMapper();

    /** 工具类没有可变请求状态，也不持有跨请求身份、事务或ORM会话。 */
    private AgentStageMetrics() {}

    /**
     * 测量一次真实调用；默认关闭不读时钟，开启时只输出阶段、时间、状态和摘要关联键。
     * operation的返回值与异常原样传递，不自动重试、不更改提交或确认逻辑。
     */
    public static <T> T measure(String stage,String trace,Supplier<T> operation) {
        var runtime=AgentOperationsRuntime.current();
        boolean legacyEnabled=Boolean.parseBoolean(System.getenv("AG06_STAGE_METRICS_ENABLED"));
        if(!legacyEnabled && runtime==null)return operation.get();
        Consumer<Map<String,Object>> legacy=metric->{
            try {LOG.info("AG06_METRIC {}",JSON.writeValueAsString(metric));}
            catch(Exception ignored) { /* 遥测序列化或日志故障不影响业务，不打印可能含敏感数据的异常正文。 */ }
        };
        // 新观测只归属于当前显式应用作用域；保留旧格式开关和事务代理外计时语义。
        if(runtime!=null)return runtime.measure(stage,trace,operation,legacyEnabled?legacy:null);
        return measure(stage,trace,operation,System::nanoTime,legacy);
    }

    /**
     * 使用注入时钟与接收器执行同一测量流程，便于不同部署接收器复用。
     * 输入只接受固定阶段；失效时钟或接收器只丢弃观测，业务操作始终恰好执行一次。
     */
    public static <T> T measure(String stage,String trace,Supplier<T> operation,
                                LongSupplier clock,Consumer<Map<String,Object>> sink) {
        if(!STAGES.contains(stage))return operation.get();
        long started;
        try {started=clock.getAsLong();}
        catch(RuntimeException unavailable) {return operation.get();}
        boolean succeeded=false;
        try {
            T result=operation.get();succeeded=true;return result;
        } finally {
            // finally不能return或抛出日志异常，否则会吞掉、替换原业务事务异常。
            try {
                long ended=clock.getAsLong();
                if(started>=0 && ended>=started) {
                    String key=trace==null?"":HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(trace.getBytes(StandardCharsets.UTF_8))).substring(0,24);
                    sink.accept(Map.of("processId",ProcessHandle.current().pid(),"stage",stage,"traceKey",key,
                        "startNs",started,"endNs",ended,"durationMs",(ended-started)/1_000_000.0,
                        "status",succeeded?"ok":"error"));
                }
            } catch(Exception ignored) { /* 无效读数不构造零耗时，也不干预原异常。 */ }
        }
    }
}
