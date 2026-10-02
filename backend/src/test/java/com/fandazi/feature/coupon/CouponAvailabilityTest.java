// 优惠券读取投影回归：隔离仓储后验证上海业务日的可用状态，不修改领取记录或扩展抵扣能力。
package com.fandazi.feature.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fandazi.core.time.BusinessTime;
import com.fandazi.feature.auth.UserEntity;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

/** AVAILABLE 只代表有效期内的启用券；终态领取事实优先，当前展示计算不得回写数据库。 */
class CouponAvailabilityTest {
    /** 合成身份仅用于检查仓储调用仍按当前用户过滤，不是可登录账号或真实用户主键。 */
    private static final Long OWNER = 77L;

    /** 覆盖过期、未生效、停用、首末日以及历史终态，防止状态修复损坏归属或持久化事实。 */
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "expired,-10,-1,true,AVAILABLE,EXPIRED",
            "future,1,10,true,AVAILABLE,NOT_YET_VALID",
            "disabled,-1,1,false,AVAILABLE,DISABLED",
            "valid,-1,1,true,AVAILABLE,AVAILABLE",
            "first_day,0,1,true,AVAILABLE,AVAILABLE",
            "last_day,-1,0,true,AVAILABLE,AVAILABLE",
            "used,-10,-1,false,USED,USED",
            "persisted_expired,-1,1,true,EXPIRED,EXPIRED"
    })
    void readStatusReflectsCurrentRulesWithoutChangingClaim(String scenario, int startOffset, int endOffset,
                                                           boolean active, String persisted, String expected) {
        LocalDate today = LocalDate.now(BusinessTime.ZONE);
        var definition = new CouponEntity(UUID.randomUUID().toString(), "隔离状态测试券", 5000, 500,
                today.plusDays(startOffset), today.plusDays(endOffset), "只读测试范围", active);
        var claim = new UserCouponEntity(UUID.randomUUID().toString(), new UserEntity("isolated", "isolated@example.invalid", "隔离哈希"), definition);
        // 仅测试对象设定已使用/已过期对照；生产读取不进行实体状态转换或更新。
        ReflectionTestUtils.setField(claim, "status", CouponStatus.valueOf(persisted));
        var repository = mock(UserCouponRepository.class);
        when(repository.findByUserIdOrderByClaimedAtDesc(OWNER)).thenReturn(List.of(claim));
        var result = new CouponService(repository).list(OWNER);
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("status", expected)
                .containsEntry("validFrom", definition.getValidFrom().toString())
                .containsEntry("validTo", definition.getValidTo().toString());
        // 当前状态只是结果投影，已领记录保持原状态；仓储查询不能退化为平台全量查询。
        assertThat(claim.getStatus().name()).isEqualTo(persisted);
        verify(repository).findByUserIdOrderByClaimedAtDesc(OWNER);
    }
}
