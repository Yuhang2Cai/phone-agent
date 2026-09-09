package com.fandazi.feature.order;
public record CreateOrderCommand(Long cartDraftId,Long addressId,String idempotencyKey) {}
