package com.fandazi.feature.agent;

import java.util.*;

/**
 * 运维本机状态的安全投影；只依赖sink已有快照，不查业务库、不发模型、不新增HTTP口。
 * 计数用于观测健康，不等于业务恢复、持久化成功或并发容量证明。
 */
public final class AgentOperationsHealth {
    /** 固定允许输出字段，任意请求正文/密钥和动态标签不能进入本地状态。 */
    private static final List<String> FIELDS=List.of("accepted","written","dropped","sink_error",
        "queued","unwritten","last_flush","closed","worker_alive","enabled");
    private AgentOperationsHealth() {}

    /**
     * 复制原快照的已知安全值；缺数据保留no_data，已知损失或关闭未退出标监控降级。
     * 返回可序列化独立Map，不修改输入，也不把确认/核查状态默认为已完成。
     */
    public static Map<String,Object> project(Map<String,Object> snapshot) {
        var result=new LinkedHashMap<String,Object>();
        for(String name:FIELDS) if(snapshot.containsKey(name)) result.put(name,snapshot.get(name));
        boolean loss=positive(snapshot.get("dropped")) || positive(snapshot.get("sink_error"));
        boolean unfinished=Boolean.TRUE.equals(snapshot.get("closed")) && Boolean.TRUE.equals(snapshot.get("worker_alive"));
        // 未启用与未观测分开；控制/资源账本尚无本投影来源，不能报告健康。
        String status=Boolean.FALSE.equals(snapshot.get("enabled")) ? "disabled" :
            loss || unfinished ? "monitor_degraded" : snapshot.get("last_flush")==null ? "no_data" : "observed";
        result.put("monitor_status",status);
        result.put("control_coverage","unknown");result.put("resource_coverage","unknown");
        result.put("remote_notification","not_configured");
        return result;
    }

    /** 只认可实际非负计数；该模块不接受业务输入，非法类型保留未观测。 */
    private static boolean positive(Object value) {return value instanceof Number number && number.longValue()>0;}
}
