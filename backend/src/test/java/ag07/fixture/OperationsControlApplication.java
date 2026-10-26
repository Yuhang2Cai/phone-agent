package ag07.fixture;

import com.fandazi.FandaziApplication;
import com.fandazi.feature.agent.AgentTaskLiveFixtureBridge;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.springframework.boot.SpringApplication;

/**
 * AG07受信测试启动器：私有stdin维护/读状态/有限前置，不新增生产HTTP管理接口。
 * 仅位于测试classpath；可与固定AG06产品classpath组合，旧版本无准入Bean时明确报告未覆盖。
 */
public final class OperationsControlApplication {
    /**
     * 真实受理后补齐生产网关的历史关联，再回读验证；只用于合成停点准备，不执行模型或工具。
     * 原来源/轮次不重建、不改写，历史写失败或权威回读不完整直接抛错，禁止继续Agent认领。
     */
    @SuppressWarnings("unchecked")
    public static com.fandazi.feature.agent.AgentTaskTurnService.Accepted admitRecorded(
            org.springframework.context.ApplicationContext context,Map<String,Object> payload) {
        var turns=context.getBean(com.fandazi.feature.agent.AgentTaskTurnService.class);
        var history=context.getBean(com.fandazi.feature.agent.AgentHistoryRecordingService.class);
        Long user=Long.valueOf((String)payload.get("userId"));String session=(String)payload.get("sessionId");
        var input=(Map<String,Object>)payload.get("input");
        // 先通过产品服务校验归属/CAS并受理，不能在已有失败认领上回填来源凭据。
        var accepted=turns.admitTurn(user,session,new com.fandazi.feature.agent.AgentTaskTurnService.Input(
            (String)input.get("requestId"),(String)input.get("body"),(String)input.get("taskId"),
            ((Number)input.get("taskVersion")).longValue(),((Number)input.get("focusVersion")).longValue(),Boolean.TRUE.equals(input.get("executable"))));
        var fence=(Map<String,Object>)accepted.admission().get("fence");
        String epoch=(String)((Map<String,Object>)fence.get("scope")).get("conversationEpoch");
        var authority=turns.readAuthority(user,session,epoch,accepted.turnId());
        String source=(String)((Map<String,Object>)authority.get("source")).get("sourceMessageId");
        String message=history.recordUserLinkedSource(user,session,(String)fence.get("runId"),(String)authority.get("traceId"),
            (String)input.get("body"),(String)input.get("requestId"),source);
        if(message==null)throw new IllegalStateException("fixture_history_not_recorded");
        // 必须采用同一真实来源的提交后回读，返回值中的标记不能由测试代码置真。
        var recorded=(Map<String,Object>)turns.readAuthority(user,session,epoch,accepted.turnId()).get("source");
        if(!Boolean.TRUE.equals(recorded.get("historyRecorded"))||!source.equals(recorded.get("sourceMessageId")))
            throw new IllegalStateException("fixture_history_evidence_incomplete");
        return accepted;
    }
    /** 固定命令集合，不接受shell、类名、反射方法或输出路径。 */
    private static final Set<String> COMMANDS=Set.of("status","maintenance_on","maintenance_off","bridge");
    /** 仅合成前置和原键只读；不提供工具执行、确认grant或任意SQL入口。 */
    private static final Set<String> BRIDGE=Set.of("database","seed","directory","goalProjection","goalRecoveryRead","admit");

    /** 校验外层安全ID、字段及原桥范围；异常前不会调用Spring或写回文件。 */
    @SuppressWarnings("unchecked")
    public static void validateCommand(Map<String,Object> value) {
        if(value==null||!(value.get("id") instanceof String id)||!id.matches("[0-9a-f]{32}")
                ||!(value.get("op") instanceof String op)||!COMMANDS.contains(op))
            throw new IllegalArgumentException("invalid_control_command");
        if(!value.keySet().equals("bridge".equals(op)?Set.of("id","op","payload"):Set.of("id","op")))
            throw new IllegalArgumentException("invalid_control_fields");
        if("bridge".equals(op)) {
            if(!(value.get("payload") instanceof Map<?,?> payload)||!BRIDGE.contains(payload.get("op")))
                throw new IllegalArgumentException("unregistered_fixture_operation");
            if("seed".equals(payload.get("op"))&&(!"goal-recovery".equals(payload.get("kind"))
                    ||!payload.keySet().equals(Set.of("op","kind"))))
                throw new IllegalArgumentException("unregistered_fixture_seed");
        }
    }

    /** 精确回环目标/模式检查后启动；STOP或EOF正常关闭上下文，超时由父驱动保持失败。 */
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        if(!"127.0.0.1".equals(System.getenv("SERVER_ADDRESS"))||!"18073".equals(System.getenv("SERVER_PORT"))
                ||!"ag07-07-zero-model".equals(System.getenv("AG07_FIXTURE_MODE")))
            throw new IllegalArgumentException("unregistered_control_fixture");
        Path root=Path.of(System.getenv("AG07_CONTROL_ROOT")).toAbsolutePath().normalize();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root))
            throw new IllegalArgumentException("private_control_root_required");
        ObjectMapper mapper=new ObjectMapper();boolean seeded=false;int count=0;
        try(var context=new SpringApplication(FandaziApplication.class).run(args)) {
            var bridge=new AgentTaskLiveFixtureBridge(context);
            Object admission=null;
            // AG06没有AG07准入Bean，不能伪造维护成功；旧版本依赖外层独占入口证明。
            try {admission=context.getBean(Class.forName("com.fandazi.feature.agent.AgentRequestAdmission"));}
            catch(ClassNotFoundException unavailable) {
                if(!"ag06-stable".equals(System.getenv("AG07_RELEASE_KIND")))throw unavailable;
            }
            var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,StandardCharsets.UTF_8));
            for(String line;(line=input.readLine())!=null;) {
                if("STOP".equals(line))break;
                if(++count>64||line.length()>65536)throw new IllegalArgumentException("control_budget_exceeded");
                Map<String,Object> command=mapper.readValue(line,new TypeReference<>() {});validateCommand(command);
                String op=(String)command.get("op");Object result;
                try {
                    if("bridge".equals(op)) {
                        Map<String,Object> payload=(Map<String,Object>)command.get("payload");
                        if("seed".equals(payload.get("op"))) {
                            if(seeded||!"candidate".equals(System.getenv("AG07_RELEASE_KIND")))
                                throw new IllegalArgumentException("seed_already_used_or_baseline");
                            seeded=true;
                        }
                        result="admit".equals(payload.get("op"))?admitRecorded(context,payload):bridge.call(payload);
                    } else if("status".equals(op)) {
                        result=Map.of("pid",ProcessHandle.current().pid(),"contextActive",context.isActive(),
                            "admission",admission==null?Map.of("coverage","unsupported"):
                                admission.getClass().getMethod("snapshot").invoke(admission));
                    } else {
                        if(admission==null)throw new IllegalArgumentException("baseline_requires_external_ingress");
                        admission.getClass().getMethod("setMaintenance",boolean.class).invoke(admission,"maintenance_on".equals(op));
                        result=admission.getClass().getMethod("snapshot").invoke(admission);
                    }
                    result=Map.of("status","ok","result",result);
                } catch(Exception error) {
                    // 失败保存固定类型，不把SQL/连接凭据异常正文暴露到证据摘要。
                    result=Map.of("status","failed","errorType",error.getClass().getSimpleName());
                }
                // 文件名由已核随机ID组成，独占创建拒绝覆盖其他轮次回执。
                Files.write(root.resolve(command.get("id")+".json"),mapper.writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);
            }
        }
    }
}
