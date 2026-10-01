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
        // 不能使用 Map.of，因为未核销券的 redeemedAt 为 null；有序可变 Map 同时保证 JSON 字段稳定。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("publicId", voucher.getPublicId());
        data.put("orderId", voucher.getOrder().getPublicId());
        data.put("dealId", voucher.getDeal().getPublicId());
        data.put("dealTitle", voucher.getDeal().getTitle());
        data.put("storeId", voucher.getDeal().getStore().getPublicId());
        data.put("storeName", voucher.getDeal().getStore().getName());
        data.put("status", voucher.getStatus().name());
        // 售后入口资格由服务端统一计算，Android 不应把 `UNUSED` 直接等同于可以退款。
        data.put("canRefund", refunds.canRefund(voucher));
        // 券摘要同时用于工具审计，统一用 ISO 时间字符串，避免持久化转换器无法序列化 Java 时间对象。
        data.put("expiresAt", voucher.getExpiresAt().toString());
        data.put("redeemedAt", voucher.getRedeemedAt() == null ? null : voucher.getRedeemedAt().toString());
        return data;
    }
}
