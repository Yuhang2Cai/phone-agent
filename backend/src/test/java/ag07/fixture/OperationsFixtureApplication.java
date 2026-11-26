package ag07.fixture;

import com.fandazi.FandaziApplication;
import com.fandazi.feature.agent.AgentTaskLiveFixtureBridge;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * AG07零模型工具组合启动器，仅在测试classpath，复用真实产品及已有合成前置桥。
 * 控制端通过私有stdin结束当前上下文，允许运维sink和数据库池正常回收，不开停机HTTP接口。
 */
public final class OperationsFixtureApplication {
    /** 固定本机端口与测试模式；不允许误部署到默认服务或其他任务地址。 */
    public static void main(String[] args) throws Exception {
        if(!"127.0.0.1".equals(System.getenv("SERVER_ADDRESS")) || !"18073".equals(System.getenv("SERVER_PORT"))
                || !"ag07-02-zero-model".equals(System.getenv("AG07_FIXTURE_MODE")))
            throw new IllegalArgumentException("unregistered_fixture_target");
        try(var context=new SpringApplication(FandaziApplication.class,FixtureConfiguration.class).run(args)) {
            // 只有创建本进程的控制端持有stdin；EOF同样退出，异常不遗留服务。
            new java.io.BufferedReader(new java.io.InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8)).readLine();
        }
    }

    /** 不在产品组件扫描包内，不会出现在生产JAR。 */
    @Configuration
    static class FixtureConfiguration {
        /** 原桥负责专用JWT和产品事务；没有模拟仓库、ACK或成功结果。 */
        @Bean FixtureController fixtureController(ApplicationContext context) {
            return new FixtureController(new AgentTaskLiveFixtureBridge(context));
        }
    }

    /** 附加入口只允许一次外部预算登记的准备和数据库身份读取，写调用仍走产品工具HTTP。 */
    @RestController
    @RequestMapping("/internal/v2/task-admissions/tl08-fixture")
    static class FixtureController {
        private final AgentTaskLiveFixtureBridge bridge;
        private boolean seeded;
        /** 原桥注入，不接受请求提供实现或目标库。 */
        FixtureController(AgentTaskLiveFixtureBridge bridge) {this.bridge=bridge;}
        /** 种子最多一次且仅purchase；此步骤准备报价/确认但不创建订单。 */
        @PostMapping public synchronized Object call(@RequestHeader(value="Authorization",required=false) String authorization,
                                                     @RequestBody Map<String,Object> raw) {
            bridge.authorize(authorization);
            if("database".equals(raw.get("op")))return bridge.call(raw);
            if(!"seed".equals(raw.get("op")) || !"purchase".equals(raw.get("kind")) || seeded)
                throw new IllegalArgumentException("fixture_budget_or_action_rejected");
            seeded=true;
            return bridge.call(raw);
        }
    }
}
