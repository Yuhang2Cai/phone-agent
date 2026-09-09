package com.fandazi.feature.catalog;
import java.math.BigDecimal;
public record MerchantResponse(Long id,String name,String category,BigDecimal minimumOrder,BigDecimal deliveryFee,int deliveryMinutes,boolean open){ public static MerchantResponse from(MerchantEntity m){return new MerchantResponse(m.getId(),m.getName(),m.getCategory(),m.getMinimumOrder(),m.getDeliveryFee(),m.getDeliveryMinutes(),m.isOpen());} }
