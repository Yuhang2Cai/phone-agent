// TL-06真实Spring/MySQL网关组件验收；归属模型、Agent运行与安全ACK均为明确替身，业务计数单独核对。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** 固定金标：归属先于填槽、短回复不动原任务、插问新ID、完成重试不再运行Agent。 */
@SpringBootTest(properties={"spring.datasource.url=${AG03_TEST_DB_URL:jdbc:h2:mem:ag03-input-gateway;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE}",
    "spring.datasource.driver-class-name=${AG03_TEST_DB_DRIVER:org.h2.Driver}","spring.datasource.username=${AG03_TEST_DB_USER:sa}","spring.datasource.password=${AG03_TEST_DB_PASSWORD:}",
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","agent.task-lifecycle.enabled=true","agent.task-lifecycle.reconcile-enabled=false",
    "agent.task-lifecycle.insert-wait-ms=1000"})
@AutoConfigureMockMvc
class AgentTaskInputGatewayTest {
    @Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired UserRepository users;@Autowired JdbcTemplate jdbc;
    @Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;@SpyBean AgentTaskTurnService turns;
    @Autowired AgentTaskControlService controls;@SpyBean AgentTaskReconciliationService reconciliation;
    @Autowired AgentTaskCommandRepository commandRows;
    @Autowired AgentPresentationService presentations;@Autowired AgentConversationSourceService sources;
    @MockBean AgentTaskInputClient input;@MockBean AgentTaskControlClient control;@MockBean AgentServiceClient agent;
    // 归属原因是明确的组件输入，用于区分纯展示停点与普通字段继续，不冒称真实模型结果。
    AgentTaskTestFixtures fixture;String action,ownershipReason;long businessBefore;boolean explicitReturn,cancelReturnTarget;
    // 两个闩锁只用于精确冻结真实后台认领与回执之间的时序，不模拟命令已经提交。
    java.util.concurrent.CountDownLatch dispatchEntered,dispatchRelease;
    /** 单scope隔离且每例业务表增量0；测试替身不创建真实订单/申请，不冒称完整业务链路。 */
    @BeforeEach void setup(){fixture=new AgentTaskTestFixtures(users,sessions,registry,jdbc);action="no_task";ownershipReason="reviewed_exact";explicitReturn=false;cancelReturnTarget=false;
        dispatchEntered=null;dispatchRelease=null;
        businessBefore=jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_executions",Long.class);
        when(input.interpret(anyLong(),anyString(),anyMap())).thenAnswer(call->{
            Map<String,Object> raw=call.getArgument(2);var directory=(Map<?,?>)raw.get("directory");var front=(Map<?,?>)directory.get("currentTask");
            var result=new LinkedHashMap<String,Object>();result.put("schemaVersion","2.2");result.put("requestId",raw.get("requestId"));result.put("action",action);
            result.put("targetTaskId",Set.of("continue","pause","cancel").contains(action)?front.get("conversationTaskId"):null);
            result.put("targetTaskVersion",Set.of("continue","pause","cancel").contains(action)?front.get("taskVersion"):null);
            result.put("focusVersion",directory.get("focusVersion"));String query=(String)raw.get("query");String newQuery=explicitReturn?query.substring(0,query.indexOf('，')):query;
            result.put("newRequest","insert".equals(action)?newQuery:null);
            result.put("returnTo",explicitReturn?Map.of("targetTaskId",front.get("conversationTaskId"),"targetTaskVersion",front.get("taskVersion"),
                "focusVersion",directory.get("focusVersion"),"sourceStart",newQuery.codePointCount(0,newQuery.length()),"sourceEnd",query.codePointCount(0,query.length())):null);
            result.put("intent","insert".equals(action)?"store_info":null);result.put("purpose","insert".equals(action)?"query":null);
            result.put("taskKind","insert".equals(action)?"lookup":null);result.put("reasonCode",ownershipReason);result.put("question","请选择要继续的任务。");
            result.put("bodySha256",sha256(query));result.put("executionQuerySha256",sha256("insert".equals(action)?newQuery:query));return result;});
        when(control.dispatch(anyMap())).thenAnswer(call->{Map<String,Object> wire=call.getArgument(0);var receipts=new ArrayList<Map<String,Object>>();
            // 竞态用例让另一个真实协调器已持租约但尚未回执；其余用例保持原即时组件回执。
            if(dispatchEntered!=null){dispatchEntered.countDown();assertTrue(dispatchRelease.await(5,java.util.concurrent.TimeUnit.SECONDS),"测试后台派发未被释放");}
            for(var item:(List<?>)wire.get("targets")){var target=(Map<?,?>)item;var row=new LinkedHashMap<String,Object>();row.put("conversationTaskId",target.get("conversationTaskId"));
                row.put("runId",target.get("runId"));row.put("checkpointVersion",1);receipts.add(row);}
            var ack=new LinkedHashMap<String,Object>();ack.put("schemaVersion","2.2");ack.put("commandId",wire.get("commandId"));ack.put("scope",wire.get("scope"));
            ack.put("status","checkpoint_saved");ack.put("writeOutcome","none");ack.put("checkpoints",receipts);ack.put("reasonCode",null);return ack;});
        when(agent.run(any())).thenAnswer(call->{AgentRunRequest request=call.getArgument(0);var admission=(Map<?,?>)request.context().get("taskAdmission");
            if(cancelReturnTarget) {
                // 用真实后台取消控制模拟登记后用户改变目标，不能直接改表冒称产品生命周期已执行。
                var paused=AgentTaskTestFixtures.paused(fixture.directory()).get(0);
                var change=controls.prepare(fixture.user,fixture.session,Map.of("requestId","cancel-return-target","action","cancel","targetTaskId",paused.get("conversationTaskId"),
                    "expectedTaskVersion",paused.get("taskVersion"),"expectedFocusVersion",fixture.directory().get("focusVersion")));
                reconciliation.process((String)change.get("commandId"),java.time.Instant.now());
            }
            var fence=new LinkedHashMap<>((Map<String,Object>)admission.get("fence"));var context=new LinkedHashMap<>(fence);context.remove("scope");context.remove("runId");
            var event=Map.<String,Object>of("schemaVersion","2.2","eventId","synthetic-gateway-event","eventType","run_completed","traceId",request.traceId(),
                "runId",request.runId(),"sequence",0,"status","succeeded","payload",Map.of("message","合成门店信息查询答复"),"taskContext",context);
            var result=Map.<String,Object>of("schemaVersion","2.2","traceId",request.traceId(),"runId",request.runId(),"status","succeeded","intent","store_info","events",List.of(event));
            turns.complete(fixture.user,fixture.session,Map.of("schemaVersion","2.2","turnId",fence.get("turnId"),"fence",fence,
                "sourceMessageId",admission.get("sourceMessageId"),"result",result));
            return AgentResult.completed("合成门店信息查询答复","task_state",List.of());});
    }
    /** 传输成功与业务执行分开计数，所有归属/短回复组件都不产生领域工具执行。 */
    @AfterEach void businessUnchanged(){assertEquals(businessBefore,jdbc.queryForObject("SELECT COUNT(*) FROM agent_tool_executions",Long.class));}
    /** 本人真实HTTP安全链，正文保持原句且明确2.2协商。 */
    org.springframework.mock.web.MockHttpServletResponse send(Map<String,Object> raw) throws Exception {
        return http.perform(post("/api/agent/sessions/{id}/task-inputs",fixture.session)
            .with(authentication(new UsernamePasswordAuthenticationToken(fixture.user,null,List.of())))
            .contentType("application/json").content(json.writeValueAsString(raw))).andReturn().getResponse();
    }
    /** 固定请求ID用于HTTP重复，不能每次换ID掩盖第二次创建。 */
    Map<String,Object> request(String text){return Map.of("schemaVersion","2.2","requestId","synthetic-input","text",text);}
    /** 替身保持真实原文绑定，避免组件绕过生产摘要校验；不读取生产密钥。 */
    private static String sha256(String text) throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    @Test void greetingPreservesFrontTaskTraceAndStop() throws Exception {
        // 纯问候不关闭原run、不写退款原因/投诉字段，也不调用业务Agent。
        fixture.selection();var before=fixture.directory();String trace=sessions.owned(fixture.user,fixture.session).getTraceId();
        assertEquals(200,send(request("你好")).getStatus());assertTaskStateUnchanged(before,fixture.directory());
        assertEquals(trace,sessions.owned(fixture.user,fixture.session).getTraceId());verifyNoInteractions(agent,control);
        assertEquals(200,send(request("你好")).getStatus());verify(input,times(1)).interpret(anyLong(),anyString(),anyMap());
    }
    @Test void ambiguousPausedTasksDoNotGuessOrActivate() throws Exception {
        fixture.seedPaused();fixture.seedPaused();action="clarify";var before=fixture.directory();
        assertEquals(200,send(request("继续那个")).getStatus());assertTaskStateUnchanged(before,fixture.directory());verifyNoInteractions(agent,control);
    }
    @Test void insertedLookupIsNewTaskAndRetryDoesNotRunAgain() throws Exception {
        // 插问真正提交持久控制，原选择任务暂停，独立lookup成功关闭；ACK/答复是明确替身。
        String original=AgentTaskTestFixtures.id(fixture.selection());action="insert";
        var first=send(request("先查营业时间"));assertEquals(200,first.getStatus(),first.getContentAsString());
        var directory=fixture.directory();assertNull(directory.get("currentTask"));assertEquals(original,AgentTaskTestFixtures.paused(directory).get(0).get("conversationTaskId"));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_tasks WHERE session_id=?",Integer.class,fixture.session));
        var again=send(request("先查营业时间"));assertEquals(200,again.getStatus(),again.getContentAsString());
        // 完成回读必须保留原事件身份和任务围栏；缺身份会使Android无法把HTTP结果与SSE归并。
        // 对象来自真实持久turn，不能由当前目录补造，更不能把完成回读变成第二次Agent运行。
        var replay=json.readTree(again.getContentAsString()).get("events").get(0).get("payload");
        assertEquals("synthetic-gateway-event",replay.path("eventId").asText(),"完成回读丢失原事件身份");
        assertTrue(replay.has("taskContext"),"完成回读必须携带原任务围栏");
        assertTrue(replay.has("traceId")&&replay.has("runId")&&replay.has("sequence"));
        verify(agent,times(1)).run(any());verify(input,times(1)).interpret(anyLong(),anyString(),anyMap());
    }
    @Test void backgroundClaimedInsertStillAdmitsOriginalInputExactlyOnce() throws Exception {
        // 复现TL09实际投诉竞态：后台先认领同一插入命令，网关收到prepared快照后不能丢掉原业务输入。
        action="insert";dispatchEntered=new java.util.concurrent.CountDownLatch(1);dispatchRelease=new java.util.concurrent.CountDownLatch(1);
        var worker=new AgentTaskReconciliationService(controls,control,commandRows,true,false);
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CompletableFuture<Map<String,Object>>>();
        doAnswer(call->{String id=call.getArgument(0);
            future.set(java.util.concurrent.CompletableFuture.supplyAsync(()->worker.process(id,java.time.Instant.now())));
            assertTrue(dispatchEntered.await(3,java.util.concurrent.TimeUnit.SECONDS),"后台必须真实取得同命令派发租约");
            var pending=controls.read(fixture.user,fixture.session,id);assertEquals("prepared",pending.get("state"));
            dispatchRelease.countDown();return pending;
        }).when(reconciliation).process(anyString(),any());
        try {
            var first=send(request("先查营业时间"));assertEquals(200,first.getStatus());
            // 普通受理和Agent运行才证明原输入继续，HTTP200、目录激活或交接提示均不能替代。
            // 完成会撤销may_execute_tools，因此以持久run身份和实际Agent调用计数核受理，不能把已撤销许可误当未执行。
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_turns WHERE session_id=? AND run_id IS NOT NULL",Integer.class,fixture.session),"后台先认领插入命令后，原输入必须真正受理且只一次");
            assertEquals(200,send(request("先查营业时间")).getStatus());
            verify(agent,times(1)).run(any());verify(input,times(1)).interpret(anyLong(),anyString(),anyMap());verify(control,times(1)).dispatch(anyMap());
        } finally {
            // 无论红测或绿测均收回本例后台工作，不让租约/线程污染后续用例。
            dispatchRelease.countDown();if(future.get()!=null)future.get().get(5,java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    @Test void unresolvedInsertIsNotSuccessAndSameRequestCanContinueAfterCommit() throws Exception {
        // 交接没有确定结果时不得以COMPLETED短回复吞掉原输入；后续显式同键只接续原命令，不重新分类/建任务。
        action="insert";
        doAnswer(call->controls.read(fixture.user,fixture.session,call.getArgument(0))).when(reconciliation).process(anyString(),any());
        assertEquals(503,send(request("先查营业时间")).getStatus(),"未提交插入不得返回业务完成，须保留原请求身份供显式核查/接续");
        verifyNoInteractions(agent);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_turns WHERE session_id=?",Integer.class,fixture.session));
        var id=jdbc.queryForObject("SELECT command_id FROM agent_task_commands WHERE session_id=?",String.class,fixture.session);
        // 用正式协调器提交原持久命令，不能直接改表或伪造ACK/前台已切换；这里的外部ACK仍是明确组件替身。
        var worker=new AgentTaskReconciliationService(controls,control,commandRows,true,false);
        assertEquals("committed",worker.process(id,java.time.Instant.now()).get("state"));
        var resumed=send(request("先查营业时间"));assertEquals(200,resumed.getStatus());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_conversation_tasks WHERE session_id=?",Integer.class,fixture.session));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_turns WHERE session_id=? AND run_id IS NOT NULL",Integer.class,fixture.session));
        verify(agent,times(1)).run(any());verify(input,times(1)).interpret(anyLong(),anyString(),anyMap());verify(control,times(1)).dispatch(anyMap());
    }
    @Test void unresolvedInsertRejectsChangedBodyBeforeReinterpreting() throws Exception {
        // 首次交接未决后同键换正文不能重做模型解释、派发第二个控制或借原任务执行另一请求。
        action="insert";
        doAnswer(call->controls.read(fixture.user,fixture.session,call.getArgument(0))).when(reconciliation).process(anyString(),any());
        assertEquals(503,send(request("先查营业时间")).getStatus());
        var before=fixture.directory();
        assertEquals(409,send(request("改查套餐价格")).getStatus());
        // 拒绝请求没有短回复事件，目录水位也必须完全不变；不能复用要求水位加一的短答断言。
        assertEquals(before,fixture.directory());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_commands WHERE session_id=?",Integer.class,fixture.session));
        verify(input,times(1)).interpret(anyLong(),anyString(),anyMap());verifyNoInteractions(agent,control);
    }
    @ParameterizedTest @CsvSource({"cancel","pause_and_resume"})
    void lateInsertCannotExecuteAfterUserChangedItsTarget(String change) throws Exception {
        // 晚提交只创建原任务；用户随后取消或暂停再恢复即改变代次，旧输入不能被重新绑定到同名/同ID前台。
        action="insert";
        doAnswer(call->controls.read(fixture.user,fixture.session,call.getArgument(0))).when(reconciliation).process(anyString(),any());
        assertEquals(503,send(request("先查营业时间")).getStatus());
        var originalId=jdbc.queryForObject("SELECT command_id FROM agent_task_commands WHERE session_id=?",String.class,fixture.session);
        var worker=new AgentTaskReconciliationService(controls,control,commandRows,true,false);
        assertEquals("committed",worker.process(originalId,java.time.Instant.now()).get("state"));
        var target=AgentTaskTestFixtures.current(fixture.directory());
        String actionName="cancel".equals(change)?"cancel":"pause";
        var command=controls.prepare(fixture.user,fixture.session,Map.of("requestId","later-"+actionName,"action",actionName,
            "targetTaskId",target.get("conversationTaskId"),"expectedTaskVersion",target.get("taskVersion"),
            "expectedFocusVersion",fixture.directory().get("focusVersion")));
        assertEquals("committed",worker.process((String)command.get("commandId"),java.time.Instant.now()).get("state"));
        if("pause_and_resume".equals(change)) {
            // 故意恢复同一原ID以核焦点版本围栏，不能仅凭当前ID相同放行旧输入。
            var paused=AgentTaskTestFixtures.paused(fixture.directory()).get(0);
            var resume=controls.prepare(fixture.user,fixture.session,Map.of("requestId","later-resume","action","resume",
                "targetTaskId",paused.get("conversationTaskId"),"expectedTaskVersion",paused.get("taskVersion"),
                "expectedFocusVersion",fixture.directory().get("focusVersion")));
            assertEquals("committed",worker.process((String)resume.get("commandId"),java.time.Instant.now()).get("state"));
        }
        var before=fixture.directory();
        assertEquals(409,send(request("先查营业时间")).getStatus());assertEquals(before,fixture.directory());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_turns WHERE session_id=?",Integer.class,fixture.session));
        verify(input,times(1)).interpret(anyLong(),anyString(),anyMap());verifyNoInteractions(agent);
    }
    @Test void insertCompletionBetweenAdmissionAndExecutionOnlyReadsOriginalResult() throws Exception {
        // 固定受理已提交到执行前的并发完成窗口；普通受理和完成使用真服务，只有外部Agent是明确组件替身。
        // 若网关不重新读完成回执，会第二次发送同业务请求；此例必须观察到唯一外部调用和原事件回读。
        action="insert";
        doAnswer(call->{
            var accepted=(AgentTaskTurnService.Accepted)call.callRealMethod();
            var authority=turns.readAuthority(fixture.user,fixture.session,fixture.epoch,accepted.turnId());
            var fence=(Map<?,?>)accepted.admission().get("fence");
            agent.run(new AgentRunRequest(String.valueOf(fixture.user),fixture.session,"先查营业时间","confirm",
                (String)authority.get("traceId"),(String)fence.get("runId"),Map.of("taskAdmission",accepted.admission()),"2.2"));
            return accepted;
        }).when(turns).admitGatewayTurn(anyLong(),anyString(),any(),anyString(),anyMap());
        var response=send(request("先查营业时间"));assertEquals(200,response.getStatus());
        // 先核唯一外部执行，避免旧实现的空事件列表让指定回归退化为测试自身空指针错误。
        verify(agent,times(1)).run(any());
        assertEquals("synthetic-gateway-event",json.readTree(response.getContentAsString()).path("events").get(0).path("payload").path("eventId").asText());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_turns WHERE session_id=? AND run_id IS NOT NULL",Integer.class,fixture.session));
    }
    @Test void pauseControlDoesNotStartBusinessWorkflow() throws Exception {
        fixture.selection();action="pause";assertEquals(200,send(request("暂停当前任务")).getStatus());
        assertNull(fixture.directory().get("currentTask"));assertEquals(1,AgentTaskTestFixtures.paused(fixture.directory()).size());verifyNoInteractions(agent);
    }
    @Test void semanticContinueDisplaysOriginalStopWithoutNewBusinessTurn() throws Exception {
        // TL-09真实开发发现自然“继续”误送新run；纯展示原因只读原停点，不受理字段或消费确认。
        fixture.selection();action="continue";ownershipReason="show_current_stop";var before=fixture.directory();
        var response=send(request("继续现在这个任务"));assertEquals(200,response.getStatus());
        assertTaskStateUnchanged(before,fixture.directory());verifyNoInteractions(agent,control);
        // 短答持久行的fence_json是JSON文本null，不是SQL NULL；业务轮次须按真实run/工具许可核对。
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_turns WHERE session_id=? AND (run_id IS NOT NULL OR may_execute_tools=TRUE)",Integer.class,fixture.session));
    }
    @Test void foreignButtonWithExactPauseCannotRebindCurrentTask() throws Exception {
        // TL-09真实S09失败：非法按钮若回退审核整句，会暂停当前任务；服务端须在解释前404拒绝。
        fixture.selection();var other=new AgentTaskTestFixtures(users,sessions,registry,jdbc);
        var foreign=other.selection();var before=fixture.directory();var raw=new LinkedHashMap<>(request("暂停"));
        raw.put("button",Map.of("taskId",foreign.get("conversationTaskId"),"taskVersion",foreign.get("taskVersion"),"action","pause"));
        assertEquals(404,send(raw).getStatus(),"外部按钮不得忽略后重新绑定当前任务");
        assertEquals(before,fixture.directory());verifyNoInteractions(input,agent,control);
    }
    @Test void malformedInputDoesNotCallInterpreterOrChangeDirectory() throws Exception {
        fixture.selection();var before=fixture.directory();var raw=new LinkedHashMap<>(request("取消"));raw.put("taskFence",Map.of("taskId","foreign"));
        assertEquals(400,send(raw).getStatus());assertEquals(before,fixture.directory());verifyNoInteractions(input,agent,control);
    }
    @Test void explicitReturnRestoresOnlyFocusAndConsumesInstructionOnce() throws Exception {
        // S26：原文含emoji检验码点位置；查询完成后只恢复原task焦点，不能自动调用原业务Agent。
        String original=AgentTaskTestFixtures.id(fixture.selection());action="insert";explicitReturn=true;
        var first=send(request("😀先查营业时间，查完再继续这个任务"));assertEquals(200,first.getStatus(),first.getContentAsString());
        assertEquals(original,AgentTaskTestFixtures.id(AgentTaskTestFixtures.current(fixture.directory())));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_commands WHERE session_id=? AND return_status='completed'",Integer.class,fixture.session));
        assertEquals(200,send(request("😀先查营业时间，查完再继续这个任务")).getStatus());
        verify(agent,times(1)).run(any());verify(control,times(2)).dispatch(anyMap());
    }
    @Test void shortReplyIsRecoverableWithoutBorrowingBusinessRunOrDuplicatingHistory() throws Exception {
        // 寒暄/澄清虽不调用业务Agent，也必须在冷启动历史保留原输入和完整短答。
        fixture.selection();action="no_task";var raw=request("你好");
        var response=send(raw);assertEquals(200,response.getStatus());assertEquals(200,send(raw).getStatus());
        var rows=jdbc.queryForList("SELECT role,content,run_id FROM agent_messages WHERE session_id=? ORDER BY id",fixture.session);
        assertEquals(2,rows.size());assertEquals("你好",rows.get(0).get("content"));assertEquals("user",rows.get(0).get("role"));
        assertEquals(json.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("message").asText(),rows.get(1).get("content"));
        assertNull(rows.get(0).get("run_id"));assertNull(rows.get(1).get("run_id"));verifyNoInteractions(agent,control);
    }
    @Test void insertionHistoryKeepsFullOriginalWhileExecutionKeepsOnlyBusinessSpan() throws Exception {
        // 返回后缀是用户原意的一部分；历史保留完整原文，业务Agent仍只能执行审核过的连续子句。
        fixture.selection();action="insert";explicitReturn=true;String original="😀先查营业时间，查完再继续这个任务";
        assertEquals(200,send(request(original)).getStatus());assertEquals(200,send(request(original)).getStatus());
        var messages=jdbc.queryForList("SELECT role,content FROM agent_messages WHERE session_id=? ORDER BY id",fixture.session);
        assertEquals(2,messages.size());assertEquals(original,messages.get(0).get("content"));
        var captured=org.mockito.ArgumentCaptor.forClass(AgentRunRequest.class);verify(agent,times(1)).run(captured.capture());
        assertEquals("😀先查营业时间",captured.getValue().text());
    }
    @Test void changedReturnTargetIsNeverAutomaticallyRestored() throws Exception {
        // 登记后原目标由真实后台取消提交，查询成功仍只显示入口/结果，不能复活取消任务。
        String original=AgentTaskTestFixtures.id(fixture.selection());action="insert";explicitReturn=true;cancelReturnTarget=true;
        var first=send(request("先查营业时间，查完再继续这个任务"));assertEquals(200,first.getStatus(),first.getContentAsString());
        assertNull(fixture.directory().get("currentTask"));assertEquals("cancelled",registry.task(fixture.user,fixture.session,original,fixture.epoch).get("lifecycle"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM agent_task_commands WHERE session_id=? AND return_status='invalidated'",Integer.class,fixture.session));
        verify(agent,times(1)).run(any());verify(control,times(2)).dispatch(anyMap());
    }
    @Test void selectedReferenceIsValidatedBeforeControlAndPersistedWithTurn() throws Exception {
        // 显式选择仅回传最小引用，Spring从本人同epoch登记读取候选；不得让客户端上传orderedIds或旧凭证。
        fixture.selection();String source=(String)sources.issue(fixture.user,fixture.session,"合成页面展示","selection-source").get("sourceMessageId");
        var card=presentations.register(fixture.user,fixture.session,source,List.of(Map.of("type","store","resourceId","synthetic-store","name","合成门店"))).get(0);
        var metadata=(Map<?,?>)card.get("presentation");var reference=Map.<String,Object>of("presentationId",metadata.get("presentationId"),"version",metadata.get("version"),"ordinal",1);
        var raw=new LinkedHashMap<>(request("查这家营业时间"));raw.put("selectionRef",reference);action="insert";
        var first=send(raw);assertEquals(200,first.getStatus(),first.getContentAsString());
        var inputCapture=org.mockito.ArgumentCaptor.forClass(Map.class);verify(input).interpret(anyLong(),anyString(),inputCapture.capture());
        var selected=(Map<?,?>)inputCapture.getValue().get("selectedContext");assertEquals(reference,selected.get("selectionRef"));
        var runCapture=org.mockito.ArgumentCaptor.forClass(AgentRunRequest.class);verify(agent).run(runCapture.capture());
        assertEquals(reference,runCapture.getValue().context().get("selectionRef"));
        assertEquals(200,send(raw).getStatus());verify(agent,times(1)).run(any());
        var before=fixture.directory();var forged=new LinkedHashMap<>(reference);forged.put("orderedIds",List.of("foreign-store"));
        raw.put("requestId","forged-selection");raw.put("selectionRef",forged);
        assertEquals(400,send(raw).getStatus());assertEquals(before,fixture.directory());verify(input,times(1)).interpret(anyLong(),anyString(),anyMap());
    }
    @Test void changedDecisionBodyCannotPauseOrCreateTask() throws Exception {
        // 内部HTTP成功也不是执行许可；原文摘要不匹配必须在生命周期控制前拒绝。
        fixture.selection();var before=fixture.directory();
        doAnswer(call->{
            var result=new LinkedHashMap<String,Object>();for(String field:List.of("targetTaskId","targetTaskVersion","newRequest","reasonCode","question","intent","purpose","taskKind","returnTo"))result.put(field,null);
            result.put("schemaVersion","2.2");result.put("requestId","synthetic-input");result.put("action","cancel");result.put("focusVersion",before.get("focusVersion"));
            result.put("bodySha256","0".repeat(64));result.put("executionQuerySha256","0".repeat(64));return result;}).when(input).interpret(anyLong(),anyString(),anyMap());
        assertEquals(409,send(request("取消当前任务")).getStatus());assertEquals(before,fixture.directory());verifyNoInteractions(control,agent);
    }
    @Test void oversizedInputIsRejectedBeforeAnyNewGoalControl() throws Exception {
        // 后续RunRequest最多4000字符，网关必须先拒绝，不能先暂停旧task再因传输过长失败。
        fixture.selection();var before=fixture.directory();action="insert";
        assertEquals(400,send(request("查".repeat(4001))).getStatus());assertEquals(before,fixture.directory());verifyNoInteractions(input,control,agent);
    }
    @Test void exactContinueDisplaysOriginalInputStopWithoutFillingReason() throws Exception {
        // 当前退款原因等待遇“继续”只是展示停点，不能送进抽槽模型成为原因或推进任何工具。
        var task=registry.create(fixture.user,fixture.session,"transaction","refund_after_sale","合成退款任务");
        var run=registry.beginRun(fixture.user,fixture.session,AgentTaskTestFixtures.id(task));
        registry.recordRunStop(fixture.user,fixture.session,AgentTaskTestFixtures.id(task),run.id(),"waiting_input","reason",null);
        var before=fixture.directory();String trace=sessions.owned(fixture.user,fixture.session).getTraceId();action="continue";
        var response=send(request("继续"));assertEquals(200,response.getStatus(),response.getContentAsString());
        assertTrue(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8).contains("退款原因"));
        assertTaskStateUnchanged(before,fixture.directory());assertEquals(trace,sessions.owned(fixture.user,fixture.session).getTraceId());
        verifyNoInteractions(agent,control);
    }
    /** 四类具体确认互不授权，真实摘要与版本匹配也不能覆盖用户原句中的动作差异。 */
    @ParameterizedTest
    @CsvSource({"create_group_buy_order,确认支付", "pay_mock_order,确认购买", "request_refund,确认投诉", "submit_complaint,确认退款"})
    void confirmationWordsMustMatchPresentedAction(String presentedAction,String words) throws Exception {
        // 即使目标、版本和摘要均真实，另一动作的确认文字也必须在归属/执行前拒绝，不能借摘要执行用户未确认的动作。
        var task=registry.create(fixture.user,fixture.session,"transaction","group_buy_order","合成动作隔离任务");
        String id=AgentTaskTestFixtures.id(task);var run=registry.beginRun(fixture.user,fixture.session,id);
        registry.recordRunStop(fixture.user,fixture.session,id,run.id(),"waiting_confirmation",null,null);
        var summary=Map.<String,Object>of("action",presentedAction,"resourceId","synthetic-resource");
        sessions.bindPendingConfirmation(fixture.user,fixture.session,run.id(),summary);
        var before=fixture.directory();var front=AgentTaskTestFixtures.current(before);var raw=new LinkedHashMap<>(request(words));
        raw.put("confirmation",Map.of("taskId",id,"taskVersion",front.get("taskVersion"),"focusVersion",before.get("focusVersion"),"summary",summary));
        assertEquals(409,send(raw).getStatus(),"不同动作确认必须拒绝");assertEquals(before,fixture.directory());
        verifyNoInteractions(input,control,agent);
    }

    /** 短回复应发布一个真实control_reply水位，任务/焦点/停点保持原值，不能误断言整个目录字节不变。 */
    void assertTaskStateUnchanged(Map<String,Object> before,Map<String,Object> after) {
        assertEquals(before.get("currentTask"),after.get("currentTask"));assertEquals(before.get("pausedTasks"),after.get("pausedTasks"));
        assertEquals(before.get("focusVersion"),after.get("focusVersion"));
        assertEquals(((Number)before.get("throughTaskEventId")).longValue()+1,((Number)after.get("throughTaskEventId")).longValue());
    }
}
