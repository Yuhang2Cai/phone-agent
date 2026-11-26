// 目录餐图映射测试模块：通过真实脚本接口验证图片回填事务，不连接用户库或默认数据库。
package com.fandazi.feature.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 按规格执行五个独立的内存库场景。测试脚本是正式回填脚本的消费者，
 * Python 运行时由 catalogPython 参数指定，进程没有数据库口令。
 */
class CatalogImageMappingTest {
    /** 同前缀的不同菜品必须按主键区分，并保留价格。 */
    @Test void exactEntityIdDoesNotOverwriteSimilarName() throws Exception { run("exact_id"); }

    /** 目标主键缺失必须报错，不允许把整批当作成功。 */
    @Test void missingEntityIsRejected() throws Exception { run("missing_id"); }

    /** 旧值不同于审核快照必须停止，避免覆盖运营期修改。 */
    @Test void conflictingOldValueStopsApply() throws Exception { run("conflict"); }

    /** 再次应用相同清单只能产生零行变更。 */
    @Test void repeatedApplyIsIdempotent() throws Exception { run("idempotent"); }

    /** 批量中途失败不能留下一条已更新的图片。 */
    @Test void failedBatchRollsBackAndPreservesTradeFields() throws Exception { run("rollback"); }

    /**
     * 启动脚本的内存库场景，并把完整断言输出纳入 JUnit 失败信息。
     * 输入是固定场景名；输出是对应进程的退出码，非零意味着业务断言或环境故障。
     */
    private void run(String scenario) throws IOException, InterruptedException {
        String python = System.getProperty("catalogPython", "python");
        Path script = Path.of("..", "scripts", "Test-CatalogImages.py").toAbsolutePath().normalize();
        Process process = new ProcessBuilder(python, script.toString(), scenario)
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int status = process.waitFor();
        assertEquals(0, status, "场景 " + scenario + " 未通过：\n" + output);
    }
}
