// 身份认证模块：标识业务异常边界。
package com.fandazi.feature.auth;

public class AuthConflictException extends RuntimeException {
// 初始化对象依赖与默认状态。
    public AuthConflictException(String message) {
        super(message);
    }
}
