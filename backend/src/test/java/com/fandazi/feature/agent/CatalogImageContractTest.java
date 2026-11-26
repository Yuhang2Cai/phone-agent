// 目录图片契约回归：真实静态资源使用版本化站内路径，Agent 工具必须允许该路径且继续拒绝越界路径。
package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证 Agent 查询套餐时图片地址与公开目录的版本化相对路径保持一致。 */
class CatalogImageContractTest {
    private final AgentToolSchemaValidator validator = new AgentToolSchemaValidator(new ObjectMapper());
    private final AgentProtocolValidator protocol = new AgentProtocolValidator(new ObjectMapper());

    /** 真实目录返回的站内路径应通过契约，历史合法绝对 URL 也应保持兼容。 */
    @Test
    void acceptsVersionedCatalogImagePathAndLegacyAbsoluteUrl() {
        assertThatCode(() -> validator.validateData("list_deal_packages", Map.of("deals",
                List.of(deal("/catalog-images/v1/full/hotpot-123456abcdef.jpg"))))).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateData("list_deal_packages", Map.of("deals",
                List.of(deal("https://example.org/hotpot.jpg"))))).doesNotThrowAnyException();
    }

    /** 非目录相对路径不能借图片字段穿过工具边界，避免客户端拼接到错误资源。 */
    @Test
    void rejectsTraversalImagePath() {
        assertThatThrownBy(() -> validator.validateData("list_deal_packages", Map.of("deals",
                List.of(deal("/catalog-images/v1/full/../private.jpg")))))
                .isInstanceOf(ToolBoundaryException.class);
    }

    /** 工具通过后，公开卡片事件还须接受同一目录路径；否则前端会把成功查询误判为连接失败。 */
    @Test
    void acceptsVersionedImageInPublicDealCard() {
        var cardData = new LinkedHashMap<String, Object>();
        cardData.put("dealId", "deal-1");
        cardData.put("storeId", "store-1");
        cardData.put("title", "火锅双人餐");
        cardData.put("priceCents", 1000);
        cardData.put("originalPriceCents", 1500);
        cardData.put("inventory", 5);
        cardData.put("validFrom", "2026-10-01");
        cardData.put("validTo", "2026-10-31");
        cardData.put("purchaseLimit", 2);
        cardData.put("imageUrl", "/catalog-images/v1/full/hotpot-123456abcdef.jpg");
        cardData.put("imageAlt", "火锅实拍");
        var card = Map.of("schemaVersion", "1.1", "cardType", "deal", "resourceId", "deal-1",
                "data", cardData, "actions", List.of());
        var event = Map.of("schemaVersion", "1.1", "eventId", "event-1", "eventType", "card",
                "traceId", "trace-1", "runId", "run-1", "sequence", 1, "status", "running",
                "payload", Map.of("card", card));
        assertThatCode(() -> protocol.validateEvent(event)).doesNotThrowAnyException();
    }

    /** 构造满足其他领域必填项的最小套餐，单独固定图片格式边界。 */
    private Map<String, Object> deal(String imageUrl) {
        var value = new LinkedHashMap<String, Object>();
        value.put("publicId", "deal-1");
        value.put("storeId", "store-1");
        value.put("title", "火锅双人餐");
        value.put("description", "火锅套餐");
        value.put("priceCents", 1000);
        value.put("originalPriceCents", 1500);
        value.put("inventory", 5);
        value.put("validFrom", "2026-10-01");
        value.put("validTo", "2026-10-31");
        value.put("purchaseLimit", 2);
        value.put("active", true);
        value.put("imageUrl", imageUrl);
        value.put("imageAlt", "火锅实拍");
        return value;
    }
}
