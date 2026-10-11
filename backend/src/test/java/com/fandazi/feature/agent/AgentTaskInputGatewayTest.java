// TL-06真实Spring/MySQL网关组件验收；归属模型、Agent运行与安全ACK均为明确替身，业务计数单独核对。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
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
    "spring.jpa.hibernate.ddl-auto=validate","spring.flyway.enabled=true","agent.task-lifecycle.enabled=true","agent.task-lifecycle.reconcile-enabled=false"})
@AutoConfigureMockMvc
class AgentTaskInputGatewayTest {
    @Autowired MockMvc http;@Autowired ObjectMapper json;@Autowired UserRepository users;@Autowired JdbcTemplate jdbc;
    @Autowired AgentSessionService sessions;@Autowired AgentTaskRegistryService registry;@Autowired AgentTaskTurnService turns;
    @Autowired AgentTaskControlService controls;@Autowired AgentTaskReconciliationService reconciliation;
    @Autowired AgentPresentationService presentations;@Autowired AgentConversationSourceService sources;
    @MockBean AgentTaskInputClient input;@MockBean AgentTaskControlClient control;@MockBean AgentServiceClient agent;
    AgentTaskTestFixtures fixture;String action;long businessBefore;boolean explicitReturn,cancelReturnTarget;
    /** 单scope隔离且每例业务表增量0；测试替身不创建真实订单/申请，不冒称完整业务链路。 */
    @BeforeEach void setup(){fixture=new AgentTaskTestFixtures(users,sessions,registry,jdbc);action="no_task";explicitReturn=false;cancelReturnTarget=false;
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
            result.put("taskKind","insert".equals(action)?"lookup":null);result.put("reasonCode","reviewed_exact");result.put("question","请选择要继续的任务。");
            result.put("bodySha256",sha256(query));result.put("executionQuerySha256",sha256("insert".equals(action)?newQuery:query));return result;});
        when(control.dispatch(anyMap())).thenAnswer(call->{Map<String,Object> wire=call.getArgument(0);var receipts=new ArrayList<Map<String,Object>>();
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
        verify(agent,times(1)).run(any());verify(input,times(1)).interpret(anyLong(),anyString(),anyMap());
    }
    @Test void pauseControlDoesNotStartBusinessWorkflow() throws Exception {
        fixture.selection();action="pause";assertEquals(200,send(request("暂停当前任务")).getStatus());
        assertNull(fixture.directory().get("currentTask"));assertEquals(1,AgentTaskTestFixtures.paused(fixture.directory()).size());verifyNoInteractions(agent);
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
    /** 短回复应发布一个真实control_reply水位，任务/焦点/停点保持原值，不能误断言整个目录字节不变。 */
    void assertTaskStateUnchanged(Map<String,Object> before,Map<String,Object> after) {
        assertEquals(before.get("currentTask"),after.get("currentTask"));assertEquals(before.get("pausedTasks"),after.get("pausedTasks"));
        assertEquals(before.get("focusVersion"),after.get("focusVersion"));
        assertEquals(((Number)before.get("throughTaskEventId")).longValue()+1,((Number)after.get("throughTaskEventId")).longValue());
    }
}
