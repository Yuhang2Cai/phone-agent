// Task8 图片边界数据：创建两个全新账号及订单，只在新克隆套餐配置受控图片地址，不修改已有图片或资格。
import com.fandazi.FandaziApplication;
import com.fandazi.core.identity.PublicIdAliases;
import com.fandazi.core.time.BusinessTime;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.DealPackageEntity;
import com.fandazi.feature.catalog.DealPackageRepository;
import com.fandazi.feature.grouporder.GroupBuyOrderEntity;
import com.fandazi.feature.grouporder.GroupBuyOrderRepository;
import com.fandazi.feature.grouporder.GroupBuyOrderStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.springframework.boot.SpringApplication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 仅开发验证执行，评价资格来自新订单的受控开发事实，不实现或开放业务核销入口。 */
public class PrepareImages {
    /** 输出新私有清单；异常即回滚数据库事务，不覆盖旧清单、不重跑种子初始化。 */
    public static void main(String[] args) throws Exception {
        var output = Path.of("C:/fdz-build/.runtime/task8-resumed/fixtures-images.json");
        if (Files.exists(output)) throw new IllegalStateException("不能覆盖图片夹具");
        try (var context = SpringApplication.run(FandaziApplication.class, "--server.port=0", "--server.address=127.0.0.1",
                "--spring.profiles.active=development", "--seed.enabled=false", "--task2.fixtures.enabled=false", "--task8.failures.enabled=false")) {
            try (var connection = context.getBean(javax.sql.DataSource.class).getConnection()) {
                if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName()) || !"fandazi_groupbuy_dev".equals(connection.getCatalog()))
                    throw new IllegalStateException("只允许团购开发 MySQL");
            }
            var users = context.getBean(UserRepository.class);
            var deals = context.getBean(DealPackageRepository.class);
            var orders = context.getBean(GroupBuyOrderRepository.class);
            var aliases = context.getBean(PublicIdAliases.class);
            var passwords = context.getBean(PasswordEncoder.class);
            var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            var manifest = transaction.execute(status -> {
                var entries = new ArrayList<Map<String,Object>>();
                for (String scenario : List.of("refundable", "reviewable")) {
                    String username = "image8-" + UUID.randomUUID();
                    String password = UUID.randomUUID().toString() + UUID.randomUUID();
                    var user = users.saveAndFlush(new UserEntity(username, username + "@test.invalid", passwords.encode(password)));
                    var source = deals.findByPublicId(aliases.require("deal", scenario.equals("refundable") ? "deal-002-1" : "deal-001-1")).orElseThrow();
                    var deal = source;
                    if (scenario.equals("reviewable")) {
                        // 克隆只属于新账号的测试套餐，保持下架，避免污染公开发现列表；照片字节源自此套餐原图。
                        deal = new DealPackageEntity(UUID.randomUUID().toString(), source.getStore(), source.getTitle(), source.getDescription(),
                                source.getPriceCents(), source.getOriginalPriceCents(), 2, source.getValidFrom(), source.getValidTo(), false, 2, 0);
                        deal.replaceImage("http://10.0.2.2:8768/meal.jpg", source.getImageAlt());
                        deal = deals.saveAndFlush(deal);
                    } else if (source.getImageUrl() != null) throw new IllegalStateException("缺图场景必须使用真实 null 图片套餐");
                    var order = orders.saveAndFlush(new GroupBuyOrderEntity(UUID.randomUUID().toString(), user, deal.getStore(), deal,
                            1, deal.getPriceCents(), GroupBuyOrderStatus.PAID, BusinessTime.now().plusDays(30)));
                    if (scenario.equals("reviewable")) order.getVouchers().get(0).redeem(deal.getStore().getPublicId());
                    orders.flush();
                    entries.add(Map.of("scenario", scenario, "username", username, "password", password, "orderId", order.getPublicId(),
                            "voucherIds", order.getVouchers().stream().map(v -> v.getPublicId()).toList()));
                }
                return Map.of("schemaVersion", "task8-image-dev-1", "scenarios", entries);
            });
            Files.writeString(output, context.getBean(ObjectMapper.class).writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            System.out.println("两个新图片场景准备完成，旧资源未修改");
        }
    }
}
