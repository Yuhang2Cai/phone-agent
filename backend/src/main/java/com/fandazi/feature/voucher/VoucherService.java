// 团购券模块：提供用户查询、展示令牌和门店演示核销业务。
package com.fandazi.feature.voucher;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.refund.RefundService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 团购券应用服务；所有写操作都在事务中完成并按用户或门店校验权限。 */
@Service
public class VoucherService {
    private final VoucherRepository vouchers;
    private final VoucherTokenService tokens;
    private final RefundService refunds;

    /** 注入团购券仓储和令牌服务。 */
    public VoucherService(VoucherRepository vouchers, VoucherTokenService tokens, RefundService refunds) {
        this.vouchers = vouchers;
        this.tokens = tokens;
        this.refunds = refunds;
    }

    /** 返回当前用户所有团购券摘要，不下发可伪造或长期复用的二维码字段。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long userId) {
        return vouchers.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::summary).toList();
    }

    /** 返回当前用户的一张团购券详情。 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long userId, String publicId) {
        return vouchers.findByUserIdAndPublicId(userId, publicId)
                .map(this::summary)
                .orElseThrow(() -> new DomainException("团购券不存在"));
    }

    /** 仅为当前用户的指定团购券生成短时二维码展示令牌。 */
    @Transactional(readOnly = true)
    public Map<String, Object> displayToken(Long userId, String publicId) {
        VoucherEntity voucher = vouchers.findByUserIdAndPublicId(userId, publicId)
                .orElseThrow(() -> new DomainException("团购券不存在"));
        if (voucher.getStatus() != VoucherStatus.UNUSED) throw new DomainException("团购券当前不可出示");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("voucherId", voucher.getPublicId());
        result.put("token", tokens.issue(voucher));
        result.put("expiresAt", java.time.Instant.now().plusSeconds(300));
        return result;
    }

    /**
     * 受保护的演示核销接口：签名令牌负责证明券身份，门店 ID 再负责证明核销地点。
     * 重复提交已核销券返回同一状态，避免网络重试造成重复消费。
     */
    @Transactional
    public Map<String, Object> redeem(String token, String storePublicId) {
        final String voucherId;
        try {
            voucherId = tokens.parseVoucherPublicId(token);
        } catch (RuntimeException exception) {
            throw new DomainException("核销码无效或已过期");
        }
        VoucherEntity voucher = vouchers.findByPublicId(voucherId).orElseThrow(() -> new DomainException("团购券不存在"));
        try {
            voucher.redeem(storePublicId);
        } catch (IllegalStateException exception) {
            throw new DomainException(exception.getMessage());
        }
        return summary(voucher);
    }

    /** 构造不包含数据库内部 ID、二维码密钥的安全响应。 */
    private Map<String, Object> summary(VoucherEntity voucher) {
        // 与订单内嵌券共用同一投影，避免退款金额、UTC 时间和图片字段在两种读取路径中漂移。
        return VoucherSummary.from(voucher, refunds.canRefund(voucher));
    }
}
