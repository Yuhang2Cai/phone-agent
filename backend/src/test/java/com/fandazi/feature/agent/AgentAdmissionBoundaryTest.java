package com.fandazi.feature.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import com.fandazi.feature.DomainExceptionHandler;
import com.fandazi.feature.auth.UserRepository;

/** 入口门控次序与安全HTTP信封，不使用模型、不建立领域数据。 */
class AgentAdmissionBoundaryTest {
    /** 缺接线必须以指定断言失败，构造器保持原兼容形态。 */
    private void attach(Object service,AgentRequestAdmission gate) throws Exception {
        var found=Arrays.stream(service.getClass().getDeclaredMethods()).filter(m->m.getName().equals("attachAdmission")).findFirst();
        assertTrue(found.isPresent(),"缺少实际网关注入准入");found.get().setAccessible(true);found.get().invoke(service,gate);
    }
    @Test void oldGatewayRejectsBeforeSessionOrModelWrite() throws Exception {
        var sessions=mock(AgentSessionService.class);var agent=mock(AgentServiceClient.class);
        var gateway=new AgentGatewayService(sessions,agent,mock(AgentEventService.class),mock(AgentCardEnrichmentService.class));
        var gate=new AgentRequestAdmission(true,()->100000,4096);attach(gateway,gate);
        try(var occupied=gate.acquire("someone",false)) {
            assertThrows(AgentRequestAdmission.Denied.class,()->gateway.handle(1L,"s","合成",AgentMode.CONFIRM));
        }
        verify(sessions,never()).start(any(),any(),any(),any());verifyNoInteractions(agent);
    }
    @Test void taskGatewayRejectsBeforePersistentSessionAndTurn() throws Exception {
        var sessions=mock(AgentSessionService.class);var turns=mock(AgentTaskTurnService.class);
        // 本例在准入处停止，不执行任何知识交付；显式提供新版依赖替身，不放宽“未触碰持久化”的断言。
        var gateway=new AgentTaskGatewayService(sessions,null,turns,null,null,null,null,null,null,null,null,null,null,null,null,
            mock(AgentKnowledgeDelivery.class),null,false,true,10000);
        var gate=new AgentRequestAdmission(true,()->100000,4096);attach(gateway,gate);
        try(var occupied=gate.acquire("someone",false)) {
            assertThrows(AgentRequestAdmission.Denied.class,()->gateway.handle(1L,"s",Map.of("schemaVersion","2.2","requestId","r","text","合成")));
        }
        verify(sessions,never()).startTaskSession(any(),any(),any());verifyNoInteractions(turns);
    }
    @Test void missingSessionPreflightDoesNotCreatePlaceholder() throws Exception {
        var rows=mock(AgentSessionRepository.class);var users=mock(UserRepository.class);
        var sessions=new AgentSessionService(rows,users);
        var found=Arrays.stream(AgentSessionService.class.getMethods()).filter(m->m.getName().equals("preflightStart")).findFirst();
        assertTrue(found.isPresent(),"缺少会话只读归属核验");
        // 不存在用户必须拒绝，不能先写占位行再以容量错误结束。
        var failure=assertThrows(InvocationTargetException.class,()->found.get().invoke(sessions,1L,"s"));
        assertTrue(failure.getCause() instanceof RuntimeException);
        verify(rows,never()).createIfAbsent(any(),any(),any(),any());
    }
    @Test void controlRecheckHasSeparateCapacityBeforeDispatch() throws Exception {
        var controls=mock(AgentTaskControlService.class);var reconciliation=mock(AgentTaskReconciliationService.class);
        var controller=new AgentTaskCommandController(controls,reconciliation,true);
        var gate=new AgentRequestAdmission(true,()->100000,4096);attach(controller,gate);
        when(controls.read(1L,"s","original")).thenReturn(Map.of("commandId","original"));
        when(reconciliation.recheck(1L,"s","original")).thenReturn(Map.of("state","committed"));
        try(var normal=gate.acquire("u",false)) {
            assertEquals("committed",controller.recheck(1L,"s","original").get("state"));
            try(var a=gate.acquire("u",true);var b=gate.acquire("u",true)) {
                assertThrows(AgentRequestAdmission.Denied.class,()->controller.recheck(1L,"s","original"));
            }
        }
        verify(reconciliation,times(1)).recheck(1L,"s","original");
    }
    @Test void goalRecoveryMustNotWriteWhenControlCapacityIsFull() throws Exception {
        // 恢复只是受理控制；不能因走另一公开入口绕过保护写入恢复记录。
        var recoveries=mock(AgentGoalRecoveryService.class);var controller=new AgentGoalController(recoveries);
        var gate=new AgentRequestAdmission(true,()->100000,4096);attach(controller,gate);
        when(recoveries.projection(1L,"s","t")).thenReturn(Map.of());
        try(var a=gate.acquire("u",true);var b=gate.acquire("u",true)) {
            assertThrows(AgentRequestAdmission.Denied.class,()->controller.recover(1L,"s","t",Map.of()));
        }
        verify(recoveries,never()).accept(any(),any(),any(),any());
    }
    @Test void originalReceiptRemainsReadableWhenOrdinaryIsFull() throws Exception {
        // 核查原执行键不借普通许可；控制饱和则返回明确拒绝，不重派工具。
        var receipts=mock(AgentTaskReceiptService.class);var validator=mock(InternalV1JwtValidator.class);
        var controller=new AgentToolReceiptController(validator,receipts);
        var gate=new AgentRequestAdmission(true,()->100000,4096);attach(controller,gate);
        when(receipts.read(1L,"s","e","t","r","turn","key")).thenReturn(Map.of("state","committed"));
        try(var ordinary=gate.acquire("u",false)) {
            assertEquals("committed",controller.read("token","key","1","s","e","t","r","turn").getBody().get("state"));
            try(var a=gate.acquire("u",true);var b=gate.acquire("u",true)) {
                assertThrows(AgentRequestAdmission.Denied.class,()->controller.read("token","key","1","s","e","t","r","turn"));
            }
        }
    }
    /** 固定失败控制器只用于验证现有统一错误出口，真实状态由生产异常提供。 */
    @Test void readWorkRunsInsideReservedPermit() throws Exception {
        // 实际回读必须位于许可内，不能读完整结果后才瞬时申请一个无效的控制槽。
        var gate=new AgentRequestAdmission(true,()->100000,4096);
        var receipts=mock(AgentTaskReceiptService.class);var receiptController=new AgentToolReceiptController(mock(InternalV1JwtValidator.class),receipts);
        attach(receiptController,gate);
        when(receipts.read(1L,"s","e","t","r","turn","key")).thenAnswer(call->{
            assertEquals(1,gate.snapshot().get("control"),"回执读未在控制许可内执行");return Map.of("state","unknown");});
        receiptController.read("token","key","1","s","e","t","r","turn");
        var recoveries=mock(AgentGoalRecoveryService.class);var goals=new AgentGoalController(recoveries);attach(goals,gate);
        when(recoveries.projection(1L,"s","t")).thenAnswer(call->{
            assertEquals(1,gate.snapshot().get("control"),"目标投影未在控制许可内执行");return Map.of();});
        goals.goals(1L,"s","t");
        var controls=mock(AgentTaskControlService.class);var commands=new AgentTaskCommandController(controls,mock(AgentTaskReconciliationService.class),true);attach(commands,gate);
        when(controls.pendingForSession(1L,"s")).thenAnswer(call->{
            assertEquals(1,gate.snapshot().get("control"),"命令列表未在控制许可内执行");return List.of();});
        commands.pending(1L,"s");assertEquals(0,gate.snapshot().get("control"));
    }

    /** 固定失败控制器只用于验证现有统一错误出口，真实状态由生产异常提供。 */
    @RestController static class DenyingController {
        @GetMapping("/synthetic-busy") public void busy() {throw new AgentRequestAdmission.Denied(503,1,"capacity");}
        @GetMapping("/synthetic-rate") public void rate() {throw new AgentRequestAdmission.Denied(429,10,"account_rate");}
    }
    @Test void responseUsesExistingErrorEnvelopeAndRetryAfter() throws Exception {
        var http=MockMvcBuilders.standaloneSetup(new DenyingController()).setControllerAdvice(new DomainExceptionHandler()).build();
        for(String path:List.of("/synthetic-busy","/synthetic-rate")) {
            var response=assertDoesNotThrow(()->http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)).andReturn().getResponse(),"缺少准入安全HTTP错误信封");
            assertEquals(path.endsWith("busy")?503:429,response.getStatus());
            assertNotNull(response.getHeader("Retry-After"));assertTrue(response.getContentAsString().contains("error"));
            assertFalse(response.getContentAsString().contains("capacity"));
        }
    }
}
