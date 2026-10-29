package com.fandazi.feature.support;

import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** 知识维护入口：正常启动只初始化审核包，显式无Web命令维护版本；不注册模型或用户HTTP工具。 */
@Component
public class KnowledgeMaintenance implements ApplicationRunner {
    /** 文件中的发布身份、审核记录和完整条目，解析后仍须目录层做事务内版本校验。 */
    public record Publication(String releaseId, String approval, List<KnowledgeCatalog.Entry> entries) {}
    private final KnowledgeCatalog catalog;
    private final Environment environment;
    private final ConfigurableApplicationContext context;
    /** 运维只读取显式本机文件/部署环境，不从聊天内容或模型结果取得命令。 */
    public KnowledgeMaintenance(KnowledgeCatalog catalog, Environment environment, ConfigurableApplicationContext context) {
        this.catalog = catalog; this.environment = environment; this.context = context;
    }
    /** 正常启动与单次管理命令共享权威目录；本入口不打印内容、凭据或任意用户正文。 */
    @Override public void run(ApplicationArguments arguments) {
        String action = environment.getProperty("knowledge.command", "").strip();
        if (action.isEmpty()) {
            // 首次启动只读取随精确源码发布的已审核文件；其它实例或后续重启不会改活动指针。
            try (var input = new org.springframework.core.io.ClassPathResource("knowledge/reviewed-r1.json").getInputStream()) {
                Publication publication = parse(input.readAllBytes());
                catalog.initialize(publication.releaseId(), publication.approval(), publication.entries());
                return;
            } catch (java.io.IOException failure) {
                throw new IllegalStateException("随包审核知识读取失败", failure);
            }
        }
        // 管理命令必须以不监听HTTP的独立进程执行；普通Web部署不能因残留参数改变知识。
        if (!"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type", "")))
            throw new IllegalArgumentException("知识管理命令要求显式无Web模式");
        switch (action) {
            case "publish" -> {
                var file = java.nio.file.Path.of(required("knowledge.file")).toAbsolutePath().normalize();
                try {
                    if (!java.nio.file.Files.isRegularFile(file) || java.nio.file.Files.size(file) > 2_000_000)
                        throw new IllegalArgumentException("审核文件不存在或超过上限");
                    Publication publication = parse(java.nio.file.Files.readAllBytes(file));
                    catalog.publish(publication.releaseId(), publication.approval(), publication.entries());
                } catch (java.io.IOException failure) {
                    throw new IllegalArgumentException("审核文件无法读取", failure);
                }
            }
            case "activate" -> catalog.activate(required("knowledge.release"));
            case "revoke" -> catalog.revoke(required("knowledge.entry"), required("knowledge.version"), required("knowledge.reason"));
            default -> throw new IllegalArgumentException("未知知识管理命令");
        }
        // 事务返回后才记录成功；不输出文件正文、数据库地址或认证配置。无Web命令完成即关自有上下文。
        org.slf4j.LoggerFactory.getLogger(getClass()).info("知识管理命令完成 action={}", action);
        context.close();
    }
    /** 严格文件结构由Jackson拒绝未知字段，完整元数据再交目录校验；不采信模型审批声明。 */
    private Publication parse(byte[] bytes) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().readValue(bytes, Publication.class); }
        catch (java.io.IOException failure) { throw new IllegalArgumentException("审核知识文件结构无效", failure); }
    }
    /** 只读取部署操作者显式参数，不从用户请求推导管理命令。 */
    private String required(String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少管理参数：" + property);
        return value;
    }
}
