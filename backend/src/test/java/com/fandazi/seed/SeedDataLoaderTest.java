// 演示数据模块：验证关键行为与边界条件。
package com.fandazi.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fandazi.feature.address.AddressEntity;
import com.fandazi.feature.address.AddressRepository;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.DishEntity;
import com.fandazi.feature.catalog.DishOptionRepository;
import com.fandazi.feature.catalog.DishRepository;
import com.fandazi.feature.catalog.DishResponse;
import com.fandazi.feature.catalog.MerchantRepository;
import com.fandazi.feature.catalog.MerchantReviewRepository;
import com.fandazi.feature.order.OrderEntity;
import com.fandazi.feature.order.OrderStatus;
import com.fandazi.feature.order.OrderRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sql.DataSource;
import java.util.stream.Stream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "seed.enabled=true")
class SeedDataLoaderTest {

    private static final Path FIXTURE_SOURCE = Path.of("../data/seed").toAbsolutePath().normalize();
    private static final Path FIXTURE_DIRECTORY = copyFixtures();

    @Autowired private MerchantRepository merchants;
    @Autowired private MerchantReviewRepository reviews;
    @Autowired private DishRepository dishes;
    @Autowired private DishOptionRepository options;
    @Autowired private AddressRepository addresses;
    @Autowired private OrderRepository orders;
    @Autowired private UserRepository users;
    @Autowired private SeedDishMetadataRepository dishMetadata;
    @Autowired private SeedAddressMetadataRepository addressMetadata;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SeedDataLoader loader;
    @Autowired private ObjectMapper mapper;

    @DynamicPropertySource
    static void seedProperties(DynamicPropertyRegistry registry) {
        registry.add("seed.path", () -> FIXTURE_DIRECTORY.toString());
    }

    @BeforeEach
    void reloadFixtures() throws Exception {
        restoreFixtures();
        deleteSeedRows();
        loader.run(new org.springframework.boot.DefaultApplicationArguments());
    }

    @AfterEach
    void restoreFixtureFiles() throws IOException {
        restoreFixtures();
    }

    @Test
    void reviewFixtureCoversEveryMerchantWithThreeToFiveReviews() throws IOException {
        JsonNode merchantFixture = mapper.readTree(Files.newInputStream(FIXTURE_SOURCE.resolve("merchants.json")));
        JsonNode reviewFixture = mapper.readTree(Files.newInputStream(FIXTURE_DIRECTORY.resolve("reviews.json")));

        Set<String> merchantNames = StreamSupport.stream(merchantFixture.spliterator(), false)
                .map(merchant -> merchant.path("name").asText()).collect(Collectors.toSet());
        Map<String, Long> reviewsByMerchant = StreamSupport.stream(reviewFixture.spliterator(), false)
                .collect(Collectors.groupingBy(review -> review.path("merchant").asText(), Collectors.counting()));

        assertThat(merchantNames).hasSize(12);
        assertThat(reviewsByMerchant.keySet()).containsExactlyInAnyOrderElementsOf(merchantNames);
        assertThat(reviewsByMerchant.values()).allSatisfy(count -> assertThat(count).isBetween(3L, 5L));
    }

    @Test
    void firstSeedPersistsThreeReviewsForEveryMerchant() {
        assertThat(reviews.count()).isEqualTo(36);
        assertThat(jdbc.queryForList("select m.name, count(r.id) as review_count from merchants m left join merchant_reviews r on r.merchant_id = m.id group by m.name"))
                .hasSize(12)
                .allSatisfy(row -> assertThat(((Number) row.get("REVIEW_COUNT")).longValue()).isEqualTo(3));
    }

    @Test
    void existingSeedUserWithoutReviewsIsRepaired() throws Exception {
        reviews.deleteAll();
        assertThat(users.findByUsername("seed_alice")).isPresent();

        loader.run(new org.springframework.boot.DefaultApplicationArguments());

        assertThat(reviews.count()).isEqualTo(36);
        assertThat(merchants.count()).isEqualTo(12);
        assertThat(dishes.count()).isEqualTo(120);
    }

    @Test
    void outOfRangeReviewRatingIsRejected() throws Exception {
        ArrayNode reviewFixture = (ArrayNode) mapper.readTree(Files.newInputStream(FIXTURE_DIRECTORY.resolve("reviews.json")));
        ((ObjectNode) reviewFixture.get(0)).put("rating", 5.5);
        Files.writeString(FIXTURE_DIRECTORY.resolve("reviews.json"), mapper.writeValueAsString(reviewFixture));

        assertThatThrownBy(() -> loader.run(new org.springframework.boot.DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Review rating must be between 1.0 and 5.0");
        assertThat(reviews.count()).isEqualTo(36);
    }

    @Test
    void reviewFixtureWithUnknownMerchantIsRejected() throws Exception {
        ArrayNode reviewFixture = (ArrayNode) mapper.readTree(Files.newInputStream(FIXTURE_DIRECTORY.resolve("reviews.json")));
        ((ObjectNode) reviewFixture.get(0)).put("merchant", "unknown_merchant");
        Files.writeString(FIXTURE_DIRECTORY.resolve("reviews.json"), mapper.writeValueAsString(reviewFixture));

        assertThatThrownBy(() -> loader.run(new org.springframework.boot.DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Review fixture references unknown merchant: unknown_merchant");
        assertThat(reviews.count()).isEqualTo(36);
    }

    @Test
    void existingSeedUserReplacesOnlyMissingReviews() throws Exception {
        var existingReviews = reviews.findAll();
        var missingReview = existingReviews.get(0);
        long retainedId = existingReviews.get(1).getId();
        reviews.delete(missingReview);

        loader.run(new org.springframework.boot.DefaultApplicationArguments());

        assertThat(reviews.count()).isEqualTo(36);
        assertThat(reviews.existsById(retainedId)).isTrue();
        assertThat(reviews.existsByMerchantIdAndReviewerNameAndCreatedAt(
                missingReview.getMerchant().getId(), missingReview.getReviewerName(), missingReview.getCreatedAt())).isTrue();
    }

    @Test
    void dishTemplatesExplicitlyDeclareStructuredMetadata() throws IOException {
        JsonNode templates = mapper.readTree(Files.newInputStream(FIXTURE_DIRECTORY.resolve("dishes.json")));

        assertThat(templates).hasSize(12);
        for (JsonNode template : templates) {
            assertThat(template.hasNonNull("rating")).as(template.path("name").asText()).isTrue();
            assertThat(template.hasNonNull("spiciness")).as(template.path("name").asText()).isTrue();
            assertThat(template.path("ingredients").isArray() && !template.path("ingredients").isEmpty()).as(template.path("name").asText()).isTrue();
            assertThat(template.path("allergens").isArray() && !template.path("allergens").isEmpty()).as(template.path("name").asText()).isTrue();
        }
    }

    @Test
    void everySeedImageReferenceUsesTheProjectOssAssetPrefix() throws IOException {
        JsonNode templates = mapper.readTree(Files.newInputStream(FIXTURE_DIRECTORY.resolve("dishes.json")));

        for (JsonNode template : templates) {
            String imageUrl = template.required("imageUrl").asText();
            URI imageUri = URI.create(imageUrl);
            assertThat(imageUri.getScheme()).as(imageUrl).isEqualTo("https");
            assertThat(imageUri.getHost()).as(imageUrl).isEqualTo("redbook-hang.oss-cn-shanghai.aliyuncs.com");
            assertThat(imageUri.getPath()).as(imageUrl).startsWith("/fandazi/seed-food/");
            assertThat(imageUri.getPath()).as(imageUrl).matches(".*\\.(?:jpg|jpeg|png)$");
        }
    }

    @Test
    void loadsDeterministicCatalogAddressAndPaidOrderFixtures() throws IOException {
        assertThat(merchants.count()).isEqualTo(12);
        assertThat(dishes.count()).isEqualTo(120L);
        assertThat(dishMetadata.findAll()).hasSize(120);
        for (SeedDishMetadataEntity metadata : dishMetadata.findAll()) {
            assertThat(metadata.getRating()).isPositive();
            assertThat(metadata.getSpiciness()).isNotBlank();
            assertThat(metadata.getIngredients()).isNotEmpty();
            assertThat(metadata.getAllergens()).isNotEmpty();
        }
        assertThat(options.findAll())
                .anySatisfy(option -> assertThat(option.getGroupName()).isEqualTo("辣度"));
        assertThat(dishes.findAll())
                .filteredOn(DishEntity::isListed)
                .hasSizeLessThan((int) dishes.count());
        assertThat(dishes.findAll())
                .anySatisfy(dish -> assertThat(dish.getPrice()).isEqualByComparingTo(new BigDecimal("18.80")));
        assertThat(addresses.findAll())
                .filteredOn(AddressEntity::isDefaultAddress)
                .singleElement()
                .extracting(AddressEntity::getDetail)
                .isEqualTo("演示市春风路 88 号 3 栋 1201");
        assertThat(addresses.findAll())
                .extracting(AddressEntity::getDetail)
                .contains("已失效地址：演示市旧站路 9 号");
        assertThat(orders.findAll()).anySatisfy(order -> {
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(order.getItems()).singleElement().extracting(item -> item.getDishName()).isEqualTo("招牌辣椒鸡盖饭 1");
            assertThat(order.getTotal()).isEqualByComparingTo("21.80");
            assertThat(order.getUser().getUsername()).isEqualTo("seed_alice");
            assertThat(order.getAddressDetail()).isEqualTo("演示市春风路 88 号 3 栋 1201");
        });
        DishEntity seededDish = dishes.findAll().stream().filter(dish -> dish.getName().equals("招牌辣椒鸡盖饭 1")).findFirst().orElseThrow();
        DishResponse response = DishResponse.from(seededDish, options.findByDishId(seededDish.getId()), dishMetadata.findByDishId(seededDish.getId()).orElseThrow());
        assertThat(response.ingredients()).containsExactly("鸡肉", "米饭", "青椒");
        assertThat(response.allergens()).containsExactly("大豆");
        assertThat(jdbc.queryForObject("select previous_price from seed_dish_metadata where dish_name = ?", BigDecimal.class, "招牌辣椒鸡盖饭 1")).isEqualByComparingTo("16.80");
        assertThat(jdbc.queryForObject("select price_changed from seed_dish_metadata where dish_name = ?", Boolean.class, "招牌辣椒鸡盖饭 1")).isTrue();
        assertThat(jdbc.queryForObject("select valid from seed_address_metadata where detail = ?", Boolean.class, "已失效地址：演示市旧站路 9 号")).isFalse();
        assertThat(dishMetadata.findAll().stream().filter(metadata -> metadata.getDishName().equals("招牌辣椒鸡盖饭 1")).findFirst().orElseThrow().getIngredients()).contains("鸡肉");
        assertThat(jdbc.queryForObject("select count(distinct category) from merchants", Long.class)).isEqualTo(7L);
    }

    @Test
    void repeatedExecutionDoesNotDuplicateRows() throws Exception {
        long merchantsBefore = merchants.count();
        long dishesBefore = dishes.count();
        long reviewsBefore = reviews.count();
        loader.run(new org.springframework.boot.DefaultApplicationArguments());
        assertThat(merchants.count()).isEqualTo(merchantsBefore);
        assertThat(dishes.count()).isEqualTo(dishesBefore);
        assertThat(reviews.count()).isEqualTo(reviewsBefore);
    }

    @Test
    void readsAllAgentEvaluationScenariosFromFixture() {
        assertThat(loader.getScenarios())
                .hasSize(9)
                .extracting(SeedScenario::id)
                .containsExactly(
                        "spicy-under-30", "reorder", "mild-cold", "no-cilantro-default-address", "missing-address",
                        "price-change", "reorder-unlisted", "over-budget", "illegal-parameters");
    }

    @Test
    void seedMetadataTablesAreCreatedByMigration() {
        DataSource migrationDataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:seed-migration-test;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway flyway = Flyway.configure().dataSource(migrationDataSource).locations("classpath:db/migration").load();
        flyway.migrate();
        JdbcTemplate migrationJdbc = new JdbcTemplate(migrationDataSource);
        assertThat(migrationJdbc.queryForObject("select count(*) from \"flyway_schema_history\" where \"script\" = 'V1__create_seed_metadata.sql' and \"success\" = true", Integer.class)).isEqualTo(1);
        assertThat(migrationJdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'SEED_DISH_METADATA'", Integer.class)).isEqualTo(1);
        assertThat(migrationJdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'SEED_ADDRESS_METADATA'", Integer.class)).isEqualTo(1);
        assertThat(migrationJdbc.query("select table_name from information_schema.tables where table_schema = 'PUBLIC'", (rs, rowNum) -> rs.getString(1)))
                .containsAll(List.of("APP_USERS", "MERCHANTS", "DISHES", "DISH_OPTIONS", "ADDRESSES", "CART_DRAFTS", "CART_DRAFT_ITEMS", "CART_DRAFT_ITEM_OPTIONS", "FOOD_ORDERS", "ORDER_ITEMS", "ORDER_ITEM_OPTIONS", "ORDER_TIMELINE"));
    }

    @Test
    void changedOrderRelationshipFixtureFailsValidation() throws Exception {
        deleteSeedRows();
        ArrayNode ordersFixture = (ArrayNode) mapper.readTree(Files.newInputStream(FIXTURE_DIRECTORY.resolve("orders.json")));
        ((ObjectNode) ordersFixture.get(0)).put("username", "unknown_seed_user");
        Files.writeString(FIXTURE_DIRECTORY.resolve("orders.json"), mapper.writeValueAsString(ordersFixture));

        assertThatThrownBy(() -> loader.run(new org.springframework.boot.DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Order fixture references unknown user: unknown_seed_user");
    }

    @Test
    void changedOrderMerchantFixtureFailsValidation() throws Exception {
        assertOrderFieldRejected("merchant", "unknown_merchant", "Order fixture references unknown merchant: unknown_merchant");
    }

    @Test
    void changedOrderAddressFixtureFailsValidation() throws Exception {
        assertOrderFieldRejected("address", "unknown address", "Order fixture references unknown address: unknown address");
    }

    private void assertOrderFieldRejected(String field, String value, String expectedMessage) throws Exception {
        deleteSeedRows();
        ArrayNode ordersFixture = (ArrayNode) mapper.readTree(Files.newInputStream(FIXTURE_DIRECTORY.resolve("orders.json")));
        ((ObjectNode) ordersFixture.get(0)).put(field, value);
        Files.writeString(FIXTURE_DIRECTORY.resolve("orders.json"), mapper.writeValueAsString(ordersFixture));

        assertThatThrownBy(() -> loader.run(new org.springframework.boot.DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(expectedMessage);
    }

    @Test
    void rejectsMissingDishMetadataInsteadOfPersistingDefaults() throws Exception {
        deleteSeedRows();
        ArrayNode templates = (ArrayNode) mapper.readTree(Files.newInputStream(FIXTURE_DIRECTORY.resolve("dishes.json")));
        ((ObjectNode) templates.get(0)).remove("spiciness");
        Files.writeString(FIXTURE_DIRECTORY.resolve("dishes.json"), mapper.writeValueAsString(templates));

        assertThatThrownBy(() -> loader.run(new org.springframework.boot.DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Dish template requires spiciness");
        assertThat(merchants.count()).isZero();
        assertThat(dishMetadata.count()).isZero();
    }

    @Test
    void corruptSubsequentFixtureRollsBackAllSeedRowsAndCanBeReloaded() throws Exception {
        deleteSeedRows();
        Files.writeString(FIXTURE_DIRECTORY.resolve("orders.json"), "{ damaged fixture");

        assertThatThrownBy(() -> loader.run(new org.springframework.boot.DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Seed fixture load failed");
        assertThat(merchants.count()).isZero();
        assertThat(users.count()).isZero();
        assertThat(dishes.count()).isZero();
        assertThat(options.count()).isZero();
        assertThat(addresses.count()).isZero();
        assertThat(orders.count()).isZero();
        assertThat(dishMetadata.count()).isZero();
        assertThat(addressMetadata.count()).isZero();

        restoreFixtures();
        loader.run(new org.springframework.boot.DefaultApplicationArguments());

        assertThat(merchants.count()).isEqualTo(12);
        assertThat(users.count()).isEqualTo(1);
        assertThat(dishes.count()).isEqualTo(120);
        assertThat(orders.count()).isEqualTo(1);
        assertThat(dishMetadata.count()).isEqualTo(120);
        assertThat(addressMetadata.count()).isEqualTo(2);
    }

    private void deleteSeedRows() {
        orders.deleteAll();
        reviews.deleteAll();
        addressMetadata.deleteAll();
        addresses.deleteAll();
        dishMetadata.deleteAll();
        options.deleteAll();
        dishes.deleteAll();
        merchants.deleteAll();
        users.deleteAll();
    }

    private static Path copyFixtures() {
        try {
            Path target = Files.createTempDirectory("fandazi-seed-fixtures-");
            copyFixtureFiles(target);
            return target;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to prepare seed fixtures", exception);
        }
    }

    private static void restoreFixtures() throws IOException {
        copyFixtureFiles(FIXTURE_DIRECTORY);
    }

    private static void copyFixtureFiles(Path target) throws IOException {
        try (Stream<Path> files = Files.list(FIXTURE_SOURCE)) {
            for (Path source : files.toList()) {
                Files.copy(source, target.resolve(source.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
