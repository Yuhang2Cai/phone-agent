// 团购售后服务：按用户和券状态判定退款资格，并记录待处理申请。
package com.fandazi.feature.refund;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.agent.ConfirmationGrantService;
import com.fandazi.feature.voucher.VoucherEntity;
import com.fandazi.feature.voucher.VoucherRepository;
import com.fandazi.feature.voucher.VoucherStatus;
import com.fandazi.feature.grouporder.GroupBuyOrderStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 退款申请只创建待处理记录，不在申请阶段擅自把券标为已退款。 */
@Service
public class RefundService {
    private final VoucherRepository vouchers;
    private final RefundRequestRepository requests;
    private final ConfirmationGrantService grants;

    /** 注入券、申请和确认凭证仓储。 */
    public RefundService(VoucherRepository vouchers, RefundRequestRepository requests, ConfirmationGrantService grants) {
        this.vouchers = vouchers; this.requests = requests; this.grants = grants;
    }

    /** 查询归属和申请资格；仅 `UNUSED` 且未重复申请的券允许申请。 */
    @Transactional(readOnly = true)
    public Map<String, Object> eligibility(Long userId, String voucherId) {
        VoucherEntity voucher = owned(userId, voucherId);
        assertEligible(voucher);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("voucherId", voucher.getPublicId());
        result.put("orderId", voucher.getOrder().getPublicId());
        result.put("dealTitle", voucher.getDeal().getTitle());
        // 退款按已成交订单的单位金额快照计算，套餐现价变化不能改变用户已支付的退款摘要。
        result.put("amountCents", voucher.getOrder().getTotalCents() / voucher.getOrder().getQuantity());
        result.put("expiresAt", voucher.getExpiresAt().toString());
        return result;
    }

    /** 为已经校验过的券签发退款确认凭证；写入仍须消费同一动作的凭证。 */
    @Transactional
    public Map<String, Object> prepare(Long userId, String sessionId, String voucherId) {
        Map<String, Object> result = new LinkedHashMap<>(eligibility(userId, voucherId));
        result.put("confirmationGrant", grants.issueForAction(userId, sessionId, "REQUEST_REFUND", voucherId,
                (Integer) result.get("amountCents"), "refund-v1").getGrantId());
        return result;
    }

    /** 校验归属、券状态和一次性确认后创建申请；并发请求由券行锁串行化。 */
    @Transactional
    public Map<String, Object> request(Long userId, String sessionId, String voucherId, String reason, String grantId) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new DomainException("退款原因长度不合法");
        VoucherEntity voucher = vouchers.findOwnedForUpdate(userId, voucherId)
                .orElseThrow(() -> new DomainException("团购券不存在"));
        assertEligible(voucher);
        // 资格先于凭证消费，确保已核销券不吞掉用户的确认凭证。
        grants.consumeBound(userId, sessionId, grantId, "REQUEST_REFUND", voucherId,
                voucher.getOrder().getTotalCents() / voucher.getOrder().getQuantity(), "refund-v1");
        RefundRequestEntity request = requests.save(new RefundRequestEntity(UUID.randomUUID().toString(), voucher,
                voucher.getUser(), reason.trim()));
        return summary(request);
    }

    /** 查询本人申请的真实状态，供进度页和 Agent 读取。 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long userId, String requestId) {
        return requests.findByUserIdAndPublicId(userId, requestId).map(this::summary)
                .orElseThrow(() -> new DomainException("退款申请不存在"));
    }

    /** 为已经按当前用户查询出的团购券计算只读退款入口资格；写入时仍须重新执行完整校验。 */
    @Transactional(readOnly = true)
    public boolean canRefund(VoucherEntity voucher) {
        // `UNUSED` 只是必要条件：未支付订单和已提交过申请的券都不能再次进入退款流程。
        return voucher.getStatus() == VoucherStatus.UNUSED
                && voucher.getOrder().getStatus() != GroupBuyOrderStatus.PENDING_PAYMENT
                && !requests.existsByVoucherId(voucher.getId());
    }

    /** 私有资格判断统一复用，避免准备页与提交页规则漂移。 */
    private void assertEligible(VoucherEntity voucher) {
        if (voucher.getStatus() == VoucherStatus.REDEEMED) throw new DomainException("已核销团购券不可退款");
        if (voucher.getStatus() != VoucherStatus.UNUSED) throw new DomainException("团购券当前不可退款");
        // 订单创建时就会生成未使用券；未付款阶段没有实际交易，不能产生退款申请。
        if (voucher.getOrder().getStatus() == GroupBuyOrderStatus.PENDING_PAYMENT) {
            throw new DomainException("未支付订单不可退款");
        }
        if (requests.existsByVoucherId(voucher.getId())) throw new DomainException("该团购券已申请退款");
    }

    /** 私有归属查询只按当前鉴权用户 ID 返回券，防止横向越权。 */
    private VoucherEntity owned(Long userId, String voucherId) {
        return vouchers.findByUserIdAndPublicId(userId, voucherId)
                .orElseThrow(() -> new DomainException("团购券不存在"));
    }

    /** 响应仅暴露公开编号与必要状态，不下发内部主键。 */
    private Map<String, Object> summary(RefundRequestEntity request) {
        return Map.of("requestId", request.getPublicId(), "voucherId", request.getVoucher().getPublicId(),
                "status", request.getStatus().name(), "reason", request.getReason(), "createdAt", request.getCreatedAt().toString());
    }
}
