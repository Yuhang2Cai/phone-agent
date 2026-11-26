// 应用入口：初始化运行所需的组件与配置。
package com.fandazi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

// 默认只注册团购服务；旧实体和Repository保留历史审计，旧交易组件不会因换环境重新启用。
@org.springframework.context.annotation.ComponentScan(basePackages = "com.fandazi",
    excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
        type = org.springframework.context.annotation.FilterType.REGEX,
        pattern = {"com\\.fandazi\\.feature\\.(address|cart|order|payment|delivery|automation)\\..*",
                   "com\\.fandazi\\.feature\\.agent\\.(AgentToolService|InternalToolController)",
                   "com\\.fandazi\\.feature\\.catalog\\.MerchantController"}))
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class FandaziApplication {

// 启动应用并连接运行所需组件。
    public static void main(String[] args) {
        SpringApplication.run(FandaziApplication.class, args);
    }
}
