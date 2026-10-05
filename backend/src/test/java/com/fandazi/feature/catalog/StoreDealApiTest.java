// 团购门店接口测试：先锁定排序边界和公开标识访问契约。
package com.fandazi.feature.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证门店、套餐只通过团购公开 API 暴露，不回退到旧外卖商家接口。 */
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:task2-store-deal;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class StoreDealApiTest {
    @Autowired private MockMvc mockMvc;
    // 仅测试装载器解析旧夹具别名，公开请求发送随机 UUID，不把旧别名暴露成目录入口。
    @Autowired private com.fandazi.core.identity.PublicIdAliases publicIds;

    /** 搜索词与分类必须同时约束真实数据；不存在的词不能因默认推荐而返回整个目录。 */
    @Test
    void keywordAndCategoryAreIndependentConstraints() throws Exception {
        mockMvc.perform(get("/api/stores").param("keyword", "不存在的门店词Task4"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/api/stores").param("category", "奶茶咖啡"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].category").value("饮品"));
    }

    /** 首屏摘要、城市选项与菜品必须由服务器生成，菜品响应不能泄漏旧外卖数据库主键。 */
    @Test
    void browseSummaryCitiesAndDishesComeFromCatalog() throws Exception {
        String storeId = publicIds.require("store", "store-001");
        mockMvc.perform(get("/api/stores/{id}", storeId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewCount").isNumber())
                .andExpect(jsonPath("$.reviewDistribution").isArray())
                .andExpect(jsonPath("$.featuredDeal.storePublicId").value(storeId))
                .andExpect(jsonPath("$.distanceMeters").value(org.hamcrest.Matchers.nullValue()));
        mockMvc.perform(get("/api/stores/cities"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].cityCode").value("demo"));
        mockMvc.perform(get("/api/stores/{id}/dishes", storeId))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].name").isNotEmpty())
                .andExpect(jsonPath("$[0].priceCents").isNumber())
                .andExpect(jsonPath("$[0].id").doesNotExist());
    }

    /** 禁用跨请求会话后评价仍能转换门店公开 ID，防止真实 MySQL 出现懒加载 500。 */
    @Test
    void reviewsRemainReadableOutsideRepositoryTransaction() throws Exception {
        mockMvc.perform(get("/api/stores/{id}/reviews", publicIds.require("store", "store-001")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].storePublicId").isNotEmpty())
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());
    }

    /** 非法人均范围必须拒绝，不允许无效条件退化为未筛选目录。 */
    @Test
    void rejectsInvalidAverageSpendRange() throws Exception {
        mockMvc.perform(get("/api/stores").param("minPrice", "-1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/stores").param("minPrice", "10000").param("maxPrice", "5000"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void distanceSortRequiresUsableLocation() throws Exception {
        // 距离排序没有用户坐标时无法得到可信结果，必须返回明确的参数错误。
        mockMvc.perform(get("/api/stores").param("cityCode", "demo").param("sort", "DISTANCE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void listsStoresAndDealsByPublicId() throws Exception {
        // 公开 ID 不能暴露数据库自增主键，并且门店详情必须带出套餐入口。
        mockMvc.perform(get("/api/stores").param("cityCode", "demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].publicId").isNotEmpty())
                .andExpect(jsonPath("$[0].averageSpendCents").isNumber());

        mockMvc.perform(get("/api/stores/{storeId}/deals", publicIds.require("store", "store-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].publicId").value(org.hamcrest.Matchers.hasItem(publicIds.require("deal", "deal-001-1"))));
    }

    /** 目录 UUID 升级后不能继续允许猜测 store-001 或 deal-001-1 获取公开目录。 */
    @Test
    void legacyAliasesAreNotPublicCatalogRoutes() throws Exception {
        mockMvc.perform(get("/api/stores/store-001/deals")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/deals/deal-001-1")).andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/deals/{dealId}", publicIds.require("deal", "deal-001-1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").isNotEmpty())
                .andExpect(jsonPath("$.imageAlt").isNotEmpty());
        // 第二家门店必须返回自身审核的麻婆豆腐主菜图，不能借用第一家盖饭图片。
        mockMvc.perform(get("/api/deals/{dealId}", publicIds.require("deal", "deal-002-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(org.hamcrest.Matchers.startsWith(
                        "/catalog-images/v1/full/mapo-tofu-rice-")));
    }
    /** 防止投影遗漏新身份：两次真实读取必须返回相同UUID，排序或同名显示不改变身份。 */
    @Test
    void dishAndReviewPublicIdentityIsStableAcrossReads() throws Exception {
        String storeId = publicIds.require("store", "store-001");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String kind : java.util.List.of("dishes", "reviews")) {
            String path = "/api/stores/" + storeId + "/" + kind;
            var first = mapper.readTree(mockMvc.perform(get(path)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            var second = mapper.readTree(mockMvc.perform(get(path)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            org.junit.jupiter.api.Assertions.assertFalse(first.isEmpty(), "种子应提供实际列表");
            var identities = new java.util.HashSet<String>();
            for (int i = 0; i < first.size(); i++) {
                var row = first.get(i);
                org.junit.jupiter.api.Assertions.assertTrue(row.hasNonNull("publicId"), "公开列表必须返回稳定publicId:" + kind);
                String id = row.get("publicId").asText();
                org.junit.jupiter.api.Assertions.assertEquals(id, java.util.UUID.fromString(id).toString());
                org.junit.jupiter.api.Assertions.assertTrue(identities.add(id), "不同资源不可共用身份");
                org.junit.jupiter.api.Assertions.assertEquals(id, second.get(i).get("publicId").asText());
                org.junit.jupiter.api.Assertions.assertFalse(row.has("id"), "不暴露数据库数值主键");
            }
        }
    }

}
