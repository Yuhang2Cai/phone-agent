// AG08业务请求时限：在入口建立一次截止，归属解释与后续运行共用，不向模型或客户端开放预算字段。
package com.fandazi.feature.agent;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** 同步Servlet线程内持有单调截止；只有签发内部JWT时才传递对应绝对时间。 */
final class AgentRequestBudget implements AutoCloseable {
    static final String CLAIM="ag08DeadlineEpochMs";
    /** 可信传输声明名称，不属于公开业务Schema或执行许可。 */
    private record Deadline(long nanos,long epochMillis) {}
    private static final ThreadLocal<Deadline> CURRENT=new ThreadLocal<>();
    private final Deadline previous;

    /** 嵌套服务复用原截止；输入只来自部署或组件测试，不能读取公共请求头。 */
    private AgentRequestBudget(long millis) {
        if(millis<=0||millis>120000)throw new IllegalArgumentException("请求预算须在1至120000毫秒内");
        previous=CURRENT.get();
        if(previous==null)CURRENT.set(new Deadline(System.nanoTime()+millis*1000000,System.currentTimeMillis()+millis));
    }
    /** 取得本线程作用域，必须用try-with-resources恢复，不能跨异步线程借用。 */
    static AgentRequestBudget open(long millis){return new AgentRequestBudget(millis);}
    /** 仅内部客户端选择有界传输时使用；离线旧调用方不隐式创建新预算。 */
    static boolean active(){return CURRENT.get()!=null;}
    /** 返回阶段上限与请求剩余的较小值；耗尽直接失败，禁止零被网络库解释成无限等待。 */
    static Duration remaining(long stageMillis){
        var value=CURRENT.get();long remaining=value==null?stageMillis*1000000:value.nanos()-System.nanoTime();
        if(remaining<=0)throw new AgentServiceUnavailableException("请求已到截止时间，请核查原请求结果");
        return Duration.ofNanos(Math.min(stageMillis*1000000,remaining));
    }
    /** 给原签名载荷附加服务端期限；原map不变，所有阶段引用同一绝对值。 */
    static Map<String,Object> claims(Map<String,Object> original){
        var result=new LinkedHashMap<>(original);var value=CURRENT.get();
        if(value!=null){remaining(120000);result.put(CLAIM,value.epochMillis());}
        return result;
    }
    /** 归还入口作用域，不影响其它请求线程，也不取消已发生的业务事实。 */
    @Override public void close(){if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
}
