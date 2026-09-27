// 订单模块：标识业务异常边界。
package com.fandazi.feature.order;
public class OrderConflictException extends RuntimeException { public OrderConflictException(String message){super(message);} }
