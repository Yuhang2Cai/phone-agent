package com.fandazi.feature.cart;
import java.math.BigDecimal; import java.util.List;
public record CartQuote(Long merchantId,List<Line> lines,BigDecimal subtotal,BigDecimal deliveryFee,BigDecimal discount,BigDecimal total){ public record Line(Long dishId,String dishName,int quantity,List<Long> optionIds,String optionSummary,BigDecimal unitPrice,BigDecimal lineTotal){} }
