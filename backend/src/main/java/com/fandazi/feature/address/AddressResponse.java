package com.fandazi.feature.address;
public record AddressResponse(Long id,String recipient,String phone,String detail,boolean defaultAddress){ public static AddressResponse from(AddressEntity a){return new AddressResponse(a.getId(),a.getRecipient(),a.getPhone(),a.getDetail(),a.isDefaultAddress());} }
