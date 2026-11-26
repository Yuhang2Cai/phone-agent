package com.fandazi.feature.agent;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 本机健康投影只披露已观测事实，不把默认关闭/缺来源解释为业务健康。 */
class AgentOperationsHealthTest {
    /** 反射先验证缺实现的指定断言，避免把编译错误当有效红测。 */
    @SuppressWarnings("unchecked")
    Map<String,Object> project(Map<String,Object> source) throws Exception {
        Class<?> type=assertDoesNotThrow(()->Class.forName("com.fandazi.feature.agent.AgentOperationsHealth"),
            "缺少AG07本机健康投影");
        return (Map<String,Object>) type.getMethod("project",Map.class).invoke(null,source);
    }
    @Test void unknownStateIsNotHealthyAndSensitiveFieldsExcluded() throws Exception {
        // 未取得控制/资源来源保持unknown，密码及任意字段不进入输出。
        var state=project(Map.of("enabled",false,"password","SECRET_SENTINEL"));
        assertEquals("disabled",state.get("monitor_status"));
        assertEquals("unknown",state.get("control_coverage"));
        assertFalse(state.toString().contains("SECRET_SENTINEL"));
    }
    @Test void knownLossOrUnfinishedCloseIsDegraded() throws Exception {
        // 已确认写失败与关闭后仍存活单列，不能因队列暂为空就报正常退出。
        var state=project(Map.of("enabled",true,"dropped",1L,"sink_error",0L,"closed",true,"worker_alive",true));
        assertEquals("monitor_degraded",state.get("monitor_status"));
        assertEquals(true,state.get("worker_alive"));
    }
    @Test void countersAndImmutableCopyArePreserved() throws Exception {
        // 投影不持有原Map，缺少last_flush仍不可判断，不能伪造时间。
        var source=new HashMap<String,Object>();source.put("accepted",2L);source.put("written",2L);
        var state=project(source);source.put("accepted",9L);
        assertEquals(2L,state.get("accepted"));assertNull(state.get("last_flush"));
        assertEquals("no_data",state.get("monitor_status"));
    }
}
