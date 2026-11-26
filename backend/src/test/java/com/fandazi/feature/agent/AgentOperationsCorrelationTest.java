package com.fandazi.feature.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 关联及事务后事件规则测试；合成事务同步回调不代替后续真实MySQL组合验收。 */
class AgentOperationsCorrelationTest {
    @TempDir Path root;

    /** 缺实现明确指定断言，避免编译失败冒充红测；反射异常保留原产品原因。 */
    void invoke(String method,Class<?>[] types,Object...args) {
        Class<?> type=assertDoesNotThrow(()->Class.forName("com.fandazi.feature.agent.AgentOperationsCorrelation"),"缺少AG07权威关联实现");
        assertDoesNotThrow(()->type.getMethod(method,types).invoke(null,args));
    }
    /** 开启独占测试目录并显式启动资源，所有用例finally关闭。 */
    AgentOperationsRuntime runtime() {
        var runtime=new AgentOperationsRuntime(new MockEnvironment().withProperty("AG07_OPERATIONS_ENABLED","true")
            .withProperty("AG07_OPERATIONS_ROOT",root.toString()));runtime.start();return runtime;
    }
    /** 读取实际sink输出，核没有把用户/凭证/载荷写入事件。 */
    List<Map> records() throws Exception {
        var result=new ArrayList<Map>();var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        try(var files=Files.walk(root)) {
            for(var file:files.filter(p->p.toString().endsWith(".jsonl")).toList())
                for(var line:Files.readAllLines(file))result.add(mapper.readValue(line,Map.class));
        }return result;
    }
    /** 请求各业务身份均为合成；敏感哨兵不得输出。 */
    InternalV2ToolRequest request() {
        return new InternalV2ToolRequest("2.1","private-user","private-session","trace-a","run-a",
            "task-a","run-a:task-a:1",1,Map.of("secret","private-payload"),Map.of(),Map.of());
    }
    @Test void bridgeOnlyEmitsDigestsAndNeverAuthorizes() throws Exception {
        // 关联事件仅记录原始请求到业务run映射，方法不会执行/恢复业务。
        var runtime=runtime();
        try {runtime.run(()->invoke("bridge",new Class<?>[]{String.class,String.class,String.class,String.class},
                "request-a","run-a","trace-a","task-a"));}
        finally {runtime.close();}
        var rows=records();assertEquals(1,rows.size());var row=rows.get(0);
        assertEquals("bridge",row.get("eventType"));assertEquals(AgentOperationsEvent.identityKey("request-a"),row.get("requestKey"));
        assertEquals(AgentOperationsEvent.identityKey("run-a"),row.get("runKey"));
        assertFalse(rows.toString().contains("request-a"));
    }
    @Test void committedOnlyAfterTransactionCallbackAndNeverBefore() throws Exception {
        // 注册回调时事件数仍为零；只有实际提交回调发生才排入committed事件。
        var runtime=runtime();TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {runtime.run(()->invoke("afterToolCommit",new Class<?>[]{String.class,InternalV2ToolRequest.class},"create_group_buy_order",request()));
            assertEquals(0L,runtime.snapshot().get("accepted"));
            var callbacks=TransactionSynchronizationManager.getSynchronizations();assertEquals(1,callbacks.size());
            callbacks.forEach(callback->callback.afterCommit());
        } finally {TransactionSynchronizationManager.clear();runtime.close();}
        var rows=records();assertEquals(1,rows.size());assertEquals("committed",rows.get(0).get("status"));
        assertEquals(AgentOperationsEvent.identityKey("run-a:task-a:1"),rows.get(0).get("executionKeyHash"));
        assertFalse(rows.toString().contains("private-"));
    }
    @Test void rollbackOrNoTransactionCannotClaimCommit() throws Exception {
        // 无事务的直接单测/异常调用以及回滚都不输出伪提交。
        var runtime=runtime();
        try {runtime.run(()->invoke("afterToolCommit",new Class<?>[]{String.class,InternalV2ToolRequest.class},"create_group_buy_order",request()));
            TransactionSynchronizationManager.initSynchronization();TransactionSynchronizationManager.setActualTransactionActive(true);
            runtime.run(()->invoke("afterToolCommit",new Class<?>[]{String.class,InternalV2ToolRequest.class},"create_group_buy_order",request()));
            TransactionSynchronizationManager.getSynchronizations().forEach(callback->callback.afterCompletion(1));
        } finally {TransactionSynchronizationManager.clear();runtime.close();}
        assertTrue(records().isEmpty());
    }
    @Test void admissionDenialIsObservedWithoutAccountData() throws Exception {
        // 使用真实准入与sink输出，不通过手工emit代替拒绝路径。
        var runtime=runtime();var gate=new AgentRequestAdmission(true,()->100000,4096);
        try(var held=gate.acquire("private-account",false)) {
            try {runtime.run(()->assertThrows(AgentRequestAdmission.Denied.class,()->gate.acquire("private-other",false)));}
            finally {runtime.close();}
        }
        var rows=records();assertEquals(1,rows.size());assertEquals("admission",rows.get(0).get("eventType"));
        assertEquals("denied",rows.get(0).get("status"));assertFalse(rows.toString().contains("private-"));
    }
}
