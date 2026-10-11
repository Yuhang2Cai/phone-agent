// 团购订单模块：消费确认凭证后创建待支付订单，并保证幂等重放安全。
package com.fandazi.feature.grouporder;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.agent.ConfirmationGrantService;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.catalog.DealPackageRepository;
import com.fandazi.feature.catalog.StoreReviewRepository;
import com.fandazi.feature.refund.RefundService;
import com.fandazi.feature.voucher.VoucherStatus;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 团购订单写服务。 */
@Service
public class GroupBuyOrderService {
    private final GroupBuyOrderRepository orders;
    private final UserRepository users;
    private final DealPackageRepository deals;
    private final ConfirmationGrantService confirmations;
    private final StoreReviewRepository reviews;
    private final RefundService refunds;

    /** 注入订单、用户、套餐和确认凭证依赖。 */
    public GroupBuyOrderService(GroupBuyOrderRepository orders, UserRepository users, DealPackageRepository deals,
                                ConfirmationGrantService confirmations, StoreReviewRepository reviews,
                                RefundService refunds) {
        this.orders = orders; this.users = users; this.deals = deals; this.confirmations = confirmations;
        this.reviews = reviews;
        this.refunds = refunds;
    }

    /** 创建待支付团购订单；同一幂等键和指纹重复提交直接返回原订单。 */
    @Transactional
    public Map<String, Object> create(Long userId, String sessionId, String dealId, int quantity, String grantId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new DomainException("缺少订单幂等键");
        var existing = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            // 同一幂等键只能代表同一个套餐、版本、数量请求；指纹不同必须拒绝，不能返回旧订单冒充新请求。
            if (!existing.get().getRequestFingerprint().startsWith(dealId + ":")) throw new DomainException("幂等键对应不同请求");
            return data(existing.get());
        }
        var user = users.findById(userId).orElseThrow(() -> new DomainException("用户不存在"));
        var deal = deals.findByPublicId(dealId).orElseThrow(() -> new DomainException("套餐不存在"));
        if (quantity <= 0 || quantity > deal.getPurchaseLimit() || quantity > deal.getInventory()) throw new DomainException("购买数量不可用");
        int total = Math.multiplyExact(deal.getPriceCents(), quantity);
        confirmations.consume(userId, sessionId, grantId, dealId, "deal-v" + deal.getVersion());
        var order = new GroupBuyOrderEntity(UUID.randomUUID().toString(), user, deal.getStore(), deal, idempotencyKey, quantity,
                total, GroupBuyOrderStatus.PENDING_PAYMENT, com.fandazi.core.time.BusinessTime.now().plusDays(30), deal.getPublicId() + ":" + deal.getVersion() + ":" + quantity);
        return data(orders.save(order));
    }

    /** 模拟支付待支付团购订单，重复支付保持幂等。 */
    @Transactional
    public Map<String, Object> pay(Long userId, String publicId, String idempotencyKey) {
        // 页面支付串行锁住本人订单；两个按钮请求只能保存一次支付快照，不重新生成券。
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new DomainException("缺少支付幂等键");
        var order = orders.findOwnedForUpdate(userId, publicId).orElseThrow(() -> new DomainException("团购订单不存在"));
        if (order.getStatus() == GroupBuyOrderStatus.PAID) return data(order);
        if (order.getStatus() != GroupBuyOrderStatus.PENDING_PAYMENT) throw new DomainException("订单当前不可支付");
        if (!order.getExpiresAt().isAfter(com.fandazi.core.time.BusinessTime.now()))
            throw new com.fandazi.feature.agent.ToolBoundaryException("EXPIRED", "订单已过期，不能支付");
        order.markPaid();
        return data(order);
    }

    /** 查询当前用户的团购订单列表，响应中附带每张券的公开摘要。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(Long userId) {
        return orders.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::data).toList();
    }

    /** 查询当前用户指定团购订单的完整详情。 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long userId, String publicId) {
        return data(orders.findByUserIdAndPublicId(userId, publicId).orElseThrow(() -> new DomainException("团购订单不存在")));
    }

    /** 输入当前认证用户，返回页面列表专用投影；只读数据库，不改变订单或工具协议。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listForPage(Long userId) {
        // 本人过滤在仓储完成，页面补充字段不能扩大到其他用户的订单。
        return orders.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::pageData).toList();
    }

    /** 输入本人用户和公开订单号，读取页面详情；资源不存在或不属于本人时统一拒绝。 */
    @Transactional(readOnly = true)
    public Map<String, Object> detailForPage(Long userId, String publicId) {
        var order = orders.findByUserIdAndPublicId(userId, publicId)
                .orElseThrow(() -> new DomainException("团购订单不存在"));
        return pageData(order);
    }

    /** 页面专用字段来自持久化支付快照及当前套餐说明，合法空值表示尚无付款事实。 */
    private Map<String, Object> pageData(GroupBuyOrderEntity order) {
        Map<String, Object> result = new LinkedHashMap<>(data(order));
        // 不能用订单状态推定实付：取消或过期订单可能从未支付，必须直接读取数据库快照。
        result.put("paidTotalCents", order.getPaidTotalCents());
        result.put("paidAt", com.fandazi.core.time.BusinessTime.utc(order.getPaidAt()));
        result.put("expiresAt", com.fandazi.core.time.BusinessTime.utc(order.getExpiresAt()));
        // 当前没有独立使用规则字段，原样显示套餐说明；不补 Figma 示例中的自动退款承诺。
        result.put("dealDescription", order.getDeal().getDescription());
        return result;
    }

    /** V1 按真实报价创建订单；锁住套餐后重查版本、金额、日期和库存，任何变化均要求重新报价。 */
    @Transactional
    public Map<String, Object> createFromQuote(Long userId, String sessionId, com.fandazi.feature.purchase.PurchaseQuote quote,
                                               String grantId, String idempotencyKey) {
        // 输入校验：页面与 Agent 共用报价写入。按用户串行检查幂等键，避免并发先查后写产生第二单。
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 255)
            throw new DomainException("订单幂等键无效");
        // 必须在任何领域行锁前拒绝无任务上下文的新grant，避免页面user/order→grant与Agent栅栏锁序反转。
        confirmations.requireDomainEntry(userId,sessionId,grantId);
        entityManager.find(com.fandazi.feature.auth.UserEntity.class, userId, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        // HTTP 报价读取事务已结束，参数可能脱管；重新取写事务内的报价并锁定，确保 consume 真正入库。
        // 与用户锁使用一致顺序，页面和 Agent 都从数据库权威状态判断期限/单次消费，不能依赖旧对象。
        quote = entityManager.find(com.fandazi.feature.purchase.PurchaseQuote.class, quote.getId(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (quote == null) throw new com.fandazi.feature.agent.ToolBoundaryException("NOT_FOUND", "购买报价不存在");
        if (!quote.getUser().getId().equals(userId)) throw new com.fandazi.feature.agent.ToolBoundaryException("FORBIDDEN", "报价不属于当前用户");
        var binding = confirmations.owned(userId, sessionId, grantId);
        if (!quote.getPublicId().equals(binding.getResourceId()))
            throw new com.fandazi.feature.agent.ToolBoundaryException("CONFLICT", "确认凭证对应不同报价");
        var existing = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            // 重放返回已经保存的事实；不同数量、金额或版本不能借相同键获得旧订单。
            if (!existing.get().getRequestFingerprint().equals(quote.getRequestFingerprint()))
                throw new com.fandazi.feature.agent.ToolBoundaryException("CONFLICT", "幂等键对应不同报价");
            return data(existing.get());
        }
        if (!quote.getExpiresAt().isAfter(java.time.Instant.now()))
            throw new com.fandazi.feature.agent.ToolBoundaryException("EXPIRED", "购买报价已过期，请重新报价");
        if (!"ACTIVE".equals(quote.getStatus()))
            throw new com.fandazi.feature.agent.ToolBoundaryException("CONFLICT", "购买报价已使用");
        // 数据恢复：报价是持久化事实，禁止退回默认数量或从载荷重新构造价格。
        var deal = deals.findByPublicId(quote.getDeal().getPublicId()).orElseThrow(() -> new DomainException("套餐不存在"));
        // 套餐行锁让库存校验和扣减成为原子步骤，避免两个会话同时消费最后一份库存。
        deal = entityManager.find(com.fandazi.feature.catalog.DealPackageEntity.class, deal.getId(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        entityManager.refresh(deal);
        int quantity = quote.getQuantity();
        String expected = deal.getPublicId() + ":" + deal.getVersion() + ":" + quantity + ":" + quote.getTotalCents();
        if (!quote.getRequestFingerprint().equals(expected) || quote.getTotalCents() != Math.multiplyExact(deal.getPriceCents(), quantity))
            throw new com.fandazi.feature.agent.ToolBoundaryException("CONFLICT", "套餐价格或版本已变化，请重新报价");
        if (!deal.isActive() || quantity > deal.getInventory() || quantity > deal.getPurchaseLimit())
            throw new com.fandazi.feature.agent.ToolBoundaryException("CONFLICT", "套餐库存或限购条件已变化");
        if (java.time.LocalDate.now().isBefore(deal.getValidFrom()) || java.time.LocalDate.now().isAfter(deal.getValidTo()))
            throw new com.fandazi.feature.agent.ToolBoundaryException("EXPIRED", "套餐不在有效期内");
        // 规则判断：专属凭证绑定报价编号、金额和当前套餐版本，消费与订单写入一起提交。
        confirmations.consumeBound(userId, sessionId, grantId, "CREATE_GROUP_BUY_ORDER", quote.getPublicId(), quote.getTotalCents(), "deal-v" + deal.getVersion());
        var order = orders.save(new GroupBuyOrderEntity(UUID.randomUUID().toString(), quote.getUser(), deal.getStore(), deal,
                idempotencyKey, quantity, quote.getTotalCents(), GroupBuyOrderStatus.PENDING_PAYMENT,
                deal.getValidTo().plusDays(1).atStartOfDay(), quote.getRequestFingerprint()));
        quote.consume();
        // 原子更新真实库存；不改动历史金额字段或 Task2 页面迁移结构，刷新实体避免后续投影读取旧库存。
        entityManager.createQuery("update DealPackageEntity d set d.inventory = d.inventory - :quantity where d.id = :id")
                .setParameter("quantity", quantity).setParameter("id", deal.getId()).executeUpdate();
        entityManager.refresh(deal);
        return data(order);
    }

    /** V1 支付必须消费 PAY_MOCK_ORDER 专属金额授权，不能复用购买确认或旧外卖支付流程。 */
    @Transactional
    public Map<String, Object> payConfirmed(Long userId, String sessionId, String orderId, String grantId) {
        // 旧页面不能先锁订单再等待任务凭证；新任务支付只从已校验栅栏的内部路径进入。
        confirmations.requireDomainEntry(userId,sessionId,grantId);
        var order = orders.findOwnedForUpdate(userId, orderId).orElseThrow(() -> new com.fandazi.feature.agent.ToolBoundaryException("NOT_FOUND", "团购订单不存在"));
        if (order.getStatus() != GroupBuyOrderStatus.PENDING_PAYMENT)
            throw new com.fandazi.feature.agent.ToolBoundaryException("CONFLICT", "订单当前不可支付");
        // 支付授权有效也不能支付业务已到期订单，日期与凭证期限分别校验。
        if (!order.getExpiresAt().isAfter(com.fandazi.core.time.BusinessTime.now()))
            throw new com.fandazi.feature.agent.ToolBoundaryException("EXPIRED", "订单已过期，不能支付");
        confirmations.consumeBound(userId, sessionId, grantId, "PAY_MOCK_ORDER", orderId, order.getTotalCents(), "pay-v1");
        order.markPaid();
        return data(order);
    }

    /** 仅供领域原子库存更新使用，所有操作都在现有订单事务中执行。 */
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;

    /** 构造对外订单摘要，避免暴露数据库自增主键和实体关联。 */
    private Map<String, Object> data(GroupBuyOrderEntity order) {
        Map<String, Object> data = new LinkedHashMap<>(); data.put("orderId", order.getPublicId()); data.put("dealId", order.getDeal().getPublicId());
        data.put("storeId", order.getStore().getPublicId()); data.put("quantity", order.getQuantity()); data.put("totalCents", order.getTotalCents());
        data.put("status", order.getStatus().name()); data.put("voucherCount", order.getVouchers().size());
        // 聊天和订单摘要的名称来自持久化门店、套餐，不让 Agent 补造卡片展示字段。
        data.put("storeName", order.getStore().getName()); data.put("dealTitle", order.getDeal().getTitle());
        // 创建时间来自订单事实，供评价页显示下单时间；不能让客户端用当前时间或订单号冒充。
        data.put("createdAt", com.fandazi.core.time.BusinessTime.utc(order.getCreatedAt()));
        // 图片来自订单所购套餐；缺图保留 null 供 Android 使用统一占位。
        data.put("imageUrl", order.getDeal().getImageUrl()); data.put("imageAlt", order.getDeal().getImageAlt());
        // 评价入口由服务端按核销事实与订单唯一评价记录计算，客户端不根据展示状态猜测资格。
        data.put("canReview", order.getVouchers().stream().anyMatch(voucher -> voucher.getStatus() == VoucherStatus.REDEEMED && voucher.getRedeemedAt() != null)
                && !reviews.existsByOrderId(order.getId()));
        // 列表卡需要完整的真实门店与套餐字段；只排除二维码令牌，不能让 Android 猜测或写死展示数据。
        data.put("vouchers", order.getVouchers().stream().map(voucher ->
                com.fandazi.feature.voucher.VoucherSummary.from(voucher, refunds.canRefund(voucher))).toList());
        return data;
    }
}
