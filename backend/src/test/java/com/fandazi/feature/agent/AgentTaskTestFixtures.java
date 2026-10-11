// 目录测试夹具：仅创建合成身份/初始任务；直接种暂停行不是产品控制命令执行证据。
package com.fandazi.feature.agent;
import com.fandazi.feature.auth.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** 共用输入构造，不从被测输出生成金标；每次使用独立scope便于真实库重跑。 */
final class AgentTaskTestFixtures {
    final Long user;final String session;final String epoch;
    private final AgentTaskRegistryService registry;private final JdbcTemplate jdbc;
    /** 身份全部为合成值，不导入实际用户或使用生产密码。 */
    AgentTaskTestFixtures(UserRepository users,AgentSessionService sessions,AgentTaskRegistryService registry,JdbcTemplate jdbc) {
        String id=UUID.randomUUID().toString();user=users.saveAndFlush(new UserEntity("ag03-"+id,"ag03-"+id+"@example.invalid","synthetic-hash")).getId();
        session="ag03-"+id;this.registry=registry;this.jdbc=jdbc;
        epoch=sessions.startTaskSession(user,session,"synthetic-trace").conversationEpoch();
    }
    /** 建立合法套餐选择，目标ID由服务端生成而非测试指定。 */
    Map<String,Object> selection() { return registry.create(user,session,"selection","deal_recommendation","合成套餐选择"); }
    /** 只读返回此合成scope目录。 */
    Map<String,Object> directory() { return registry.directory(user,session); }
    /** 种初始暂停条件；后续真实控制验收不得复用此操作冒充交接成功。 */
    String seedPaused() {
        String task=id(selection());
        jdbc.update("UPDATE agent_conversation_tasks SET lifecycle='paused',active_scope_key=NULL,last_paused_at=CURRENT_TIMESTAMP,stop_kind='waiting_input' WHERE conversation_task_id=?",task);
        jdbc.update("UPDATE agent_sessions SET current_conversation_task_id=NULL,task_focus_version=task_focus_version+1 WHERE session_id=?",session);
        return task;
    }
    /** 提取公开task ID；只用于引用，不能作为授权凭据。 */
    static String id(Map<String,Object> task) { return (String)task.get("conversationTaskId"); }
    /** 提取嵌套公开当前任务。 */
    @SuppressWarnings("unchecked") static Map<String,Object> current(Map<String,Object> directory) { return (Map<String,Object>)directory.get("currentTask"); }
    /** 提取公开暂停列表，仅用于精确数量断言。 */
    @SuppressWarnings("unchecked") static List<Map<String,Object>> paused(Map<String,Object> directory) { return (List<Map<String,Object>>)directory.get("pausedTasks"); }
    /** 提取公开停点，不读取私有执行或准备内容。 */
    @SuppressWarnings("unchecked") static Map<String,Object> stop(Map<String,Object> task) { return (Map<String,Object>)task.get("stopPoint"); }
}
