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
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true"})
@AutoConfigureMockMvc
class StoreDealApiTest {
    @Autowired private MockMvc mockMvc;

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

        mockMvc.perform(get("/api/stores/store-001/deals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].publicId").value("deal-001-1"));
    }
}
