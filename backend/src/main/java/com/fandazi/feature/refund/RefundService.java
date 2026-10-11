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

    /** 查询本人已支付、未使用、未过期且无申请的券，返回实际分摊金额和页面摘要；不合资格抛出领域异常。 */
    @Transactional(readOnly = true)
    public Map<String, Object> eligibility(Long userId, String voucherId) {
        VoucherEntity voucher = owned(userId, voucherId);
        assertEligible(voucher);
        Map<String, Object> result = resourceSummary(voucher);
        // 该值来自支付事务的券分摊快照，余分已在支付时保存，不能再整除或读取套餐现价。
        result.put("amountCents", voucher.getAmountCents());
        result.put("canRefund", true);
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
        // 在券行锁前识别任务凭证，旧入口不得与任务grant消费形成反向等待。
        grants.requireDomainEntry(userId,sessionId,grantId);
        VoucherEntity voucher = vouchers.findOwnedForUpdate(userId, voucherId)
                .orElseThrow(() -> new DomainException("团购券不存在"));
        assertEligible(voucher);
        // 资格先于凭证消费，确保已核销券不吞掉用户的确认凭证。
        grants.consumeBound(userId, sessionId, grantId, "REQUEST_REFUND", voucherId,
                voucher.getAmountCents(), "refund-v1");
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

    /** 先核对本人券，再查询已持久化的唯一申请；未申请返回空，不制造进度或暴露他人资源。 */
    @Transactional(readOnly = true)
    public java.util.Optional<Map<String, Object>> detailByVoucher(Long userId, String voucherId) {
        // canRefund=false 也可能表示已核销/过期，因此不能据此猜测已有申请；必须查询真实记录。
        owned(userId, voucherId);
        return requests.findByUserIdAndVoucherPublicId(userId, voucherId).map(this::summary);
    }

    /** 为已经按当前用户查询出的团购券计算只读退款入口资格；写入时仍须重新执行完整校验。 */
    @Transactional(readOnly = true)
    public boolean canRefund(VoucherEntity voucher) {
        // `UNUSED` 只是必要条件：未支付订单和已提交过申请的券都不能再次进入退款流程。
        return voucher.getStatus() == VoucherStatus.UNUSED
                && hasEligiblePayment(voucher)
                && voucher.getExpiresAt().isAfter(com.fandazi.core.time.BusinessTime.now())
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
        // 只接受存在支付事实的有效订单状态；取消、过期或全退订单即使残留 UNUSED 券也拒绝。
        if (!hasEligiblePayment(voucher)) throw new DomainException("订单当前不可退款或缺少支付事实");
        if (!voucher.getExpiresAt().isAfter(com.fandazi.core.time.BusinessTime.now()))
            throw new DomainException("团购券已过期，不可退款");
        if (requests.existsByVoucherId(voucher.getId())) throw new DomainException("该团购券已申请退款");
    }

    /** 支付快照与聚合状态共同证明可退交易；不能把“不是待支付”视作已付款证据。 */
    private boolean hasEligiblePayment(VoucherEntity voucher) {
        var order = voucher.getOrder();
        return (order.getStatus() == GroupBuyOrderStatus.PAID || order.getStatus() == GroupBuyOrderStatus.PARTIALLY_USED)
                && order.getPaidTotalCents() != null && voucher.getAmountCents() != null;
    }

    /** 私有归属查询只按当前鉴权用户 ID 返回券，防止横向越权。 */
    private VoucherEntity owned(Long userId, String voucherId) {
        return vouchers.findByUserIdAndPublicId(userId, voucherId)
                .orElseThrow(() -> new DomainException("团购券不存在"));
    }

    /** 响应仅暴露公开编号与必要状态，不下发内部主键。 */
    private Map<String, Object> summary(RefundRequestEntity request) {
        var result = resourceSummary(request.getVoucher());
        // 申请阶段只表示 REQUESTED，不将状态写成已退款或已到账，金额来自申请自己的持久化快照。
        result.put("requestId", request.getPublicId()); result.put("amountCents", request.getAmountCents());
        result.put("status", request.getStatus().name()); result.put("reason", request.getReason());
        result.put("createdAt", com.fandazi.core.time.BusinessTime.utc(request.getCreatedAt()));
        return result;
    }

    /** 准备与进度共用真实资源摘要；可空图片使用可变 Map，禁止因 Map.of 拒绝 null 而丢字段。 */
    private Map<String, Object> resourceSummary(VoucherEntity voucher) {
        var order = voucher.getOrder(); var deal = voucher.getDeal();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("voucherId", voucher.getPublicId()); result.put("orderId", order.getPublicId());
        result.put("storeId", order.getStore().getPublicId()); result.put("dealId", deal.getPublicId());
        result.put("storeName", order.getStore().getName()); result.put("dealTitle", deal.getTitle());
        result.put("expiresAt", com.fandazi.core.time.BusinessTime.utc(voucher.getExpiresAt()));
        result.put("imageUrl", deal.getImageUrl()); result.put("imageAlt", deal.getImageAlt());
        return result;
    }
}
