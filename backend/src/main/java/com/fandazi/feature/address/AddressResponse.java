// 收货地址模块：定义对外返回的数据结构。
package com.fandazi.feature.address;
public record AddressResponse(Long id,String recipient,String phone,String detail,boolean defaultAddress,boolean valid){ public static AddressResponse from(AddressEntity a){return new AddressResponse(a.getId(),a.getRecipient(),a.getPhone(),a.getDetail(),a.isDefaultAddress(),true);} public static AddressResponse from(AddressEntity a, boolean valid){return new AddressResponse(a.getId(),a.getRecipient(),a.getPhone(),a.getDetail(),a.isDefaultAddress(),valid);} }
