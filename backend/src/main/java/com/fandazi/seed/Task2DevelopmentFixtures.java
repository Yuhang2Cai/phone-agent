// Task2 开发状态准备：七个隔离随机账号及订单/券/退款/核销事实；依赖种子别名、真实领域服务和事务管理器。
package com.fandazi.seed;

import com.fandazi.core.identity.PublicIdAliases;
import com.fandazi.core.time.BusinessTime;
import com.fandazi.feature.auth.*;
import com.fandazi.feature.catalog.DealPackageRepository;
import com.fandazi.feature.grouporder.*;
import com.fandazi.feature.refund.RefundService;
import com.fandazi.feature.review.ReviewService;
import com.fandazi.feature.voucher.VoucherStatus;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 无 HTTP Controller；仅明确 development profile、启用开关和种子时执行，prod/production 同时出现也禁止。 */
@Component
@Profile("development & !production & !prod")
@ConditionalOnProperty(name = {"task2.fixtures.enabled", "seed.enabled"}, havingValue = "true")
@Order(100)
public class Task2DevelopmentFixtures implements ApplicationRunner {
    /** 固定七个状态，文件不能扩展为任意伪造业务入口。 */
    private static final Set<String> SCENARIOS = Set.of("no_orders", "pending_payment", "refundable", "refund_requested",
            "reviewable", "reviewed", "no_candidates");
    private final ObjectMapper mapper;
    private final PublicIdAliases aliases;
    private final UserRepository users;
    private final DealPackageRepository deals;
    private final GroupBuyOrderRepository orders;
    private final RefundService refunds;
    private final ReviewService reviews;
    private final PasswordEncoder passwords;
    private final TransactionTemplate transaction;
    private final Path definitions;
    private final Path output;

    /** 注入已验证的业务服务与文件位置；不接受网络请求提供状态、用户或金额。 */
    public Task2DevelopmentFixtures(ObjectMapper mapper, PublicIdAliases aliases, UserRepository users,
            DealPackageRepository deals, GroupBuyOrderRepository orders, RefundService refunds, ReviewService reviews,
            PasswordEncoder passwords, PlatformTransactionManager manager,
            @Value("${seed.path:../data/seed}") String seedPath,
            @Value("${task2.fixtures.output:../.runtime/task2-fixtures.json}") String outputPath) {
        this.mapper = mapper; this.aliases = aliases; this.users = users; this.deals = deals; this.orders = orders;
        this.refunds = refunds; this.reviews = reviews; this.passwords = passwords;
        this.transaction = new TransactionTemplate(manager);
        this.definitions = Path.of(seedPath).resolve("task2-development-fixtures.json");
        this.output = Path.of(outputPath).toAbsolutePath().normalize();
    }

    /** 种子完成后准备场景，事务提交再原子写运行清单；文件失败明确中断开发启动且不输出凭据。 */
    @Override public void run(ApplicationArguments arguments) throws Exception {
        // 输入边界：凭据只允许放在 .runtime 路径内，先校验目标再进行数据库写入。
        boolean runtime = false;
        for (Path part : output) if (part.toString().equals(".runtime")) runtime = true;
        if (!runtime) throw new IllegalArgumentException("Task2 凭据输出必须位于忽略的 .runtime 目录");
        JsonNode source = mapper.readTree(Files.readString(definitions));
        if (!source.isArray() || source.size() != SCENARIOS.size()) throw new IllegalArgumentException("Task2 必须包含七种隔离场景");
        Set<String> seen = new HashSet<>();
        for (var fixture : source) if (!SCENARIOS.contains(fixture.path("scenario").asText()) || !seen.add(fixture.path("scenario").asText()))
            throw new IllegalArgumentException("Task2 场景名称未知或重复");
        Map<String, Object> manifest = transaction.execute(status -> prepare(source));
        // 文件只在数据库事务成功后发布，避免把已回滚账号的密码交给下一轮 UI 开发。
        Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), "task2-fixtures-", ".tmp");
        try {
            Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            try { Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    /** 一个事务建立七个新账号；每次明确开启开发启动都产生新清单，不删除或重置原有用户数据。 */
    private Map<String, Object> prepare(JsonNode source) {
        List<Map<String, Object>> scenarios = new ArrayList<>();
        SecureRandom random = new SecureRandom();
        for (var fixture : source) {
            String scenario = fixture.path("scenario").asText();
            String username = "t2-" + scenario + "-" + UUID.randomUUID().toString().substring(0, 12);
            byte[] bytes = new byte[32]; random.nextBytes(bytes);
            String password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            var user = users.saveAndFlush(new UserEntity(username, username + "@task2.invalid", passwords.encode(password)));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("scenario", scenario); entry.put("username", username); entry.put("password", password);
            entry.put("orderId", null); entry.put("voucherIds", List.of()); entry.put("requestId", null);
            // 无订单场景只建立账号；其他场景选择同一份真实目录实体，不复制前端展示数据。
            if (!scenario.equals("no_orders")) prepareOrder(fixture, scenario, user, entry);
            scenarios.add(entry);
        }
        return Map.of("schemaVersion", "task2-dev-1", "createdAt", Instant.now().toString(), "scenarios", scenarios);
    }

    /** 建立一单一账号的初始事实，金额 10001 分专门覆盖三券余分；核销只在后端开发数据中执行。 */
    private void prepareOrder(JsonNode fixture, String scenario, UserEntity user, Map<String, Object> entry) {
        var deal = deals.findByPublicId(aliases.require("deal", fixture.path("deal").asText())).orElseThrow();
        int quantity = fixture.path("quantity").asInt(); int cents = fixture.path("totalCents").asInt();
        if (quantity < 1 || cents < 0) throw new IllegalArgumentException("Task2 夹具数量或金额非法");
        var status = scenario.equals("pending_payment") ? GroupBuyOrderStatus.PENDING_PAYMENT : GroupBuyOrderStatus.PAID;
        var expires = scenario.equals("no_candidates") ? BusinessTime.now().minusDays(1) : BusinessTime.now().plusDays(30);
        var order = orders.saveAndFlush(new GroupBuyOrderEntity(UUID.randomUUID().toString(), user, deal.getStore(), deal,
                quantity, cents, status, expires));
        // 评价资格必须来自实际持久化核销事实，不能把订单状态文字或客户端操作当作核销证据。
        if (scenario.equals("reviewable") || scenario.equals("reviewed")) order.getVouchers().get(0).redeem(deal.getStore().getPublicId());
        if (scenario.equals("reviewed")) reviews.create(user.getId(), order.getPublicId(), 4, "开发隔离场景：已消费评价");
        if (scenario.equals("no_candidates")) order.getVouchers().get(0).restoreSeedStatus(VoucherStatus.EXPIRED);
        if (scenario.equals("refund_requested")) {
            String session = "task2-fixture-" + UUID.randomUUID(); String voucherId = order.getVouchers().get(0).getPublicId();
            var prepared = refunds.prepare(user.getId(), session, voucherId);
            // 已申请场景通过真实确认与资格服务写入 REQUESTED，不能直接伪造到账或退款成功。
            var requested = refunds.request(user.getId(), session, voucherId, "开发隔离场景：行程变化", prepared.get("confirmationGrant").toString());
            entry.put("requestId", requested.get("requestId"));
        }
        orders.flush();
        entry.put("orderId", order.getPublicId()); entry.put("storeId", deal.getStore().getPublicId()); entry.put("dealId", deal.getPublicId());
        entry.put("voucherIds", order.getVouchers().stream().map(voucher -> voucher.getPublicId()).toList());
    }
}
