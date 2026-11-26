// TL-08测试进程启动器：真实Spring应用与数据库，只额外挂载本地鉴权夹具入口，不打入产品JAR。
package ag03.fixture;

import com.fandazi.FandaziApplication;
import com.fandazi.feature.agent.AgentTaskLiveFixtureBridge;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.*;

/** 只从显式测试classpath启动，监听地址由驱动固定127.0.0.1；生产组件扫描不包含ag03.fixture。 */
public final class TaskFaultFixtureApplication {
    /** 启动完整产品服务；附加配置仅用于准备前置和停在可观察事务边界。 */
    public static void main(String[] args){
        var application=new SpringApplication(FandaziApplication.class,FixtureConfiguration.class);application.run(args);
    }
    /** 配置不参与正常应用扫描；必须由本测试启动器显式注册。 */
    @Configuration
    static class FixtureConfiguration {
        /** 所有操作依赖真实Bean，不能以测试配置覆盖产品协调器/工具/数据库。 */
        @Bean FixtureController fixtureController(ApplicationContext context){
            return new FixtureController(new AgentTaskLiveFixtureBridge(context));
        }
    }
    /** 复用专用短期内部JWT；该入口在产品产物中不存在，不接收无凭证夹具创建。 */
    @RestController
    @RequestMapping("/internal/v2/task-admissions/tl08-fixture")
    static class FixtureController {
        private final AgentTaskLiveFixtureBridge bridge;
        /** 鉴权与真实服务桥分别注入，不由请求选择实现或数据库地址。 */
        FixtureController(AgentTaskLiveFixtureBridge bridge){this.bridge=bridge;}
        /** 先鉴权再执行夹具命令；固定合成驱动主体，不输出密钥、SQL或异常正文。 */
        @PostMapping public Object execute(@RequestHeader(value="Authorization",required=false)String authorization,@RequestBody Map<String,Object> raw){
            bridge.authorize(authorization);return bridge.call(raw);
        }
    }
}
