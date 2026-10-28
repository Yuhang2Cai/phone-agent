// 准入测试：固定窗口、并行计数和身份隔离，不通过真实等待拖长验证。
package com.fandazi.feature.activity;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 额度只保护服务，不代替事务、身份校验或幂等。 */
class ActivityAdmissionTest {
    /** 第121次写入被拒绝，窗口到点重新计数；拒绝不得污染其他身份或读取额度。 */
    @Test void rejectsExcessWritesAndResetsAtExactBoundary() {
        var now=new AtomicLong(0);var gate=new ActivityAdmission(now::get);
        for(int i=0;i<120;i++)gate.check("user:a",true);
        assertEquals(429,assertThrows(ActivityException.class,()->gate.check("user:a",true)).status());
        assertDoesNotThrow(()->gate.check("user:a",false));assertDoesNotThrow(()->gate.check("user:b",true));
        now.set(60_000_000_000L);assertDoesNotThrow(()->gate.check("user:a",true));
    }
}
