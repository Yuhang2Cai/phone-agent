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
import com.fandazi.feature.catalog.MerchantRepository;
import com.fandazi.feature.order.OrderStatus;
import com.fandazi.feature.order.OrderRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
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
    void everySeedImagePathResolvesToALocalPngResource() throws IOException {
        JsonNode templates = mapper.readTree(Files.newInputStream(FIXTURE_DIRECTORY.resolve("dishes.json")));

        for (JsonNode template : templates) {
            String imageUrl = template.required("imageUrl").asText();
            Path resource = Path.of("src/main/resources/static").resolve(imageUrl.substring(1));
            assertThat(resource).as(imageUrl).isRegularFile();
            byte[] contents = Files.readAllBytes(resource);
            assertThat(contents).startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4e, (byte) 0x47);
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
            assertThat(mapper.readTree(metadata.getIngredients())).isNotEmpty();
            assertThat(mapper.readTree(metadata.getAllergens())).isNotEmpty();
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
        });
        assertThat(jdbc.queryForObject("select previous_price from seed_dish_metadata where dish_name = ?", BigDecimal.class, "招牌辣椒鸡盖饭 1")).isEqualByComparingTo("16.80");
        assertThat(jdbc.queryForObject("select price_changed from seed_dish_metadata where dish_name = ?", Boolean.class, "招牌辣椒鸡盖饭 1")).isTrue();
        assertThat(jdbc.queryForObject("select valid from seed_address_metadata where detail = ?", Boolean.class, "已失效地址：演示市旧站路 9 号")).isFalse();
        assertThat(jdbc.queryForObject("select ingredients from seed_dish_metadata where dish_name = ?", String.class, "招牌辣椒鸡盖饭 1")).contains("鸡肉");
        assertThat(jdbc.queryForObject("select count(distinct category) from merchants", Long.class)).isEqualTo(7L);
    }

    @Test
    void repeatedExecutionDoesNotDuplicateRows() throws Exception {
        long merchantsBefore = merchants.count();
        long dishesBefore = dishes.count();
        loader.run(new org.springframework.boot.DefaultApplicationArguments());
        assertThat(merchants.count()).isEqualTo(merchantsBefore);
        assertThat(dishes.count()).isEqualTo(dishesBefore);
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
