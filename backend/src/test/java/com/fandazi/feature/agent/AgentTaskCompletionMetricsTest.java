// AG06完成回执观测回归：执行真实控制器，核对计时位置、原返回/异常和权限边界。
package com.fandazi.feature.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 开关前后调用同一个控制器和服务边界；替身不冒充真实数据库或线上性能证据。 */
class AgentTaskCompletionMetricsTest {
    private final InternalV1JwtValidator jwt=mock(InternalV1JwtValidator.class);
    private final AgentTaskTurnService turns=mock(AgentTaskTurnService.class);
    private final AgentTaskAdmissionController controller=new AgentTaskAdmissionController(jwt,turns);
    private final Logger logger=(Logger)LoggerFactory.getLogger(AgentStageMetrics.class);
    private final ListAppender<ILoggingEvent> captured=new ListAppender<>();
    private final ObjectMapper json=new ObjectMapper();

    /** 每例独享日志收集器，不修改共享日志级别或全局环境变量。 */
    @BeforeEach void capture(){captured.start();logger.addAppender(captured);}
    /** 无论断言是否成功，移除本例收集器，避免影响后续用例。 */
    @AfterEach void release(){logger.detachAppender(captured);captured.stop();}

    /** 固定合成输入只用于控制器测试，不包含真实账号或凭据。 */
    private Map<String,Object> input(){
        return Map.of("turnId","synthetic-completion-turn","result",Map.of("traceId","合成关联原文哨兵"),
            "fence",Map.of("scope",Map.of("userId","42","sessionId","synthetic-completion-session")));
    }

    /** 只接受固定协议字段与摘要；正常完成及异常都计时，开关关闭时不产生计时日志。 */
    private void assertMetric(String status) throws Exception {
        boolean enabled=Boolean.parseBoolean(System.getenv("AG06_STAGE_METRICS_ENABLED"));
        if(!enabled){assertTrue(captured.list.isEmpty());return;}
        assertEquals(1,captured.list.size(),"真实完成事务必须产生恰好一条spring.complete观测");
        String line=captured.list.get(0).getFormattedMessage();
        assertFalse(line.contains("合成关联原文哨兵"));assertFalse(line.contains("synthetic-completion-session"));
        assertTrue(line.startsWith("AG06_METRIC "));
        var metric=json.readTree(line.substring("AG06_METRIC ".length()));
        assertEquals("spring.complete",metric.path("stage").asText());
        assertEquals(status,metric.path("status").asText());
        double duration=metric.path("durationMs").asDouble();
        assertTrue(Double.isFinite(duration)&&duration>=0);assertEquals(24,metric.path("traceKey").asText().length());
    }

    @Test void completionMeasuresOriginalServiceExactlyOnce() throws Exception {
        // 回执返回原对象，观测不能复制、修改结果，也不能重新提交业务。
        var raw=input();var expected=Map.<String,Object>of("state","completed");
        when(turns.complete(42L,"synthetic-completion-session",raw)).thenReturn(expected);
        var response=controller.complete("synthetic-completion-turn","synthetic-token",raw);
        assertEquals(200,response.getStatusCode().value());assertSame(expected,response.getBody());
        verify(jwt).validate("synthetic-token","42");
        verify(turns).complete(42L,"synthetic-completion-session",raw);verifyNoMoreInteractions(turns);
        assertMetric("ok");
    }

    @Test void completionPreservesOriginalFailureWithoutRetry() throws Exception {
        // 任意服务运行时异常原样传播；日志finally不能吞异常或补发第二次完成。
        var raw=input();var original=new IllegalStateException("合成完成失败");
        when(turns.complete(42L,"synthetic-completion-session",raw)).thenThrow(original);
        assertSame(original,assertThrows(IllegalStateException.class,
            ()->controller.complete("synthetic-completion-turn","synthetic-token",raw)));
        verify(turns).complete(42L,"synthetic-completion-session",raw);verifyNoMoreInteractions(turns);
        assertMetric("error");
    }

    @Test void invalidTurnDoesNotReachCompletionOrEmitMetric(){
        // 原协议校验必须在完成事务计时外，非法对象不能被记为完成或触发持久服务。
        var response=controller.complete("different-turn","synthetic-token",input());
        assertEquals(400,response.getStatusCode().value());verifyNoInteractions(turns);assertTrue(captured.list.isEmpty());
    }

    @Test void deniedIdentityDoesNotReachCompletionOrEmitMetric(){
        // JWT拒绝继续使用原403映射，完成事务与观测均不得早于身份校验。
        doThrow(new ToolBoundaryException("FORBIDDEN","合成权限拒绝")).when(jwt).validate("synthetic-token","42");
        var response=controller.complete("synthetic-completion-turn","synthetic-token",input());
        assertEquals(403,response.getStatusCode().value());verifyNoInteractions(turns);assertTrue(captured.list.isEmpty());
    }
}
