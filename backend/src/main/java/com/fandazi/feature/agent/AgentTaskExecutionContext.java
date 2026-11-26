// 领域授权上下文模块：仅在已验证栅栏的同线程短事务内绑定task，退出后必定清理。
package com.fandazi.feature.agent;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** 不从HTTP/model自行设置上下文；v2桥校验许可后进入，既有领域签发/消费复用该绑定。 */
@Component
class AgentTaskExecutionContext {
    private final ThreadLocal<Map<String,Object>> current=new ThreadLocal<>();
    /** 返回线程内可信执行栅栏；旧领域入口无上下文，不能消费任务凭证。 */
    Map<String,Object> current(){return current.get();}
    /** 包围唯一领域分发；异常同样清理，禁止线程池复用时串入另一用户的任务。 */
    <T> T within(Map<String,Object> fence,Supplier<T> work){
        if(current.get()!=null)throw new IllegalStateException("任务执行上下文禁止嵌套替换");
        current.set(Collections.unmodifiableMap(new LinkedHashMap<>(fence)));
        try{return work.get();}finally{current.remove();}
    }
}
