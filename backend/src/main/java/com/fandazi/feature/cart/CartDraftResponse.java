package com.fandazi.feature.cart;
import java.util.*;
public record CartDraftResponse(Long id,Long merchantId,List<Item> items){ public record Item(Long dishId,int quantity,List<Long> optionIds){} public static CartDraftResponse from(CartDraft d){return new CartDraftResponse(d.getId(),d.getMerchant().getId(),d.getItems().stream().map(i->new Item(i.getDish().getId(),i.getQuantity(),i.getOptionIds())).toList());} }
