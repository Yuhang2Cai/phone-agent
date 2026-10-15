// 种子测试数据库隔离探针：在独立JVM建立两个真实Spring/Hibernate上下文，复现共享H2的建表与关闭交错。
package com.fandazi.seed;

import com.fandazi.FandaziApplication;
import com.fandazi.feature.catalog.MerchantRepository;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.stream.Stream;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

/** 只由SeedDisabledTest显式调用，不是生产入口或独立JUnit测试；不读取外部数据库/凭据参数。 */
public final class SeedSchemaIsolationProbe {
    /** 仅在相邻上下文关闭后实际出现H2表不存在42102时返回42，防止把环境失败当预期红测。 */
    public static final int SCHEMA_LOST_EXIT = 42;

    /** 不允许构造；状态只驻留当前隔离测试JVM。 */
    private SeedSchemaIsolationProbe() {}

    /** 使用待测类的真实Spring配置建立主体，再关闭独立的默认认证库上下文。
     * args不接收配置覆盖；正常返回0，指定商户表丢失返回42，其它异常保持原非零错误。
     */
    public static void main(String[] args) {
        String[] subjectArguments = Stream.concat(
                Arrays.stream(SeedDisabledTest.class.getAnnotation(SpringBootTest.class).properties()).map(value -> "--" + value),
                Stream.of("--spring.main.web-application-type=none", "--spring.main.banner-mode=off")).toArray(String[]::new);
        int exit = 0;
        try (ConfigurableApplicationContext subject = new SpringApplicationBuilder(FandaziApplication.class)
                .web(WebApplicationType.NONE).run(subjectArguments)) {
            MerchantRepository merchants = subject.getBean(MerchantRepository.class);
            if (merchants.count() != 0) throw new IllegalStateException("主体种子关闭前置未建立");
            // 在子进程内关闭真实相邻上下文，使Hibernate执行create-drop的drop阶段；不直接造假仓储结果。
            try (ConfigurableApplicationContext unrelated = new SpringApplicationBuilder(FandaziApplication.class)
                    .web(WebApplicationType.NONE).run("--seed.enabled=false", "--spring.main.banner-mode=off",
                            "--spring.datasource.url=jdbc:h2:mem:fandazi-auth;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")) {
                if (unrelated.getBean(MerchantRepository.class).count() != 0) {
                    throw new IllegalStateException("相邻种子关闭前置未建立");
                }
            }
            try {
                if (merchants.count() != 0) throw new IllegalStateException("关闭相邻上下文改变了主体数据");
            } catch (InvalidDataAccessResourceUsageException error) {
                // 严格限定观察到的真实H2缺表，不吞掉认证、连接、DDL或其它SQL环境错误。
                Throwable cause = error.getMostSpecificCause();
                if (cause instanceof SQLException sql && sql.getErrorCode() == 42102) {
                    System.out.println("SEED_CONTEXT_SCHEMA_LOST");
                    exit = SCHEMA_LOST_EXIT;
                } else throw error;
            }
        }
        System.exit(exit);
    }
}
