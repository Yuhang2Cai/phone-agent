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
        // 缺图是明确的 null 字段，服务端不能用另一套餐图片兜底。
        mockMvc.perform(get("/api/deals/{dealId}", publicIds.require("deal", "deal-002-1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imageUrl").value(org.hamcrest.Matchers.nullValue()));
    }
}
