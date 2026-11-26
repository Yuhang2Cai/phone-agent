// 演示数据加载器：以可重复执行的方式导入初始数据。
package com.fandazi.seed;

import com.fandazi.feature.address.AddressEntity;
import com.fandazi.feature.address.AddressRepository;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishOptionEntity;
import com.fandazi.feature.catalog.DishOptionRepository;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.MerchantEntity;
import com.fandazi.feature.catalog.MerchantRepository;
import com.fandazi.feature.catalog.MerchantReviewEntity;
import com.fandazi.feature.catalog.MerchantReviewRepository;
import com.fandazi.feature.catalog.DealPackageEntity;
import com.fandazi.feature.catalog.DealPackageRepository;
import com.fandazi.feature.catalog.StoreEntity;
import com.fandazi.feature.catalog.StoreRepository;
import com.fandazi.feature.catalog.StoreReviewEntity;
import com.fandazi.feature.catalog.StoreReviewRepository;
import com.fandazi.feature.coupon.CouponEntity;
import com.fandazi.feature.coupon.CouponRepository;
import com.fandazi.feature.grouporder.GroupBuyOrderEntity;
import com.fandazi.feature.grouporder.GroupBuyOrderRepository;
import com.fandazi.feature.grouporder.GroupBuyOrderStatus;
import com.fandazi.feature.voucher.VoucherEntity;
import com.fandazi.feature.voucher.VoucherRepository;
import com.fandazi.feature.voucher.VoucherStatus;
import com.fandazi.feature.order.OrderEntity;
import com.fandazi.feature.order.OrderItemEntity;
import com.fandazi.feature.order.OrderRepository;
import com.fandazi.feature.order.OrderStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
// 基础目录必须先于开发状态夹具装载，避免 fixture 解析到尚不存在的 UUID 别名。
@org.springframework.core.annotation.Order(0)
@ConditionalOnProperty(name = "seed.enabled", havingValue = "true")
public class SeedDataLoader implements ApplicationRunner {
    private final ObjectMapper mapper; private final MerchantRepository merchants; private final MerchantReviewRepository reviews; private final DishRepository dishes;
    private final DishOptionRepository options; private final UserRepository users; private final AddressRepository addresses; private final OrderRepository orders; private final SeedDishMetadataRepository dishMetadata; private final SeedAddressMetadataRepository addressMetadata;
    private final StoreRepository stores; private final DealPackageRepository deals; private final StoreReviewRepository storeReviews;
    private final CouponRepository coupons; private final GroupBuyOrderRepository groupOrders; private final VoucherRepository vouchers;
    private final Path path; private final boolean groupSeedEnabled;
    // 内部夹具使用旧别名关联，公开实体只保存随机 UUID；重复装载必须复用已持久化映射。
    @org.springframework.beans.factory.annotation.Autowired
    private com.fandazi.core.identity.PublicIdAliases publicIds;
    private List<SeedScenario> scenarios = List.of();
// 初始化对象依赖与默认状态。
    public SeedDataLoader(ObjectMapper mapper, MerchantRepository merchants, MerchantReviewRepository reviews, DishRepository dishes, DishOptionRepository options,
                          UserRepository users, AddressRepository addresses, OrderRepository orders, SeedDishMetadataRepository dishMetadata, SeedAddressMetadataRepository addressMetadata,
                          StoreRepository stores, DealPackageRepository deals, StoreReviewRepository storeReviews, CouponRepository coupons,
                          GroupBuyOrderRepository groupOrders, VoucherRepository vouchers, @Value("${seed.path:../data/seed}") String path,
                          @Value("${group.seed.enabled:true}") boolean groupSeedEnabled) {
        this.mapper=mapper; this.merchants=merchants; this.reviews=reviews; this.dishes=dishes; this.options=options; this.users=users; this.addresses=addresses; this.orders=orders; this.dishMetadata=dishMetadata; this.addressMetadata=addressMetadata;
        this.stores=stores; this.deals=deals; this.storeReviews=storeReviews; this.coupons=coupons; this.groupOrders=groupOrders; this.vouchers=vouchers;
        this.path=Path.of(path); this.groupSeedEnabled=groupSeedEnabled;
    }
// 运行当前数据。
    @Override @Transactional(rollbackFor = Exception.class) public void run(ApplicationArguments args) {
        try { load(args); } catch (IOException | RuntimeException ex) { throw new IllegalStateException("Seed fixture load failed", ex); }
    }
// 加载当前数据。
    private void load(ApplicationArguments args) throws IOException {
        scenarios = readScenarios();
        if (users.findByUsername("seed_alice").isPresent()) {
            refreshExistingDishImages(read("dishes.json"));
            Map<String, MerchantEntity> merchantsByName = new HashMap<>();
            for (MerchantEntity merchant : merchants.findAll()) merchantsByName.put(merchant.getName(), merchant);
            syncReviews(read("reviews.json"), merchantsByName);
            // 旧外卖种子已经存在时仍需执行团购种子同步，保证新增门店可以独立补齐。
            if (groupSeedEnabled) syncGroupBuyingData(merchantsByName);
            return;
        }
        JsonNode merchantData = read("merchants.json");
        Map<String, MerchantEntity> merchantsByName = new HashMap<>();
        for (JsonNode m : merchantData) {
            MerchantEntity merchant = merchants.save(new MerchantEntity(text(m,"name"), text(m,"category"), money(m,"minimumOrder"), money(m,"deliveryFee"), m.get("deliveryMinutes").asInt(), m.get("open").asBoolean(), money(m,"discountThreshold"), money(m,"discountAmount")));
            merchantsByName.put(merchant.getName(), merchant);
        }
        syncReviews(read("reviews.json"), merchantsByName);
        for (JsonNode template : read("dishes.json")) {
            MerchantEntity merchant = merchants.findAll().stream().filter(m -> m.getName().equals(text(template,"merchant"))).findFirst().orElseThrow();
            for (int i=1;i<=template.get("count").asInt();i++) {
                boolean listed = i != template.get("count").asInt();
                DishEntity dish=dishes.save(new DishEntity(merchant,text(template,"category"),text(template,"name")+" "+i,text(template,"description"),money(template,"basePrice").add(BigDecimal.valueOf(i-1)),listed?20:0,listed,text(template,"imageUrl")));
                dishMetadata.save(new SeedDishMetadataEntity(dish.getId(), dish.getName(), template.has("previousPrice") ? money(template,"previousPrice") : null, template.path("priceChanged").asBoolean(false), requiredRating(template), requiredText(template,"spiciness"), requiredStringArray(template,"ingredients"), requiredStringArray(template,"allergens")));
                options.saveAll(List.of(new DishOptionEntity(dish,"辣度","不辣",BigDecimal.ZERO),new DishOptionEntity(dish,"辣度","中辣",new BigDecimal("1.00")),new DishOptionEntity(dish,"加料","加蛋",new BigDecimal("2.00"))));
            }
        }
        JsonNode u=read("users.json").get(0); UserEntity user=users.save(new UserEntity(text(u,"username"),text(u,"email"),text(u,"passwordHash")));
        for (JsonNode a:read("addresses.json")) {
            UserEntity addressUser = users.findByUsername(requiredFixtureText(a, "username")).orElseThrow(() -> new IllegalArgumentException("Address fixture references unknown user: " + a.path("username").asText()));
            AddressEntity address=addresses.save(new AddressEntity(addressUser,text(a,"recipient"),text(a,"phone"),text(a,"detail"),a.get("default").asBoolean()));
            addressMetadata.save(new SeedAddressMetadataEntity(address.getId(), address.getDetail(), a.path("valid").asBoolean(true)));
        }
        for (JsonNode fixture:read("orders.json")) {
            String username = requiredFixtureText(fixture, "username");
            UserEntity orderUser = users.findByUsername(username).orElseThrow(() -> new IllegalArgumentException("Order fixture references unknown user: " + username));
            String merchantName = requiredFixtureText(fixture, "merchant");
            MerchantEntity merchant = merchants.findAll().stream().filter(item -> item.getName().equals(merchantName)).findFirst().orElseThrow(() -> new IllegalArgumentException("Order fixture references unknown merchant: " + merchantName));
            DishEntity dish=dishes.findAll().stream().filter(d -> d.getName().equals(text(fixture,"dish")) && d.getMerchant().getId().equals(merchant.getId())).findFirst().orElseThrow(() -> new IllegalArgumentException("Order fixture references unknown dish for merchant: " + text(fixture,"dish")));
            String addressDetail = requiredFixtureText(fixture, "address");
            AddressEntity address = addresses.findAll().stream().filter(item -> item.getUser().getId().equals(orderUser.getId()) && item.getDetail().equals(addressDetail)).findFirst().orElseThrow(() -> new IllegalArgumentException("Order fixture references unknown address: " + addressDetail));
            int quantity=fixture.path("quantity").asInt(); BigDecimal subtotal=dish.getPrice().multiply(BigDecimal.valueOf(quantity));
            OrderEntity order=new OrderEntity(orderUser,text(fixture,"idempotencyKey"),"seed-history",merchant.getName(), address.getRecipient(),address.getPhone(),address.getDetail(),subtotal,merchant.getDeliveryFee(),BigDecimal.ZERO,subtotal.add(merchant.getDeliveryFee()));
            order.addItem(new OrderItemEntity(order,dish.getId(),dish.getName(),quantity,List.of(),"",dish.getPrice())); order.advance(OrderStatus.valueOf(text(fixture,"status"))); orders.save(order);
        }
        // 首次建立旧数据后，再装载独立团购域，避免修改既有外卖表的业务语义。
        if (groupSeedEnabled) syncGroupBuyingData(merchantsByName);
    }
    /**
     * 幂等加载团购领域种子。
     *
     * <p>团购数据使用独立表和公开标识，不修改旧外卖商家的数量和评价统计；
     * 因此旧种子测试可以关闭本开关，生产和团购集成测试则使用默认开启状态。</p>
     */
    private void syncGroupBuyingData(Map<String, MerchantEntity> merchantsByName) throws IOException {
        Map<String, StoreEntity> storesByPublicId = new HashMap<>();
        for (StoreEntity store : stores.findAll()) storesByPublicId.put(store.getPublicId(), store);
        includeAliases(storesByPublicId, "store");

        // 只在门店表为空时创建基础门店，避免重复启动产生重复公开资源。
        if (storesByPublicId.isEmpty()) {
            for (JsonNode fixture : read("stores.json")) {
                MerchantEntity merchant = merchantsByName.get(fixture.path("merchant").asText());
                StoreEntity store = new StoreEntity(
                        publicIds.getOrCreate("store", requiredFixtureText(fixture, "publicId")), requiredFixtureText(fixture, "brandName"),
                        requiredFixtureText(fixture, "name"), requiredFixtureText(fixture, "cityCode"),
                        requiredFixtureText(fixture, "address"), requiredFixtureText(fixture, "phone"),
                        requiredFixtureText(fixture, "category"), fixture.path("latitude").decimalValue(),
                        fixture.path("longitude").decimalValue(), fixture.path("averageSpendCents").asInt(),
                        fixture.path("open").asBoolean(), requiredFixtureText(fixture, "openingHours"), merchant
                );
                storesByPublicId.put(store.getPublicId(), stores.save(store));
                storesByPublicId.put(requiredFixtureText(fixture, "publicId"), store);
            }
        }

        Map<String, DealPackageEntity> dealsByPublicId = new HashMap<>();
        for (DealPackageEntity deal : deals.findAll()) dealsByPublicId.put(deal.getPublicId(), deal);
        includeAliases(dealsByPublicId, "deal");

        // 模板序号只用于内部别名；实际对外 UUID 随机生成并持久化，不能从门店编号推导。
        if (dealsByPublicId.isEmpty()) {
            for (JsonNode fixture : read("deals.json")) {
                String storeId = requiredFixtureText(fixture, "store");
                StoreEntity store = storesByPublicId.get(storeId);
                if (store == null) throw new IllegalArgumentException("Deal fixture references unknown store: " + storeId);
                int count = fixture.path("count").asInt(1);
                for (int index = 1; index <= count; index++) {
                    String alias = "deal-" + storeId.substring("store-".length()) + "-" + index;
                    String publicId = publicIds.getOrCreate("deal", alias);
                    DealPackageEntity deal = new DealPackageEntity(
                            publicId, store, requiredFixtureText(fixture, "title") + " " + index,
                            requiredFixtureText(fixture, "description"), fixture.path("priceCents").asInt(),
                            fixture.path("originalPriceCents").asInt(), fixture.path("inventory").asInt(),
                            LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), true,
                            fixture.path("purchaseLimit").asInt(), 1
                    );
                    dealsByPublicId.put(publicId, deals.save(deal));
                    dealsByPublicId.put(alias, deal);
                }
            }
        }

        // 只同步模板明确声明的套餐图，旧库也可补图；未声明的资源不覆盖人工维护图片。
        for (JsonNode fixture : read("deals.json")) {
            if (!fixture.has("imageUrl")) continue;
            String storeAlias = requiredFixtureText(fixture, "store");
            for (int index = 1; index <= fixture.path("count").asInt(1); index++) {
                String alias = "deal-" + storeAlias.substring("store-".length()) + "-" + index;
                var deal = dealsByPublicId.get(alias);
                if (deal == null) throw new IllegalArgumentException("套餐图片引用不存在：" + alias);
                deal.replaceImage(fixture.path("imageUrl").isNull() ? null : fixture.path("imageUrl").asText(),
                        fixture.path("imageAlt").isMissingNode() || fixture.path("imageAlt").isNull() ? null : fixture.path("imageAlt").asText());
            }
        }

        // 30 条模板各展开 10 条评价，使用固定时间和名称保证唯一键稳定。
        if (storeReviews.count() == 0) {
            for (JsonNode fixture : read("group-reviews.json")) {
                StoreEntity store = storesByPublicId.get(requiredFixtureText(fixture, "store"));
                if (store == null) throw new IllegalArgumentException("Review fixture references unknown store");
                int count = fixture.path("count").asInt(1);
                Instant start = Instant.parse(requiredFixtureText(fixture, "createdAt"));
                for (int index = 1; index <= count; index++) {
                    storeReviews.save(new StoreReviewEntity(
                            store, requiredFixtureText(fixture, "reviewerPrefix") + index,
                            fixture.path("rating").decimalValue(), requiredFixtureText(fixture, "content"),
                            start.plusSeconds(index * 86400L)
                    ));
                }
            }
        }

        // 优惠券定义同样用 count 展开，保证演示环境至少有 30 个可查询对象。
        if (coupons.count() == 0) {
            for (JsonNode fixture : read("coupons.json")) {
                int count = fixture.path("count").asInt(1);
                for (int index = 1; index <= count; index++) {
                    coupons.save(new CouponEntity(
                            publicIds.getOrCreate("coupon", String.format("coupon-%03d", index)), requiredFixtureText(fixture, "name") + index,
                            fixture.path("thresholdCents").asInt(), fixture.path("discountCents").asInt(),
                            LocalDate.parse(requiredFixtureText(fixture, "validFrom")),
                            LocalDate.parse(requiredFixtureText(fixture, "validTo")),
                            requiredFixtureText(fixture, "scopeDescription"), fixture.path("active").asBoolean()
                    ));
                }
            }
        }

        // 订单只创建一次；级联保存会先保存订单，再保存按数量生成的团购券。
        if (groupOrders.count() == 0) {
            UserEntity user = users.findByUsername("seed_alice")
                    .orElseThrow(() -> new IllegalArgumentException("Group order seed requires seed_alice"));
            Map<String, GroupBuyOrderEntity> savedOrders = new HashMap<>();
            for (JsonNode fixture : read("group-orders.json")) {
                DealPackageEntity deal = dealsByPublicId.get(requiredFixtureText(fixture, "deal"));
                if (deal == null) throw new IllegalArgumentException("Group order references unknown deal");
                GroupBuyOrderEntity order = new GroupBuyOrderEntity(
                        publicIds.getOrCreate("order", requiredFixtureText(fixture, "publicId")), user, deal.getStore(), deal,
                        fixture.path("quantity").asInt(), fixture.path("totalCents").asInt(),
                        GroupBuyOrderStatus.valueOf(requiredFixtureText(fixture, "status")),
                        LocalDateTime.parse(requiredFixtureText(fixture, "expiresAt"))
                );
                savedOrders.put(requiredFixtureText(fixture, "publicId"), groupOrders.save(order));
            }
            applyVoucherSeedStatuses(savedOrders);
        }
    }

    /** 将仅供内部引用的别名加入当前实体索引；映射仍指向原实体，不新建或改变用户归属。 */
    private <T> void includeAliases(Map<String, T> entities, String kind) {
        for (var alias : publicIds.mappings(kind).entrySet()) {
            T entity = entities.get(alias.getValue());
            if (entity != null) entities.put(alias.getKey(), entity);
        }
    }

    /** 将种子中的核销、退款和过期状态应用到订单自动生成的团购券。 */
    private void applyVoucherSeedStatuses(Map<String, GroupBuyOrderEntity> savedOrders) throws IOException {
        Map<String, Integer> nextIndexByOrder = new HashMap<>();
        for (JsonNode fixture : read("vouchers.json")) {
            String orderId = requiredFixtureText(fixture, "order");
            GroupBuyOrderEntity order = savedOrders.get(orderId);
            if (order == null) throw new IllegalArgumentException("Voucher fixture references unknown order: " + orderId);
            VoucherStatus status = VoucherStatus.valueOf(requiredFixtureText(fixture, "status"));
            int count = fixture.path("count").asInt(1);
            int start = nextIndexByOrder.getOrDefault(orderId, 0);
            for (int offset = 0; offset < count; offset++) {
                if (start + offset >= order.getVouchers().size()) {
                    throw new IllegalArgumentException("Voucher fixture quantity exceeds order quantity: " + orderId);
                }
                order.getVouchers().get(start + offset).restoreSeedStatus(status);
            }
            nextIndexByOrder.put(orderId, start + count);
        }
        vouchers.saveAll(savedOrders.values().stream().flatMap(order -> order.getVouchers().stream()).toList());
    }

// 获取scenarios。
    public List<SeedScenario> getScenarios() { return scenarios; }

// 处理 `syncReviews` 相关逻辑。
    private void syncReviews(JsonNode fixtures, Map<String, MerchantEntity> merchantsByName) {
        for (JsonNode fixture : fixtures) {
            String merchantName = requiredFixtureText(fixture, "merchant");
            MerchantEntity merchant = merchantsByName.get(merchantName);
            if (merchant == null) throw new IllegalArgumentException("Review fixture references unknown merchant: " + merchantName);
            JsonNode ratingNode = fixture.get("rating");
            if (ratingNode == null || !ratingNode.isNumber()
                    || ratingNode.decimalValue().compareTo(BigDecimal.ONE) < 0
                    || ratingNode.decimalValue().compareTo(new BigDecimal("5.0")) > 0) {
                throw new IllegalArgumentException("Review rating must be between 1.0 and 5.0");
            }
            String reviewer = requiredFixtureText(fixture, "reviewerName");
            Instant createdAt = Instant.parse(requiredFixtureText(fixture, "createdAt"));
            if (!reviews.existsByMerchantIdAndReviewerNameAndCreatedAt(merchant.getId(), reviewer, createdAt)) {
                reviews.save(new MerchantReviewEntity(merchant, reviewer,
                        ratingNode.decimalValue(), requiredFixtureText(fixture, "content"), createdAt));
            }
        }
    }

// 处理 `refreshExistingDishImages` 相关逻辑。
    private void refreshExistingDishImages(JsonNode templates) {
        List<DishEntity> existing = dishes.findAll();
        for (JsonNode template : templates) {
            String imageUrl = text(template, "imageUrl");
            String merchantName = text(template, "merchant");
            int count = template.path("count").asInt();
            // 仅同步本模板明确展开的精确菜名和所属商家，防止相似前缀菜被旧种子覆盖。
            for (int index = 1; index <= count; index++) {
                String expectedName = text(template, "name") + " " + index;
                existing.stream()
                        .filter(dish -> dish.getName().equals(expectedName)
                                && dish.getMerchant().getName().equals(merchantName))
                        .forEach(dish -> dish.replaceImageUrl(imageUrl));
            }
        }
        dishes.saveAll(existing);
    }
// 读取scenarios。
    private List<SeedScenario> readScenarios() throws IOException {
        JsonNode fixture = read("agent-scenarios.json");
        if (!fixture.isArray()) throw new IllegalArgumentException("Agent scenario fixture must be an array");
        List<SeedScenario> parsed = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode scenario : fixture) {
            String id = requiredScenarioText(scenario, "id");
            if (!ids.add(id)) throw new IllegalArgumentException("Agent scenario ids must be unique");
            parsed.add(new SeedScenario(id, requiredScenarioText(scenario, "prompt"), requiredScenarioText(scenario, "expected")));
        }
        return List.copyOf(parsed);
    }
// 读取当前数据。
    private JsonNode read(String file) throws IOException { try (var input=Files.newInputStream(path.resolve(file))) { return mapper.readTree(input); } }
// 处理 `text` 相关逻辑。
    // 处理 `money` 相关逻辑。
    private static String text(JsonNode node,String name){return node.get(name).asText();} private static BigDecimal money(JsonNode node,String name){return node.get(name).decimalValue();}
// 处理 `requiredRating` 相关逻辑。
    private static BigDecimal requiredRating(JsonNode node) {
        JsonNode rating = node.get("rating");
        if (rating == null || !rating.isNumber() || rating.decimalValue().signum() <= 0) throw new IllegalArgumentException("Dish template requires a positive rating");
        return rating.decimalValue();
    }
// 处理 `requiredText` 相关逻辑。
    private static String requiredText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Dish template requires " + name);
        return value.asText();
    }
// 处理 `requiredStringArray` 相关逻辑。
    private static List<String> requiredStringArray(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isArray() || value.isEmpty()) throw new IllegalArgumentException("Dish template requires a non-empty " + name + " array");
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual() || item.asText().isBlank()) throw new IllegalArgumentException("Dish template requires a non-empty " + name + " array");
            result.add(item.asText());
        }
        return List.copyOf(result);
    }
// 处理 `requiredScenarioText` 相关逻辑。
    private static String requiredScenarioText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Agent scenario requires " + name);
        return value.asText();
    }
// 处理 `requiredFixtureText` 相关逻辑。
    private static String requiredFixtureText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Fixture requires " + name);
        return value.asText();
    }
}
