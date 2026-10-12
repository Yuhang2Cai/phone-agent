// TL-08并发验收：以真实数据库行锁竞争控制/普通输入，不能用进程内互斥代替跨实例CAS。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 合成scope彼此隔离；原生MySQL运行时验证锁序、版本、来源及唯一前台，H2仅作开发辅助。 */
@SpringBootTest(properties={"spring.datasource.url=${AG03_TEST_DB_URL:jdbc:h2:mem:ag03-concurrent;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG03_TEST_DB_DRIVER:org.h2.Driver}","spring.datasource.username=${AG03_TEST_DB_USER:sa}","spring.datasource.password=${AG03_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","agent.task-lifecycle.enabled=false"})
class AgentTaskConcurrentControlTest {
    @Autowired UserRepository users;@Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;
    @Autowired AgentTaskControlService controls;@Autowired AgentTaskTurnService turns;@Autowired JdbcTemplate jdbc;

    /** 每个并发场景建立独立真实身份，防止旧未终结命令污染下一场景。 */
    AgentTaskTestFixtures fixture(){return new AgentTaskTestFixtures(users,sessions,registry,jdbc);}

    /** 按权威目录构造同一用户按钮输入；固定原版本，用于并发而非重读后偷偷重试。 */
    Map<String,Object> request(AgentTaskTestFixtures f,Map<String,Object> task,String action,String id){
        return Map.of("requestId",id,"action",action,"targetTaskId",AgentTaskTestFixtures.id(task),
            "expectedTaskVersion",task.get("taskVersion"),"expectedFocusVersion",f.directory().get("focusVersion"));
    }

    /** 两线程同时跨事务入口，超时即失败；只把业务409转成观察值，数据库/线程错误仍使测试失败。 */
    List<Object> race(Supplier<?> first,Supplier<?> second) throws Exception {
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            var futures=new ArrayList<Future<Object>>();
            for(var action:List.of(first,second))futures.add(pool.submit(()->{
                ready.countDown();assertTrue(start.await(5,TimeUnit.SECONDS),"并发起跑屏障超时");
                try{return action.get();}catch(ResponseStatusException conflict){return conflict.getStatusCode().value();}
            }));
            assertTrue(ready.await(5,TimeUnit.SECONDS));start.countDown();
            var result=new ArrayList<Object>();for(var future:futures)result.add(future.get(15,TimeUnit.SECONDS));return result;
        }finally{pool.shutdownNow();}
    }

    /** 同来源双点击必须返回同一个持久命令，不能推进两次任务代次或登记两条来源。 */
    @Test void sameRequestHasOneCommandAndOneVersionAdvance() throws Exception {
        var f=fixture();var task=f.selection();long version=((Number)task.get("taskVersion")).longValue();
        var raw=request(f,task,"pause",UUID.randomUUID().toString());
        var result=race(()->controls.prepare(f.user,f.session,raw),()->controls.prepare(f.user,f.session,raw));
        assertEquals(result.get(0),result.get(1));assertInstanceOf(Map.class,result.get(0));
        assertEquals(version+1,((Number)registry.task(f.user,f.session,AgentTaskTestFixtures.id(task),f.epoch).get("taskVersion")).longValue());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_commands WHERE session_id=?",Integer.class,f.session));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_sources WHERE session_id=?",Integer.class,f.session));
    }

    /** 同requestId异文竞争只能接受一种决定，另一种409，不得覆写已持久输入。 */
    @Test void changedBodyWithSameIdentityIsConflict() throws Exception {
        var f=fixture();var task=f.selection();String id=UUID.randomUUID().toString();
        var pause=request(f,task,"pause",id);var cancel=request(f,task,"cancel",id);
        var result=race(()->controls.prepare(f.user,f.session,pause),()->controls.prepare(f.user,f.session,cancel));
        assertEquals(1,result.stream().filter(Map.class::isInstance).count());assertEquals(1,Collections.frequency(result,409));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_commands WHERE session_id=?",Integer.class,f.session));
    }

    /** 前台pause与切换同时竞争相同focus，只能一项预备；未收真实ACK前旧前台仍唯一且不可执行。 */
    @Test void differentControlsCannotPrepareTwoHandoffs() throws Exception {
        var f=fixture();f.seedPaused();var active=f.selection();var paused=AgentTaskTestFixtures.paused(f.directory()).get(0);
        var pause=request(f,active,"pause",UUID.randomUUID().toString());var change=request(f,paused,"switch",UUID.randomUUID().toString());
        var result=race(()->controls.prepare(f.user,f.session,pause),()->controls.prepare(f.user,f.session,change));
        assertEquals(1,result.stream().filter(Map.class::isInstance).count());assertEquals(1,Collections.frequency(result,409));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_commands WHERE session_id=?",Integer.class,f.session));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_tasks WHERE session_id=? AND lifecycle='active'",Integer.class,f.session));
    }

    /** 两个普通输入争同task执行占位，必须一受理一冲突；租约过期也不能签第二执行者。 */
    @Test void ordinaryTurnHasOneLeaseEvenAfterExpiry() throws Exception {
        var f=fixture();var task=f.selection();String taskId=AgentTaskTestFixtures.id(task);
        long version=((Number)task.get("taskVersion")).longValue(),focus=((Number)f.directory().get("focusVersion")).longValue();
        var a=new AgentTaskTurnService.Input("a","合成补槽甲",taskId,version,focus,true);
        var b=new AgentTaskTurnService.Input("b","合成补槽乙",taskId,version,focus,true);
        var result=race(()->turns.admitTurn(f.user,f.session,a),()->turns.admitTurn(f.user,f.session,b));
        assertEquals(1,result.stream().filter(AgentTaskTurnService.Accepted.class::isInstance).count());assertEquals(1,Collections.frequency(result,409));
        jdbc.update("UPDATE agent_task_turns SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE session_id=?",f.session);
        var current=AgentTaskTestFixtures.current(f.directory());
        var late=new AgentTaskTurnService.Input("late","合成迟到输入",taskId,((Number)current.get("taskVersion")).longValue(),focus,true);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->turns.admitTurn(f.user,f.session,late)).getStatusCode().value());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_turns WHERE session_id=?",Integer.class,f.session));
    }

    /** 同轮次双请求只复用turn和source，不因并发重复创建run；不进入任何业务工具。 */
    @Test void ordinarySameInputSharesTurnAndSource() throws Exception {
        var f=fixture();var task=f.selection();var input=new AgentTaskTurnService.Input("same","合成补槽",AgentTaskTestFixtures.id(task),
            ((Number)task.get("taskVersion")).longValue(),((Number)f.directory().get("focusVersion")).longValue(),true);
        var result=race(()->turns.admitTurn(f.user,f.session,input),()->turns.admitTurn(f.user,f.session,input));
        // 首次签发的Long与JSON回读的Integer类型可不同；按产品既有规范指纹比较协议值，不比较Java包装类。
        assertEquals(AgentTaskFingerprint.of(result.get(0)),AgentTaskFingerprint.of(result.get(1)));assertInstanceOf(AgentTaskTurnService.Accepted.class,result.get(0));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_turns WHERE session_id=?",Integer.class,f.session));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_sources WHERE session_id=?",Integer.class,f.session));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_executions WHERE session_id=?",Integer.class,f.session));
    }
}
