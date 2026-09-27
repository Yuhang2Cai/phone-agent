// 应用入口：初始化运行所需的组件与配置。
package com.fandazi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class FandaziApplication {

// 启动应用并连接运行所需组件。
    public static void main(String[] args) {
        SpringApplication.run(FandaziApplication.class, args);
    }
}
