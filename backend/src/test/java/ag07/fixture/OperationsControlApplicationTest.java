package ag07.fixture;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

/** 私有维护通道校验，不启动Spring或数据库；非法路径、动作和扩大种子范围均须拒绝。 */
class OperationsControlApplicationTest {
    /** 夹具必须遵守生产网关的历史关联顺序，否则认领后不可变来源会永久保留缺口。 */
    @Test void recordsHistoryBeforeReturningAdmission() throws Exception {
        var context=org.mockito.Mockito.mock(org.springframework.context.ApplicationContext.class);
        var turns=org.mockito.Mockito.mock(com.fandazi.feature.agent.AgentTaskTurnService.class);
        var history=org.mockito.Mockito.mock(com.fandazi.feature.agent.AgentHistoryRecordingService.class);
        org.mockito.Mockito.when(context.getBean(com.fandazi.feature.agent.AgentTaskTurnService.class)).thenReturn(turns);
        org.mockito.Mockito.when(context.getBean(com.fandazi.feature.agent.AgentHistoryRecordingService.class)).thenReturn(history);
        var input=java.util.Map.<String,Object>of("requestId","request","body","合成原文","taskId","task","taskVersion",1,"focusVersion",1,"executable",true);
        var payload=java.util.Map.<String,Object>of("userId","1","sessionId","session","input",input);
        var accepted=new com.fandazi.feature.agent.AgentTaskTurnService.Accepted("turn",java.util.Map.of("fence",java.util.Map.of("runId","run","scope",java.util.Map.of("conversationEpoch","epoch"))),null);
        org.mockito.Mockito.when(turns.admitTurn(org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.eq("session"),org.mockito.ArgumentMatchers.any())).thenReturn(accepted);
        org.mockito.Mockito.when(turns.readAuthority(1L,"session","epoch","turn"))
            .thenReturn(java.util.Map.of("traceId","trace","source",java.util.Map.of("sourceMessageId","source","historyRecorded",false)))
            .thenReturn(java.util.Map.of("traceId","trace","source",java.util.Map.of("sourceMessageId","source","historyRecorded",true)));
        org.mockito.Mockito.when(history.recordUserLinkedSource(1L,"session","run","trace","合成原文","request","source")).thenReturn("message");
        java.lang.reflect.Method method=null;
        try {method=implementation().getMethod("admitRecorded",org.springframework.context.ApplicationContext.class,java.util.Map.class);}
        catch(NoSuchMethodException missing){fail("缺少真实历史关联后的受理夹具");}
        assertThat(method.invoke(null,context,payload)).isSameAs(accepted);
        var order=org.mockito.Mockito.inOrder(turns,history);
        order.verify(turns).admitTurn(org.mockito.ArgumentMatchers.eq(1L),org.mockito.ArgumentMatchers.eq("session"),org.mockito.ArgumentMatchers.any());
        order.verify(turns).readAuthority(1L,"session","epoch","turn");
        order.verify(history).recordUserLinkedSource(1L,"session","run","trace","合成原文","request","source");
        order.verify(turns).readAuthority(1L,"session","epoch","turn");
        // 历史失败或回读仍缺关联时必须停止，不能伪造historyRecorded或交给Agent认领。
        org.mockito.Mockito.when(history.recordUserLinkedSource(1L,"session","run","trace","合成原文","request","source")).thenReturn(null);
        final var checked=method;
        assertThatThrownBy(()->checked.invoke(null,context,payload)).hasCauseInstanceOf(IllegalStateException.class);
    }
    /** 缺实现形成明确红测，不能把编译缺类错误混作业务断言。 */
    private Class<?> implementation() {
        try { return Class.forName("ag07.fixture.OperationsControlApplication"); }
        catch(ClassNotFoundException error) { fail("缺少受信维护启动器");return null; }
    }
    /** 请求不能选择输出文件、任意反射方法或任意业务种子。 */
    @Test void rejectsUnregisteredCommands() throws Exception {
        var type=implementation();var validate=type.getDeclaredMethod("validateCommand",java.util.Map.class);
        assertThatThrownBy(()->validate.invoke(null,java.util.Map.of("id","../escape","op","status"))).hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->validate.invoke(null,java.util.Map.of("id","a".repeat(32),"op","exec"))).hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->validate.invoke(null,java.util.Map.of("id","a".repeat(32),"op","status","path","other"))).hasCauseInstanceOf(IllegalArgumentException.class);
        assertThatCode(()->validate.invoke(null,java.util.Map.of("id","a".repeat(32),"op","status"))).doesNotThrowAnyException();
    }
}
