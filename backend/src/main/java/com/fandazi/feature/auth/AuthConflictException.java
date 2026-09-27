// 身份认证模块：标识业务异常边界。
package com.fandazi.feature.auth;

public class AuthConflictException extends RuntimeException {
    public AuthConflictException(String message) {
        super(message);
    }
}
