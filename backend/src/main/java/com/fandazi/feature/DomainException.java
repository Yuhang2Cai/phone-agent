// 应用模块：标识业务异常边界。
package com.fandazi.feature;

public class DomainException extends RuntimeException {
    public DomainException(String message) { super(message); }
}
