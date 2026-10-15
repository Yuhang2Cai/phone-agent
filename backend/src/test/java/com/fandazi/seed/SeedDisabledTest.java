// 关闭演示种子的测试：验证真实仓储为空，并检查其它上下文关闭不能销毁本测试的表结构。
package com.fandazi.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.fandazi.feature.catalog.MerchantRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

// 此上下文可能在全量套件中被缓存；使用专属H2库，避免其它create-drop上下文关闭时删除其表。
@SpringBootTest(properties = {"seed.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:seed-disabled-isolated;MODE=MySQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
class SeedDisabledTest {
    @Autowired private MerchantRepository merchants;

    /** 关闭种子开关后，真实商户仓储仍须可读且为空，不能以启动成功替代数据检查。 */
    @Test
    void disabledSeedDoesNotLoadFixtures() {
        assertThat(merchants.count()).isZero();
    }

    /** 在独立测试JVM中真实建表并关闭相邻上下文，再核对主体表仍可读。
     * 子进程隔离H2内存库，避免这项故障回归本身删除全量套件其它活动上下文的表。
     */
    @Test
    void closingAnotherContextMustNotDropThisTestSchema() throws Exception {
        // 输入固定为本次实际测试JVM和完整测试classpath，不接受外部数据库或业务参数。
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path directory = Path.of("target", "surefire-reports");
        Files.createDirectories(directory);
        Path output = Files.createTempFile(directory, "seed-isolation-", ".log");
        Process process = new ProcessBuilder(java, "-Xmx256m", "-Dfile.encoding=UTF-8", "-cp", classpath,
                SeedSchemaIsolationProbe.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        // 诊断必须有界且实际完成。启动/命令错误不是指定红测，单独按环境错误抛出。
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("隔离子进程未在60秒内完成，日志：" + output);
        }
        int code = process.exitValue();
        if (code != 0 && code != SeedSchemaIsolationProbe.SCHEMA_LOST_EXIT) {
            throw new IllegalStateException("隔离场景未建立，退出码=" + code + "，日志：" + output);
        }
        if (code == SeedSchemaIsolationProbe.SCHEMA_LOST_EXIT) {
            assertThat(Files.readString(output, StandardCharsets.UTF_8)).contains("SEED_CONTEXT_SCHEMA_LOST");
        }
        // 只有指定的真实表丢失才能形成本回归红测；修复后必须返回0且保留原种子为空断言。
        assertThat(code).as("其它上下文关闭不得销毁关闭种子测试的表，日志：%s", output).isZero();
    }
}
