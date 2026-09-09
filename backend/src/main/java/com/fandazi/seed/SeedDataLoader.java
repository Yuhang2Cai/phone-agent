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
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "seed.enabled", havingValue = "true")
public class SeedDataLoader implements ApplicationRunner {
    private final ObjectMapper mapper; private final MerchantRepository merchants; private final DishRepository dishes;
    private final DishOptionRepository options; private final UserRepository users; private final AddressRepository addresses; private final OrderRepository orders; private final SeedDishMetadataRepository dishMetadata; private final SeedAddressMetadataRepository addressMetadata;
    private final Path path;
    public SeedDataLoader(ObjectMapper mapper, MerchantRepository merchants, DishRepository dishes, DishOptionRepository options,
                          UserRepository users, AddressRepository addresses, OrderRepository orders, SeedDishMetadataRepository dishMetadata, SeedAddressMetadataRepository addressMetadata, @Value("${seed.path:../data/seed}") String path) {
        this.mapper=mapper; this.merchants=merchants; this.dishes=dishes; this.options=options; this.users=users; this.addresses=addresses; this.orders=orders; this.dishMetadata=dishMetadata; this.addressMetadata=addressMetadata; this.path=Path.of(path);
    }
    @Override @Transactional public void run(ApplicationArguments args) throws IOException {
        if (users.findByUsername("seed_alice").isPresent()) return;
        JsonNode merchantData = read("merchants.json");
        for (JsonNode m : merchantData) merchants.save(new MerchantEntity(text(m,"name"), text(m,"category"), money(m,"minimumOrder"), money(m,"deliveryFee"), m.get("deliveryMinutes").asInt(), m.get("open").asBoolean(), money(m,"discountThreshold"), money(m,"discountAmount")));
        for (JsonNode template : read("dishes.json")) {
            MerchantEntity merchant = merchants.findAll().stream().filter(m -> m.getName().equals(text(template,"merchant"))).findFirst().orElseThrow();
            for (int i=1;i<=template.get("count").asInt();i++) {
                boolean listed = i != template.get("count").asInt();
                DishEntity dish=dishes.save(new DishEntity(merchant,text(template,"category"),text(template,"name")+" "+i,text(template,"description"),money(template,"basePrice").add(BigDecimal.valueOf(i-1)),listed?20:0,listed,text(template,"imageUrl")));
                dishMetadata.save(new SeedDishMetadataEntity(dish.getId(), dish.getName(), template.has("previousPrice") ? money(template,"previousPrice") : null, template.path("priceChanged").asBoolean(false), template.path("rating").decimalValue(), template.path("spiciness").asText("未标注"), template.path("ingredients").toString(), template.path("allergens").toString()));
                options.saveAll(List.of(new DishOptionEntity(dish,"辣度","不辣",BigDecimal.ZERO),new DishOptionEntity(dish,"辣度","中辣",new BigDecimal("1.00")),new DishOptionEntity(dish,"加料","加蛋",new BigDecimal("2.00"))));
            }
        }
        JsonNode u=read("users.json").get(0); UserEntity user=users.save(new UserEntity(text(u,"username"),text(u,"email"),text(u,"passwordHash")));
        for (JsonNode a:read("addresses.json")) { AddressEntity address=addresses.save(new AddressEntity(user,text(a,"recipient"),text(a,"phone"),text(a,"detail"),a.get("default").asBoolean())); addressMetadata.save(new SeedAddressMetadataEntity(address.getId(), address.getDetail(), a.path("valid").asBoolean(true))); }
        for (JsonNode fixture:read("orders.json")) {
            DishEntity dish=dishes.findAll().stream().filter(d -> d.getName().equals(text(fixture,"dish"))).findFirst().orElseThrow();
            MerchantEntity merchant=dish.getMerchant(); int quantity=fixture.path("quantity").asInt(); BigDecimal subtotal=dish.getPrice().multiply(BigDecimal.valueOf(quantity));
            OrderEntity order=new OrderEntity(user,text(fixture,"idempotencyKey"),"seed-history", "合成用户","13900000000","演示市春风路 88 号 3 栋 1201",subtotal,merchant.getDeliveryFee(),BigDecimal.ZERO,subtotal.add(merchant.getDeliveryFee()));
            order.addItem(new OrderItemEntity(order,dish.getId(),dish.getName(),quantity,List.of(),"",dish.getPrice())); order.advance(OrderStatus.valueOf(text(fixture,"status"))); orders.save(order);
        }
    }
    private JsonNode read(String file) throws IOException { try (var input=Files.newInputStream(path.resolve(file))) { return mapper.readTree(input); } }
    private static String text(JsonNode node,String name){return node.get(name).asText();} private static BigDecimal money(JsonNode node,String name){return node.get(name).decimalValue();}
}
