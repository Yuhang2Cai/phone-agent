// 金额边界回归：验证十进制精确转换、null、负数、分以下精度和 int 溢出，禁止浮点金额。
package com.fandazi.core.money;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** 同时保护 JPA 写入和协议读取，失败必须显式报错，不能舍入或截断。 */
class CentsDecimalConverterTest {
    private final CentsDecimalConverter converter = new CentsDecimalConverter();
    /** 一分和最大整数分都精确往返，未支付 null 保持 null。 */
    @Test void centsRoundTripExactlyWithoutFloatingPoint() {
        for (int cents : new int[] {0, 1, 3334, 10001, Integer.MAX_VALUE}) {
            assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(cents))).isEqualTo(cents);
        }
        assertThat(converter.convertToDatabaseColumn(10001)).isEqualByComparingTo("100.01");
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
    /** 数据库异常精度、负数和溢出不能变成客户端看似合法的金额。 */
    @Test void invalidPrecisionAndRangeFailExplicitly() {
        assertThatThrownBy(() -> converter.convertToEntityAttribute(new BigDecimal("1.001"))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> converter.convertToEntityAttribute(new BigDecimal("21474836.48"))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> converter.convertToEntityAttribute(new BigDecimal("-0.01"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> converter.convertToDatabaseColumn(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
