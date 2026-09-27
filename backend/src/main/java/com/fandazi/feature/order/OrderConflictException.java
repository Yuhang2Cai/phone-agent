// 订单模块：标识业务异常边界。
package com.fandazi.feature.order;
// 初始化对象依赖与默认状态。
public class OrderConflictException extends RuntimeException { public OrderConflictException(String message){super(message);} }
