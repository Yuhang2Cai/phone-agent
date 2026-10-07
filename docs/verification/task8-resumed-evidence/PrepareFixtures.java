// Task8 隔离夹具启动器：复用已有开发夹具服务，只创建新账号的新事实，不开启通用种子初始化。
import com.fandazi.FandaziApplication;
import com.fandazi.core.identity.PublicIdAliases;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.DealPackageRepository;
import com.fandazi.feature.grouporder.GroupBuyOrderRepository;
import com.fandazi.feature.refund.RefundService;
import com.fandazi.feature.review.ReviewService;
import com.fandazi.seed.Task2DevelopmentFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

/** 单次开发验证入口；输出凭据只能进入新 .runtime 文件，完成后关闭独立临时服务。 */
public class PrepareFixtures {
    /** 接收新输出路径；先防覆盖，再建立仅本进程的随机端口开发上下文，任何异常中止。 */
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(output) || !output.toString().replace('\\', '/').contains("/.runtime/task8-resumed/")) {
            throw new IllegalArgumentException("仅允许全新的 Task8 私有夹具路径");
        }
        // 命令行优先级高于本机环境：禁止通用种子和自动夹具，避免覆盖旧清单及业务目录。
        try (var context = SpringApplication.run(FandaziApplication.class,
                "--spring.profiles.active=development", "--server.port=0", "--server.address=127.0.0.1",
                "--seed.enabled=false", "--task2.fixtures.enabled=false", "--task8.failures.enabled=false")) {
            // 类路径若混入 H2 测试配置必须立即失败；不会向错误数据库准备可被误当成真实测试的记录。
            try (var connection = context.getBean(javax.sql.DataSource.class).getConnection()) {
                if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())
                        || !"fandazi_groupbuy_dev".equals(connection.getCatalog())) {
                    throw new IllegalStateException("夹具仅允许连接开发团购 MySQL 库");
                }
            }
            var fixture = new Task2DevelopmentFixtures(context.getBean(ObjectMapper.class),
                    context.getBean(PublicIdAliases.class), context.getBean(UserRepository.class),
                    context.getBean(DealPackageRepository.class), context.getBean(GroupBuyOrderRepository.class),
                    context.getBean(RefundService.class), context.getBean(ReviewService.class),
                    context.getBean(PasswordEncoder.class), context.getBean(PlatformTransactionManager.class),
                    "C:/fdz-build/data/seed", output.toString());
            // 明确复用既有受控开发事实：评价资格仅为新隔离资源准备，不交付核销业务入口。
            fixture.run(new DefaultApplicationArguments());
            System.out.println("Task8 新隔离七场景已建立；旧清单和业务行保持不变。");
        }
    }
}
