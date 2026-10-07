// 注册昵称契约回归：昵称必须从真实请求进入用户持久化实体并出现在响应，空昵称兼容旧账号。
package com.fandazi.feature.auth;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fandazi.core.security.JwtService;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 不连接任何业务库；验证请求、实体写入与响应绑定，防止端上仅展示却悄悄丢弃昵称。 */
class AuthNicknameTest {
    private final ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 昵称前后空白规范化后必须随保存的真实实体返回，不能伪造为客户端显示值。 */
    @Test void nicknameIsSavedAndReturned() throws Exception {
        UserRepository users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        JwtService jwt = mock(JwtService.class);
        when(encoder.encode(anyString())).thenReturn("test-hash");
        when(users.saveAndFlush(any(UserEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        AuthDtos.RegisterRequest request = json.readValue(
                "{\"username\":\"test_nickname\",\"password\":\"test-password-123\",\"nickname\":\"  测试食客  \"}", AuthDtos.RegisterRequest.class);
        var reply = new AuthService(users, encoder, jwt).register(request);
        assertThat(json.valueToTree(reply).path("user").path("nickname").asText()).isEqualTo("测试食客");
    }

    /** 空字段仍可按旧账号密码方式注册；协议不得引入强制验证码或强制昵称。 */
    @Test void missingNicknameKeepsOldRegistrationCompatible() throws Exception {
        var request = json.readValue("{\"username\":\"test_optional\",\"password\":\"test-password-123\"}", AuthDtos.RegisterRequest.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isEmpty();
        }
    }

    /** 限制昵称长度与新增列一致，防止数据库截断或接受端侧无法完整显示的无界输入。 */
    @Test void tooLongNicknameIsRejected() throws Exception {
        var request = json.readValue("{\"username\":\"test_limit\",\"password\":\"test-password-123\",\"nickname\":\"" + "a".repeat(41) + "\"}", AuthDtos.RegisterRequest.class);
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request)).isNotEmpty();
        }
    }
}
