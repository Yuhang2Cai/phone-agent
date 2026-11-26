// TL-09测试进程启动器：完整生产Spring组件及显式鉴权的前置接口，不覆盖产品实现或进入产品包。
package ag03.online;

import com.fandazi.FandaziApplication;
import com.fandazi.feature.agent.AgentTaskOnlineFixtureBridge;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.web.bind.annotation.*;

/** 配置在生产扫描范围之外；仅本地测试classpath显式启动时存在。 */
public final class TaskOnlineFixtureApplication {
    /** 启动真实应用，监听和数据库均由已登记本地环境提供。 */
    public static void main(String[] args){new SpringApplication(FandaziApplication.class,FixtureConfiguration.class).run(args);}
    /** 明确注册前置接口，不覆盖已有服务Bean。 */
    @Configuration static class FixtureConfiguration {
        /** 桥接真实容器，操作不接受金标或成功计数。 */
        @Bean FixtureController fixtureController(ApplicationContext context){return new FixtureController(new AgentTaskOnlineFixtureBridge(context));}
    }
    /** 私有测试前置接口复用内部JWT主体校验；真实消息和控制仍走公开产品HTTP入口。 */
    @RestController @RequestMapping("/internal/v2/task-admissions/tl09-fixture") static class FixtureController {
        private final AgentTaskOnlineFixtureBridge bridge;
        /** 保存真实桥，不根据请求切换实现。 */
        FixtureController(AgentTaskOnlineFixtureBridge bridge){this.bridge=bridge;}
        /** 先鉴权后准备合成scope；异常不转为成功。 */
        @PostMapping public Object execute(@RequestHeader(value="Authorization",required=false)String authorization,@RequestBody Map<String,Object> raw){
            bridge.authorize(authorization);return bridge.call(raw);
        }
    }
}
