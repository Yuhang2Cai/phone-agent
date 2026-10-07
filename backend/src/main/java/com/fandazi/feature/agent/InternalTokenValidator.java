// 智能助手模块：定义该模块的数据与行为。
package com.fandazi.feature.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Component class InternalTokenValidator {
    private final byte[] expected;
// 初始化对象依赖与默认状态。
    InternalTokenValidator(@Value("${agent.internal-token:}") String token){expected=token.getBytes(StandardCharsets.UTF_8);}
// 校验当前数据。
    void validate(String actual){if(expected.length==0||actual==null||!MessageDigest.isEqual(expected,actual.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"内部 Agent 凭据无效");}
}
