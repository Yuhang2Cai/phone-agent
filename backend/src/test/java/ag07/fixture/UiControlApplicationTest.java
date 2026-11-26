package ag07.fixture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fandazi.feature.agent.AgentRequestAdmission;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** UI测试进程仅能持有一次普通许可，不发送模型或业务；验证原控制通路及资源归还。 */
class UiControlApplicationTest {
    /** 红测先检查装配接口，再核一次持有/释放，防止测试旁路长期占用产品容量。 */
    @Test void boundedHoldAndReleaseAreOwned() throws Exception {
        Class<?> type;
        try {type=Class.forName("ag07.fixture.UiControlApplication$Control");}
        catch(ClassNotFoundException missing) {fail("必须实现UI专属有界控制器");return;}
        var admission=new AgentRequestAdmission(true,()->1L,8);
        var control=type.getConstructor(AgentRequestAdmission.class).newInstance(admission);
        var call=type.getMethod("call",String.class);
        call.invoke(control,"hold_ordinary");assertEquals(1,admission.snapshot().get("ordinary"));
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->call.invoke(control,"hold_ordinary"));
        // 普通槽已满时产品控制许可仍可获取，不以测试返回值模拟业务控制成功。
        try(var lease=admission.acquire("synthetic-control",true)) {assertEquals(1,admission.snapshot().get("control"));}
        call.invoke(control,"release_ordinary");assertEquals(0,admission.snapshot().get("ordinary"));
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->call.invoke(control,"hold_ordinary"));
        ((AutoCloseable)control).close();assertEquals(0,admission.snapshot().get("ordinary"));
    }
    /** 非法命令必须在准入副作用前被拒绝，不能任意桥接Spring Bean或SQL。 */
    @Test void invalidOperationNeverAcquires() throws Exception {
        Class<?> type;
        try {type=Class.forName("ag07.fixture.UiControlApplication$Control");}
        catch(ClassNotFoundException missing) {fail("必须实现UI专属有界控制器");return;}
        var admission=mock(AgentRequestAdmission.class);
        var control=type.getConstructor(AgentRequestAdmission.class).newInstance(admission);
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->type.getMethod("call",String.class).invoke(control,"arbitrary"));
        verifyNoInteractions(admission);
    }
    /** 空会话前置必须调用生产初始化且只执行一次，禁止任意字段/重复分配。 */
    @Test void emptySessionUsesProductionServiceOnce() throws Exception {
        var service=mock(com.fandazi.feature.agent.AgentSessionService.class);
        Class<?> type=Class.forName("ag07.fixture.UiControlApplication$SessionBinding");
        var binding=type.getConstructor(com.fandazi.feature.agent.AgentSessionService.class).newInstance(service);
        var bind=type.getMethod("bind",Map.class);
        String session="58f389f9-87c1-47af-946a-2a69108fb621";
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->bind.invoke(binding,Map.of("userId",1,"sessionId",session,"sql","unsafe")));
        verifyNoInteractions(service);
        bind.invoke(binding,Map.of("userId",1,"sessionId",session));
        verify(service).startTaskSession(eq(1L),eq(session),anyString());
        assertThrows(java.lang.reflect.InvocationTargetException.class,()->bind.invoke(binding,Map.of("userId",1,"sessionId",session)));
        verifyNoMoreInteractions(service);
    }
}
