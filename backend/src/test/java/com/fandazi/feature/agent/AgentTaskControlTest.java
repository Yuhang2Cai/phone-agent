// 持久控制组件验收：真实H2事务/锁/迁移，明确构造Agent回执而不冒称真实跨服务停点。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.UserRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** 金标固定为版本只推进一次、后台取消不改焦点、无安全证据不提交。 */
@SpringBootTest(properties={"spring.datasource.url=${AG03_TEST_DB_URL:jdbc:h2:mem:ag03-control;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG03_TEST_DB_DRIVER:org.h2.Driver}","spring.datasource.username=${AG03_TEST_DB_USER:sa}","spring.datasource.password=${AG03_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","agent.task-lifecycle.enabled=false"})
class AgentTaskControlTest {
    @Autowired UserRepository users;@Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;
    @Autowired AgentTaskControlService controls;@Autowired JdbcTemplate jdbc;@Autowired AgentTaskEventOutbox events;
    @Autowired AgentTaskTurnService turnService;
    @Autowired AgentTaskEventDispatcher dispatcher;
    private Map<String,Long> businessBefore;
    /** 每例核对准备/执行/业务表，而非用HTTP200推断控制没有业务副作用。 */
    private Map<String,Long> businessCounts(){var result=new LinkedHashMap<String,Long>();for(String table:List.of("purchase_quotes","confirmation_grants","agent_tool_executions","group_buy_orders","refund_requests","complaint_tickets"))
        result.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));return result;}
    /** 冻结本例业务计数；合成身份与任务注册不计为业务写。 */
    @BeforeEach void recordBusinessCounts(){businessBefore=businessCounts();}
    /** 控制、租约、回执、事件均不得准备交易或执行业务写。 */
    @AfterEach void requireNoBusinessDelta(){assertEquals(businessBefore,businessCounts(),"控制组件业务增量必须为零");}
    /** 每例使用新合成scope，不依赖测试运行顺序或上一次数据库状态。 */
    AgentTaskTestFixtures fixture(){return new AgentTaskTestFixtures(users,sessions,registry,jdbc);}
    /** 按公开目录冻结CAS输入；请求正文改变必须被幂等层拒绝。 */
    Map<String,Object> request(AgentTaskTestFixtures f,Map<String,Object> task,String action){return Map.of("requestId",UUID.randomUUID().toString(),"action",action,
        "targetTaskId",AgentTaskTestFixtures.id(task),"expectedTaskVersion",task.get("taskVersion"),"expectedFocusVersion",f.directory().get("focusVersion"));}
    /** 控制边界替身只依据已签发目标返回检查点；不执行任何业务工具。 */
    Map<String,Object> ack(Map<String,Object> wire,String status,String outcome){
        @SuppressWarnings("unchecked") var targets=(List<Map<String,Object>>)wire.get("targets");
        var receipts=new ArrayList<Map<String,Object>>();for(var target:targets){var r=new LinkedHashMap<String,Object>();
            r.put("conversationTaskId",target.get("conversationTaskId"));r.put("runId",target.get("runId"));r.put("checkpointVersion",1);receipts.add(r);}
        var value=new LinkedHashMap<String,Object>();value.put("schemaVersion","2.2");value.put("commandId",wire.get("commandId"));value.put("scope",wire.get("scope"));
        value.put("status",status);value.put("writeOutcome",outcome);value.put("checkpoints",receipts);value.put("reasonCode",null);return value;
    }
    /** 认领不是执行，调用方必须在短事务结束之后发送网络请求。 */
    Map<String,Object> claim(AgentTaskTestFixtures f,String id){return controls.claim(f.user,f.session,id,"synthetic-owner",Instant.now().plusSeconds(1));}
    @Test void staleAndRepeatedRequestsPreserveVersions(){
        // S06：错误版本不改任何目录；同键同文只预备一次，异文409。
        var f=fixture();var task=f.selection();var before=f.directory();var raw=request(f,task,"pause");
        var bad=new LinkedHashMap<>(raw);bad.put("expectedTaskVersion",999L);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->controls.prepare(f.user,f.session,bad)).getStatusCode().value());
        assertEquals(before,f.directory());var first=controls.prepare(f.user,f.session,raw);var prepared=f.directory();
        assertEquals(first,controls.prepare(f.user,f.session,raw));assertEquals(prepared,f.directory());
        bad.putAll(raw);bad.put("action","cancel");assertEquals(409,assertThrows(ResponseStatusException.class,()->controls.prepare(f.user,f.session,bad)).getStatusCode().value());
        assertEquals(prepared,f.directory());
    }
    @Test void pauseRequiresAckAndPreservesStop(){
        // S07/S10：预备仍active/原停点，独立持久ACK后才提交paused，重复commit不重复事件。
        var f=fixture();var task=f.selection();var run=registry.beginRun(f.user,f.session,AgentTaskTestFixtures.id(task));
        task=registry.recordRunStop(f.user,f.session,AgentTaskTestFixtures.id(task),run.id(),"waiting_input","budget",null);
        var command=controls.prepare(f.user,f.session,request(f,task,"pause"));String id=(String)command.get("commandId");
        assertEquals("active",AgentTaskTestFixtures.current(f.directory()).get("lifecycle"));
        assertEquals(task.get("stopPoint"),AgentTaskTestFixtures.current(f.directory()).get("stopPoint"));
        assertThrows(ResponseStatusException.class,()->controls.commit(f.user,f.session,id));
        var wire=claim(f,id);var safe=ack(wire,"checkpoint_saved","none");controls.acknowledge(f.user,f.session,id,safe);
        assertEquals("agent_acknowledged",controls.read(f.user,f.session,id).get("state"));
        assertEquals("committed",controls.commit(f.user,f.session,id).get("state"));var after=f.directory();
        assertNull(after.get("currentTask"));assertEquals(task.get("stopPoint"),AgentTaskTestFixtures.paused(after).get(0).get("stopPoint"));
        controls.commit(f.user,f.session,id);controls.acknowledge(f.user,f.session,id,safe);assertEquals(after,f.directory());
    }
    @Test void cancelBackgroundDoesNotChangeForeground(){
        // S22：取消后台只更改该task，不改前台版本、焦点、trace和待确认归属。
        var f=fixture();String paused=f.seedPaused();var active=f.selection();
        jdbc.update("UPDATE agent_sessions SET pending_confirmation_task_id=?,trace_id=? WHERE session_id=?",AgentTaskTestFixtures.id(active),"front-trace",f.session);
        var before=f.directory();var command=controls.prepare(f.user,f.session,request(f,AgentTaskTestFixtures.paused(before).get(0),"cancel"));
        String id=(String)command.get("commandId");controls.acknowledge(f.user,f.session,id,ack(claim(f,id),"checkpoint_saved","none"));controls.commit(f.user,f.session,id);
        var after=f.directory();assertEquals(before.get("focusVersion"),after.get("focusVersion"));assertEquals(before.get("currentTask"),after.get("currentTask"));
        assertEquals(AgentTaskTestFixtures.id(active),jdbc.queryForObject("SELECT pending_confirmation_task_id FROM agent_sessions WHERE session_id=?",String.class,f.session));
        assertEquals("front-trace",jdbc.queryForObject("SELECT trace_id FROM agent_sessions WHERE session_id=?",String.class,f.session));
        assertTrue(AgentTaskTestFixtures.paused(after).isEmpty());assertEquals("cancelled",registry.task(f.user,f.session,paused,f.epoch).get("lifecycle"));
    }
    @Test void alteredGenerationCannotCommitSavedAck(){
        // 接收后其他写入篡改代次必须拦截，pending命令ID相同不能代替版本核验。
        var f=fixture();var task=f.selection();var c=controls.prepare(f.user,f.session,request(f,task,"pause"));String id=(String)c.get("commandId");
        controls.acknowledge(f.user,f.session,id,ack(claim(f,id),"checkpoint_saved","none"));
        jdbc.update("UPDATE agent_conversation_tasks SET execution_generation=execution_generation+1 WHERE conversation_task_id=?",AgentTaskTestFixtures.id(task));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->controls.commit(f.user,f.session,id),"保存ACK后仍须核验原代次").getStatusCode().value());
    }
    @Test void tailGapMustRequireDirectoryReload(){
        // S24：已发水位大于最后保留行时不能把缺失尾事件当成功空批次。
        var f=fixture();var c=controls.prepare(f.user,f.session,request(f,f.selection(),"pause"));
        long watermark=((Number)f.directory().get("throughTaskEventId")).longValue();
        jdbc.update("DELETE FROM agent_task_event_outbox WHERE session_id=? AND task_event_id=?",f.session,watermark);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->events.read(f.user,f.session,0,100),"尾部事件缺口不能漏报").getStatusCode().value());
    }
    @Test void ordinaryConfirmationMustNotPrepareControl(){
        // S25/S22：普通确认取得单执行租约，task/focus/generation与自身待确认摘要保持原值。
        var f=fixture();var task=f.selection();var run=registry.beginRun(f.user,f.session,AgentTaskTestFixtures.id(task));
        task=registry.recordRunStop(f.user,f.session,AgentTaskTestFixtures.id(task),run.id(),"waiting_confirmation",null,null);
        String taskId=AgentTaskTestFixtures.id(task);jdbc.update("UPDATE agent_sessions SET pending_confirmation_task_id=? WHERE session_id=?",taskId,f.session);
        var before=f.directory();var input=new AgentTaskTurnService.Input("confirm-once","确认",taskId,((Number)task.get("taskVersion")).longValue(),((Number)before.get("focusVersion")).longValue(),true);
        var first=assertDoesNotThrow(()->turnService.admitTurn(f.user,f.session,input),"普通确认应受理而非控制预备");assertNotNull(first.admission());
        assertEquals(AgentTaskFingerprint.of(first),AgentTaskFingerprint.of(turnService.admitTurn(f.user,f.session,input)));assertEquals(before,f.directory());
        assertEquals(taskId,jdbc.queryForObject("SELECT pending_confirmation_task_id FROM agent_sessions WHERE session_id=?",String.class,f.session));
        jdbc.update("UPDATE agent_task_turns SET lease_until=TIMESTAMP '2000-01-01 00:00:00' WHERE turn_id=?",first.turnId());
        var other=new AgentTaskTurnService.Input("second-confirm","确认",taskId,input.taskVersion(),input.focusVersion(),true);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->turnService.admitTurn(f.user,f.session,other)).getStatusCode().value(),"租约过期不能再跑一次");
    }
    @Test void noTaskReplyHasNoExecutionPermitAndCompletesOnce(){
        // 无任务歧义回复只写来源和公开事件；同turn补交回读，异文冲突，不创建业务运行。
        var f=fixture();var before=f.directory();var input=new AgentTaskTurnService.Input("ask-target","继续",null,0,0,false);
        var turn=assertDoesNotThrow(()->turnService.admitTurn(f.user,f.session,input),"无任务短回复必须可以持久受理");assertNull(turn.admission(),"无任务不能签业务栅栏");
        assertEquals(before,f.directory());var first=turnService.completeReply(f.user,f.session,turn.turnId(),"请选择要继续的任务");var after=f.directory();
        assertEquals(first,turnService.completeReply(f.user,f.session,turn.turnId(),"请选择要继续的任务"));assertEquals(after,f.directory());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->turnService.completeReply(f.user,f.session,turn.turnId(),"另一个回复")).getStatusCode().value());
        assertNull(after.get("currentTask"));assertEquals(1,events.read(f.user,f.session,0,100).size());
    }
    @Test void uncertainAndForgedAckCannotCommit(){
        // 不明写必须待核查；不同账号scope回执不能使焦点提交。
        var f=fixture();var c=controls.prepare(f.user,f.session,request(f,f.selection(),"pause"));String id=(String)c.get("commandId");var wire=claim(f,id);
        var forged=ack(wire,"checkpoint_saved","none");forged.put("scope",Map.of("userId","999999","sessionId",f.session,"conversationEpoch",f.epoch));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->controls.acknowledge(f.user,f.session,id,forged)).getStatusCode().value());
        var uncertain=controls.acknowledge(f.user,f.session,id,ack(wire,"uncertain","uncertain"));
        assertEquals("prepared",uncertain.get("state"));assertEquals("needs_attention",uncertain.get("reconcileStatus"));
        assertNull(controls.claim(f.user,f.session,id,"later-owner",Instant.now().plusSeconds(120)));
        assertThrows(ResponseStatusException.class,()->controls.commit(f.user,f.session,id));assertNotNull(f.directory().get("currentTask"));
    }
    @Test void sixDispatchesAndSixtySecondsStopApply(){
        // S23：六次/60秒任一先到停止自动派发，保留同commandId和栅栏，不自动退出。
        var f=fixture();var c=controls.prepare(f.user,f.session,request(f,f.selection(),"pause"));String id=(String)c.get("commandId");Instant start=Instant.now().plusSeconds(1);
        for(int i=0;i<6;i++){Instant now=start.plusSeconds(i*6);assertNotNull(controls.claim(f.user,f.session,id,"owner",now));controls.dispatchFailed(f.user,f.session,id,"owner",now);}
        assertNull(controls.claim(f.user,f.session,id,"owner",start.plusSeconds(36)));assertEquals("needs_attention",controls.read(f.user,f.session,id).get("reconcileStatus"));
        assertEquals(6,jdbc.queryForObject("SELECT dispatch_count FROM agent_task_commands WHERE command_id=?",Integer.class,id));
        var f2=fixture();var c2=controls.prepare(f2.user,f2.session,request(f2,f2.selection(),"pause"));String id2=(String)c2.get("commandId");
        assertNotNull(controls.claim(f2.user,f2.session,id2,"owner",start));controls.dispatchFailed(f2.user,f2.session,id2,"owner",start);
        assertNull(controls.claim(f2.user,f2.session,id2,"owner",start.plusSeconds(60)));assertEquals("prepared",controls.read(f2.user,f2.session,id2).get("state"));
    }
    @Test void abortTombstoneWinsOverLateNormalAck(){
        // S23：持久退出决定先行；迟到checkpoint_saved不能反转，只有安全退出墓碑解除栅栏。
        var f=fixture();var task=f.selection();var c=controls.prepare(f.user,f.session,request(f,task,"pause"));String id=(String)c.get("commandId");var wire=claim(f,id);
        long prepared=((Number)AgentTaskTestFixtures.current(f.directory()).get("executionGeneration")).longValue();
        controls.beginAbort(f.user,f.session,id);assertEquals("aborting",controls.acknowledge(f.user,f.session,id,ack(wire,"checkpoint_saved","none")).get("state"));
        assertEquals("abort",controls.claim(f.user,f.session,id,"abort-owner",Instant.now().plusSeconds(6)).get("decision"),"迟到ACK不能阻止传播退出决定");
        assertThrows(ResponseStatusException.class,()->controls.commit(f.user,f.session,id));
        assertEquals("aborted",controls.acknowledge(f.user,f.session,id,ack(wire,"aborted","none")).get("state"));
        var after=f.directory();assertEquals("active",AgentTaskTestFixtures.current(after).get("lifecycle"));
        assertEquals(prepared+1,((Number)AgentTaskTestFixtures.current(after).get("executionGeneration")).longValue());
        controls.acknowledge(f.user,f.session,id,ack(wire,"checkpoint_saved","none"));assertEquals(after,f.directory());
    }
    @Test void fullPauseDirectoryAllowsNetZeroSwap(){
        // S01：五个暂停时新增暂停被拒，但选中暂停目标与前台交换净增为零，允许提交。
        var f=fixture();for(int i=0;i<5;i++)f.seedPaused();var front=f.selection();var before=f.directory();
        assertEquals(409,assertThrows(ResponseStatusException.class,()->controls.prepare(f.user,f.session,request(f,front,"pause"))).getStatusCode().value());assertEquals(before,f.directory());
        var selected=AgentTaskTestFixtures.paused(before).get(0);var c=controls.prepare(f.user,f.session,request(f,selected,"switch"));String id=(String)c.get("commandId");
        controls.acknowledge(f.user,f.session,id,ack(claim(f,id),"checkpoint_saved","none"));controls.commit(f.user,f.session,id);
        assertEquals(5,AgentTaskTestFixtures.paused(f.directory()).size());assertEquals(AgentTaskTestFixtures.id(selected),AgentTaskTestFixtures.current(f.directory()).get("conversationTaskId"));
    }
    @Test void insertCreatesNewTaskOnlyAfterSafeCommit(){
        // S01：新查询预备不能提前替换前台；只有可信停点提交才暂停原任务并创建新目标。
        var f=fixture();var front=f.selection();var input=new AgentTaskControlService.NewGoal("new-lookup","帮我查订单","lookup","group_buy_order","合成订单查询",((Number)f.directory().get("focusVersion")).longValue());
        var c=controls.prepareInsert(f.user,f.session,input);String id=(String)c.get("commandId");assertEquals(c,controls.prepareInsert(f.user,f.session,input));
        assertEquals(AgentTaskTestFixtures.id(front),AgentTaskTestFixtures.current(f.directory()).get("conversationTaskId"));assertTrue(AgentTaskTestFixtures.paused(f.directory()).isEmpty());
        controls.acknowledge(f.user,f.session,id,ack(claim(f,id),"checkpoint_saved","none"));controls.commit(f.user,f.session,id);
        assertEquals("group_buy_order",AgentTaskTestFixtures.current(f.directory()).get("intent"));assertEquals(1,AgentTaskTestFixtures.paused(f.directory()).size());
        var full=fixture();for(int i=0;i<5;i++)full.seedPaused();full.selection();var before=full.directory();
        var rejected=new AgentTaskControlService.NewGoal("over-capacity","查订单","lookup","group_buy_order","合成查询",((Number)before.get("focusVersion")).longValue());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->controls.prepareInsert(full.user,full.session,rejected)).getStatusCode().value());assertEquals(before,full.directory());
    }
    @Test void completeBusinessResultCanBeResubmittedWithoutExecution(){
        // S25：模拟Agent已有持久结果而完成HTTP丢失；补交只回读唯一回执/事件，不产生新run。
        var f=fixture();var task=f.selection();var input=new AgentTaskTurnService.Input("followup","继续推荐",AgentTaskTestFixtures.id(task),((Number)task.get("taskVersion")).longValue(),((Number)f.directory().get("focusVersion")).longValue(),true);
        var admitted=turnService.admitTurn(f.user,f.session,input);
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)admitted.admission().get("fence");var context=new LinkedHashMap<>(fence);context.remove("scope");context.remove("runId");
        String trace=jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,admitted.turnId());
        var event=Map.<String,Object>of("schemaVersion","2.2","eventId","synthetic-completion-event","eventType","run_completed","traceId",trace,"runId",fence.get("runId"),
            "sequence",0,"status","succeeded","payload",Map.of("message","已完成合成推荐"),"taskContext",context);
        var result=Map.<String,Object>of("schemaVersion","2.2","traceId",trace,"runId",fence.get("runId"),"status","succeeded","intent","deal_recommendation","events",List.of(event));
        var completion=Map.<String,Object>of("schemaVersion","2.2","turnId",admitted.turnId(),"fence",fence,"sourceMessageId",admitted.admission().get("sourceMessageId"),"result",result);
        var first=turnService.complete(f.user,f.session,completion);var after=f.directory();
        assertEquals(AgentTaskFingerprint.of(first),AgentTaskFingerprint.of(turnService.complete(f.user,f.session,completion)));assertEquals(after,f.directory());
        assertEquals("awaiting_followup",AgentTaskTestFixtures.stop(AgentTaskTestFixtures.current(after)).get("kind"));
        var replay=turnService.admitTurn(f.user,f.session,input);assertEquals("completed",replay.admission().get("admissionState"));assertEquals(false,replay.admission().get("mayExecuteTools"));
        assertNotNull(replay.completedResult());assertEquals(1,events.read(f.user,f.session,0,100).size());
    }
    @Test void outboxDeliveryFailureRetriesOnlySameEvent(){
        // S24：消费者失败只重投同事件；Consumer必须运行在事务外，不能重新执行任务。
        var f=fixture();var turn=turnService.admitTurn(f.user,f.session,new AgentTaskTurnService.Input("no-tool","继续",null,0,0,false));
        turnService.completeReply(f.user,f.session,turn.turnId(),"请选择任务");var before=f.directory();var seen=new ArrayList<Object>();
        // 排除同一个测试上下文中其他case的待投递行，保持本例有界批次只包含目标事件。
        jdbc.update("UPDATE agent_task_event_outbox SET delivery_state='sent' WHERE session_id<>?",f.session);
        dispatcher.deliverOnce(event->{if(((Map<?,?>)event.get("scope")).get("sessionId").equals(f.session)){
            assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());seen.add(((Number)event.get("taskEventId")).longValue());throw new IllegalStateException("合成投递失败");}});
        dispatcher.deliverOnce(event->{if(((Map<?,?>)event.get("scope")).get("sessionId").equals(f.session))seen.add(((Number)event.get("taskEventId")).longValue());});
        assertEquals(List.of(1L,1L),seen);assertEquals(before,f.directory());
    }
    @Test void concurrentInputsCreateOnlyOnePersistentLease() throws Exception {
        // S25：八个独立事务争用同一task，只允许一个输入入场，不以进程内锁冒充数据库串行化。
        var f=fixture();var task=f.selection();long version=((Number)task.get("taskVersion")).longValue(),focus=((Number)f.directory().get("focusVersion")).longValue();
        var pool=java.util.concurrent.Executors.newFixedThreadPool(8);var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var futures=new ArrayList<java.util.concurrent.Future<Integer>>();for(int i=0;i<8;i++){String key="parallel-"+i;
                futures.add(pool.submit(()->{start.await();try{turnService.admitTurn(f.user,f.session,new AgentTaskTurnService.Input(key,"合成跟进",AgentTaskTestFixtures.id(task),version,focus,true));return 200;}
                    catch(ResponseStatusException rejected){return rejected.getStatusCode().value();}}));}
            start.countDown();int accepted=0,conflicts=0;for(var future:futures){int status=future.get(15,java.util.concurrent.TimeUnit.SECONDS);if(status==200)accepted++;else if(status==409)conflicts++;else fail("非预期事务结果"+status);}
            assertEquals(1,accepted);assertEquals(7,conflicts);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_turns WHERE session_id=? AND lease_task_key IS NOT NULL",Integer.class,f.session));
        } finally {pool.shutdownNow();}
    }
    @Test void preparedControlFencesOrdinaryTurnAndAbortDoesNotRestoreIt(){
        // 控制预备隔离已入场turn；退出后仍不能重新使用旧许可，只允许后续新输入代次。
        var f=fixture();var task=f.selection();var turn=turnService.admitTurn(f.user,f.session,new AgentTaskTurnService.Input("prior-turn","继续推荐",AgentTaskTestFixtures.id(task),((Number)task.get("taskVersion")).longValue(),((Number)f.directory().get("focusVersion")).longValue(),true));
        task=AgentTaskTestFixtures.current(f.directory());var c=controls.prepare(f.user,f.session,request(f,task,"pause"));String id=(String)c.get("commandId");
        assertEquals("fenced",jdbc.queryForObject("SELECT state FROM agent_task_turns WHERE turn_id=?",String.class,turn.turnId()));
        controls.beginAbort(f.user,f.session,id);var wire=claim(f,id);assertEquals("abort",wire.get("decision"));
        controls.acknowledge(f.user,f.session,id,ack(wire,"aborted","none"));
        assertEquals("fenced",jdbc.queryForObject("SELECT state FROM agent_task_turns WHERE turn_id=?",String.class,turn.turnId()));
        assertEquals(false,jdbc.queryForObject("SELECT may_execute_tools FROM agent_task_turns WHERE turn_id=?",Boolean.class,turn.turnId()));
        assertNull(jdbc.queryForObject("SELECT lease_task_key FROM agent_task_turns WHERE turn_id=?",String.class,turn.turnId()));
    }
    @Test void oldEpochCommandReadIsNotAuthorized(){
        // 相同user/session但epoch改变后，原命令仍不能经只读接口泄漏/恢复。
        var f=fixture();var c=controls.prepare(f.user,f.session,request(f,f.selection(),"pause"));String id=(String)c.get("commandId");
        jdbc.update("UPDATE agent_sessions SET conversation_epoch=? WHERE session_id=?",UUID.randomUUID().toString(),f.session);
        assertEquals(404,assertThrows(ResponseStatusException.class,()->controls.read(f.user,f.session,id)).getStatusCode().value());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"input_required","confirmation_required"})
    void inconsistentCompletionCannotWriteStopOrConfirmation(String eventType){
        // 成功结果与等待输入/确认事件矛盾时整体拒绝，不能先写确认摘要再让成功停点掩盖它。
        var f=fixture();var task=f.selection();var admitted=turnService.admitTurn(f.user,f.session,new AgentTaskTurnService.Input("inconsistent-result","合成输入",AgentTaskTestFixtures.id(task),((Number)task.get("taskVersion")).longValue(),((Number)f.directory().get("focusVersion")).longValue(),true));
        @SuppressWarnings("unchecked") var fence=(Map<String,Object>)admitted.admission().get("fence");var context=new LinkedHashMap<>(fence);context.remove("scope");context.remove("runId");
        String trace=jdbc.queryForObject("SELECT trace_id FROM agent_task_turns WHERE turn_id=?",String.class,admitted.turnId());
        var payload="input_required".equals(eventType)?Map.<String,Object>of("question","请选择城市","inputKey","cityCode","candidates",List.of()):
            Map.<String,Object>of("summary",Map.of("action","submit_complaint","resourceId","general","title","合成确认","amountCents",0,"ruleVersion","complaint-v1",
                "subject","合成主题","description","合成正文"),
                "actions",List.of(Map.of("actionId","synthetic-cancel","actionType","cancel","label","取消","resourceId","general","enabled",true)));
        var event=Map.<String,Object>of("schemaVersion","2.2","eventId","synthetic-inconsistent","eventType",eventType,"traceId",trace,"runId",fence.get("runId"),"sequence",0,
            "status","input_required".equals(eventType)?"waiting_input":"waiting_confirmation","payload",payload,"taskContext",context);
        var result=Map.<String,Object>of("schemaVersion","2.2","traceId",trace,"runId",fence.get("runId"),"status","succeeded","intent","deal_recommendation","events",List.of(event));
        var raw=Map.<String,Object>of("schemaVersion","2.2","turnId",admitted.turnId(),"fence",fence,"sourceMessageId",admitted.admission().get("sourceMessageId"),"result",result);var before=f.directory();
        assertEquals(409,assertThrows(ResponseStatusException.class,()->turnService.complete(f.user,f.session,raw),"完成状态矛盾必须拒绝").getStatusCode().value());
        assertEquals(before,f.directory());assertNull(jdbc.queryForObject("SELECT pending_confirmation_task_id FROM agent_sessions WHERE session_id=?",String.class,f.session));
        // 普通输入合法停点应通过；确认除Schema外还要求真实领域准备，本控制零业务夹具不能伪造有效grant。
        String correctStatus=(String)event.get("status");var correctedResult=new LinkedHashMap<>(result);correctedResult.put("status",correctStatus);
        var corrected=new LinkedHashMap<>(raw);corrected.put("result",correctedResult);
        if("confirmation_required".equals(eventType)){
            assertThrows(ToolBoundaryException.class,()->turnService.complete(f.user,f.session,corrected));
            assertEquals(before,f.directory());return;
        }
        assertDoesNotThrow(()->turnService.complete(f.user,f.session,corrected));
        assertEquals(correctStatus,AgentTaskTestFixtures.stop(AgentTaskTestFixtures.current(f.directory())).get("kind"));
        if("confirmation_required".equals(eventType))assertEquals(AgentTaskTestFixtures.id(task),jdbc.queryForObject("SELECT pending_confirmation_task_id FROM agent_sessions WHERE session_id=?",String.class,f.session));
    }
}
