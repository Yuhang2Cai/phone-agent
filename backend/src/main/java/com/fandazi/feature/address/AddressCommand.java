// 收货地址模块：定义输入参数及其约束。
package com.fandazi.feature.address;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
public record AddressCommand(@NotBlank(message="收货人不能为空") String recipient,
                             @NotBlank(message="手机号不能为空") @Pattern(regexp="1\\d{10}", message="手机号格式无效") String phone,
                             @NotBlank(message="地址不能为空") String detail, boolean defaultAddress) {}
