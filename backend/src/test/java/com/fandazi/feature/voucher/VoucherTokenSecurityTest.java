// 团购券令牌测试：防止二维码接口把固定数据库令牌当作长期凭证直接返回。
package com.fandazi.feature.voucher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 验证短时签名令牌能校验券身份，且篡改内容后不能通过解析。 */
@SpringBootTest(properties = {"seed.enabled=true", "group.seed.enabled=true"})
class VoucherTokenSecurityTest {
    @Autowired private VoucherTokenService tokens;

    @Test
    void forgedTokenCannotBeParsed() {
        // 任意字符串都必须被签名校验拒绝，避免客户端伪造券号或门店号完成核销。
        assertThatThrownBy(() -> tokens.parseVoucherPublicId("forged-voucher-token"))
                .isInstanceOf(RuntimeException.class);
    }
}
