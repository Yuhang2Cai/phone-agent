// 应用模块：标识业务异常边界。
package com.fandazi.feature;

public class DomainException extends RuntimeException {
// 初始化对象依赖与默认状态。
    public DomainException(String message) { super(message); }
}
