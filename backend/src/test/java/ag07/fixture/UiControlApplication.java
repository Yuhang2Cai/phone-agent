package ag07.fixture;

import com.fandazi.FandaziApplication;
import com.fandazi.feature.agent.AgentRequestAdmission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import org.springframework.boot.SpringApplication;

/**
 * AG07-08当前生产Spring的测试启动器：只有stdin私有控制，不添加HTTP业务或模拟响应。
 * 普通容量占用仅一次并自动到期，Android实际请求必须经过正式鉴权、准入和业务接口。
 */
public final class UiControlApplication {
    /** 仅受信父进程可调用；状态读取不会执行模型、数据库写或订单动作。 */
    public static final class Control implements AutoCloseable {
        private final AgentRequestAdmission admission;
        private AgentRequestAdmission.Lease held;
        private boolean used;
        private final ScheduledExecutorService expiry=Executors.newSingleThreadScheduledExecutor(task->{
            Thread thread=new Thread(task,"ag07-ui-owned-expiry");thread.setDaemon(true);return thread;
        });
        /** 保存当前生产Bean，禁止另造准入桶来伪造饱和结果。 */
        public Control(AgentRequestAdmission admission) {this.admission=admission;}
        /** 固定三种操作；普通槽最多持有90秒，重复持有拒绝，释放后不能再开一轮。 */
        public synchronized Map<String,Object> call(String op) {
            if(!Set.of("status","hold_ordinary","release_ordinary").contains(op))
                throw new IllegalArgumentException("unregistered_ui_control");
            if("hold_ordinary".equals(op)) {
                if(used)throw new IllegalStateException("ui_hold_already_consumed");
                used=true;held=admission.acquire("ag07-ui-owned-hold",false);
                expiry.schedule(this::release,90,TimeUnit.SECONDS);
            } else if("release_ordinary".equals(op))release();
            return admission.snapshot();
        }
        /** 只释放自己持有的原许可，finally与到期回调重复执行也不扣别人的计数。 */
        private synchronized void release() {if(held!=null){held.close();held=null;}}
        /** 父进程关闭/EOF后取消本任务到期线程并归还许可，不触及其他应用实例。 */
        @Override public synchronized void close() {release();expiry.shutdownNow();}
    }

    /** 合成目录前置只初始化空会话，调用正式服务分配epoch，不写任务、确认或业务账本。 */
    public static final class SessionBinding {
        private final com.fandazi.feature.agent.AgentSessionService sessions;
        private boolean used;
        /** 注入当前应用生产服务，不能由调用方传SQL或业务结果。 */
        public SessionBinding(com.fandazi.feature.agent.AgentSessionService sessions) {this.sessions=sessions;}
        /** 只接受一个正整数账号和规范UUID；消费后无论成功失败都不重复初始化。 */
        public synchronized Map<String,Object> bind(Map<String,Object> payload) {
            if(used||payload==null||!payload.keySet().equals(Set.of("userId","sessionId"))
                    ||!(payload.get("userId") instanceof Integer || payload.get("userId") instanceof Long)
                    ||((Number)payload.get("userId")).longValue()<=0
                    ||!(payload.get("sessionId") instanceof String session)
                    ||!session.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                throw new IllegalArgumentException("invalid_or_consumed_session_binding");
            used=true;
            sessions.startTaskSession(((Number)payload.get("userId")).longValue(),session,java.util.UUID.randomUUID().toString());
            return Map.of("initialized",true,"businessWrites",0);
        }
    }

    /** 固定专属地址、双开关和UI轮次；任何部署错误均在应用启动前拒绝。 */
    public static void main(String[] args) throws Exception {
        if(!"127.0.0.1".equals(System.getenv("SERVER_ADDRESS"))||!"18073".equals(System.getenv("SERVER_PORT"))
                ||!"ag07-08-bounded-ui".equals(System.getenv("AG07_FIXTURE_MODE"))
                ||!"true".equals(System.getenv("AG07_ADMISSION_ENABLED"))
                ||!"true".equals(System.getenv("AG07_OPERATIONS_ENABLED")))
            throw new IllegalArgumentException("unregistered_ui_fixture");
        Path root=Path.of(System.getenv("AG07_CONTROL_ROOT")).toAbsolutePath().normalize();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(root))
            throw new IllegalArgumentException("private_control_root_required");
        ObjectMapper mapper=new ObjectMapper();int count=0;
        try(var context=new SpringApplication(FandaziApplication.class).run(args);
            var control=new Control(context.getBean(AgentRequestAdmission.class))) {
            var binding=new SessionBinding(context.getBean(com.fandazi.feature.agent.AgentSessionService.class));
            var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in,StandardCharsets.UTF_8));
            for(String line;(line=input.readLine())!=null;) {
                if("STOP".equals(line))break;
                if(++count>32||line.length()>4096)throw new IllegalArgumentException("ui_control_budget");
                Map<String,Object> value=mapper.readValue(line,new TypeReference<>(){});
                if(!(value.keySet().equals(Set.of("id","op"))||value.keySet().equals(Set.of("id","op","payload")))||!(value.get("id") instanceof String id)
                        ||!id.matches("[0-9a-f]{32}")||!(value.get("op") instanceof String op))
                    throw new IllegalArgumentException("invalid_ui_control");
                Object result;
                try {
                    Object answer;
                    if("bind_session".equals(op)) {
                        if(!(value.get("payload") instanceof Map<?,?> raw))throw new IllegalArgumentException("binding_payload_required");
                        @SuppressWarnings("unchecked") Map<String,Object> payload=(Map<String,Object>)raw;
                        answer=binding.bind(payload);
                    } else {
                        if(value.containsKey("payload"))throw new IllegalArgumentException("unexpected_payload");
                        answer=control.call(op);
                    }
                    result=Map.of("status","ok","result",answer);
                }
                catch(RuntimeException failure) {result=Map.of("status","failed","errorType",failure.getClass().getSimpleName());}
                Files.write(root.resolve(id+".json"),mapper.writeValueAsBytes(result),StandardOpenOption.CREATE_NEW);
            }
        }
    }
}
