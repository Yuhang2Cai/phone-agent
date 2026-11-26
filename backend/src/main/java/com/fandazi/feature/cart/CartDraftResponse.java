// 购物车模块：定义对外返回的数据结构。
package com.fandazi.feature.cart;
import java.util.*;
// 处理 `from` 相关逻辑。
public record CartDraftResponse(Long id,Long merchantId,List<Item> items){ public record Item(Long dishId,int quantity,List<Long> optionIds){} public static CartDraftResponse from(CartDraft d){return new CartDraftResponse(d.getId(),d.getMerchant().getId(),d.getItems().stream().map(i->new Item(i.getDish().getId(),i.getQuantity(),i.getOptionIds())).toList());} }
