// 对比事实测试：通过真实响应结构防止假人数/假内容；缺字段红测使用断言而不是反射异常。
package com.fandazi.feature.catalog;
import java.util.Arrays;
import java.util.List;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
/** 冻结三个新增可空事实字段，旧字段继续兼容。 */
class CatalogCompareFactsTest {
    /** 当前模型缺少权威人数、结构化内容及使用规则，应准确触发字段集合断言。 */
    @Test void exposesNullableAuthoritativeCompareFields(){
        Set<String> names=Arrays.stream(DealPackageResponse.class.getRecordComponents()).map(x->x.getName()).collect(Collectors.toSet());
        assertThat(names).as("对比必需的可空权威字段缺失").contains("peopleCount","contents","usageRules");
    }

    /** 标题写双人也不能补人数；旧响应有新字段但值明确null，金额/库存保持。 */
    @Test void oldRowsRemainUnknown(){var d=deal("fixture-deal",true);var r=DealPackageResponse.from(d);assertThat(r.peopleCount()).isNull();assertThat(r.contents()).isNull();assertThat(r.usageRules()).isNull();assertThat(r.priceCents()).isEqualTo(8800);}
    /** 权威可空资料完整往返，返回不可变集合，外部输入变化不能改已保存事实。 */
    @Test void structuredFactsRoundTripWithoutTitleInference(){var d=deal("fixture-deal",true);var input=new java.util.ArrayList<>(List.of(new DealContent("合成盖饭",2)));d.replaceCompareFacts(2,input,"到店使用");input.clear();var r=DealPackageResponse.from(d);assertThat(r.peopleCount()).isEqualTo(2);assertThat(r.contents()).containsExactly(new DealContent("合成盖饭",2));var converter=new DealContentsConverter();assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(r.contents()))).isEqualTo(r.contents());assertThatThrownBy(()->r.contents().clear()).isInstanceOf(UnsupportedOperationException.class);}
    /** 非法资料必须在全部校验后才更新，不能覆盖既有真实资料的一部分。 */
    @Test void invalidFactsDoNotPartiallyReplace(){var d=deal("fixture-deal",true);d.replaceCompareFacts(2,List.of(new DealContent("合成盖饭",2)),"原规则");assertThatThrownBy(()->d.replaceCompareFacts(0,null,"错误规则")).isInstanceOf(IllegalArgumentException.class);assertThat(d.getPeopleCount()).isEqualTo(2);assertThat(d.getUsageRules()).isEqualTo("原规则");assertThatThrownBy(()->new DealContentsConverter().convertToEntityAttribute("[{\"name\":\"合成菜\",\"quantity\":0}]")).isInstanceOf(IllegalArgumentException.class);}
    /** 下架套餐公开详情仍可只读辨认，购买require继续拒绝，不能因公开读取放宽交易资格。 */
    @Test void inactivePublicDetailDoesNotAuthorizePurchase(){var repo=mock(DealPackageRepository.class);var d=deal("fixture-deal",false);when(repo.findByPublicId("fixture-deal")).thenReturn(Optional.of(d));var service=new DealPackageService(repo,mock(StoreService.class));assertThat(service.detail("fixture-deal").active()).isFalse();assertThatThrownBy(()->service.require("fixture-deal")).hasMessageContaining("暂不可购买");}
    /** 独立合成实体，不使用旧订单/券或运行期默认套餐。 */
    private DealPackageEntity deal(String id,boolean active){var store=new StoreEntity("fixture-store","合成品牌","合成门店","demo","合成地址","000","合成品类",BigDecimal.ZERO,BigDecimal.ZERO,8800,true,"10:00–20:00",null);return new DealPackageEntity(id,store,"合成双人餐","合成说明",8800,10800,10,LocalDate.of(2020,1,1),LocalDate.of(2030,1,1),active,5,1);}
}
