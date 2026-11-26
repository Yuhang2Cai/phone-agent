// 任务事件投递模块：短事务认领与确认，消费者在事务外执行；失败只重投原事件身份。
package com.fandazi.feature.agent;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** 投递器不执行任务/业务工具；界面订阅接入时复用同一outbox，不另造业务完成事实。 */
@Service
public class AgentTaskEventDispatcher {
    private final AgentTaskEventOutbox outbox;
    /** 注入代理服务以确保认领事务在进入Consumer前已经结束。 */
    public AgentTaskEventDispatcher(AgentTaskEventOutbox outbox){this.outbox=outbox;}
    /** 有界投递一批；异常保留pending，消费成功但回执丢失允许同ID再次发送。 */
    public void deliverOnce(Consumer<Map<String,Object>> consumer) {
        for(Long id:outbox.pending()) {
            String owner=UUID.randomUUID().toString();var event=outbox.claim(id,owner,Instant.now());if(event==null)continue;
            boolean success=false;
            try {consumer.accept(event);success=true;}catch(RuntimeException failure){/* 消费失败不影响已提交任务，只保留事件等待重投。 */}
            finally {outbox.finish(id,owner,success);}
        }
    }
}
