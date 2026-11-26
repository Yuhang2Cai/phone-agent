// 金额持久化边界：团购 Java 接口使用整数分，JPA 以十进制元读写数据库；依赖标准 BigDecimal。
package com.fandazi.core.money;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** 显式绑定到金额字段，避免自动转换数量或其他整数；非法精度、负数与整数溢出立即失败。 */
@Converter
public class CentsDecimalConverter implements AttributeConverter<Integer, BigDecimal> {
    /** 将分精确换算为两位小数的元；null 表示尚未付款等未知金额，负数抛出 IllegalArgumentException。 */
    @Override
    public BigDecimal convertToDatabaseColumn(Integer cents) {
        if (cents == null) return null;
        if (cents < 0) throw new IllegalArgumentException("金额不能为负数");
        // 十进制位移不经过浮点数，整数分无论大小都不会产生二进制舍入误差。
        return BigDecimal.valueOf(cents).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** 将数据库元转换为协议整数分；存在小于一分的精度或超过 int 范围时抛出 ArithmeticException。 */
    @Override
    public Integer convertToEntityAttribute(BigDecimal amount) {
        if (amount == null) return null;
        if (amount.signum() < 0) throw new IllegalArgumentException("金额不能为负数");
        // 只允许精确转换，禁止舍去多余小数、溢出截断或伪造零金额。
        return amount.movePointRight(2).intValueExact();
    }
}
