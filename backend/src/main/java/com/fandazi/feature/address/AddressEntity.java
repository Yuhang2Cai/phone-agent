// 收货地址模块：定义持久化数据结构。
package com.fandazi.feature.address;
import com.fandazi.feature.auth.UserEntity;
import jakarta.persistence.*;
@Entity @Table(name="addresses")
// 初始化对象依赖与默认状态。
// 获取id。
// 获取用户。
// 获取recipient。
// 获取phone。
// 获取detail。
// 判断默认、地址。
// 处理 `setDefaultAddress` 相关逻辑。
// 更新当前数据。
public class AddressEntity { @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @ManyToOne(optional=false) private UserEntity user; @Column(nullable=false) private String recipient; @Column(nullable=false) private String phone; @Column(nullable=false) private String detail; @Column(nullable=false) private boolean defaultAddress; protected AddressEntity(){} public AddressEntity(UserEntity user,String recipient,String phone,String detail,boolean defaultAddress){this.user=user;this.recipient=recipient;this.phone=phone;this.detail=detail;this.defaultAddress=defaultAddress;} public Long getId(){return id;} public UserEntity getUser(){return user;} public String getRecipient(){return recipient;} public String getPhone(){return phone;} public String getDetail(){return detail;} public boolean isDefaultAddress(){return defaultAddress;} void setDefaultAddress(boolean value){defaultAddress=value;} void update(String recipient,String phone,String detail){this.recipient=recipient;this.phone=phone;this.detail=detail;} }
