// 投诉服务：校验可选订单归属和一次性确认后持久化工单。
package com.fandazi.feature.support;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.agent.ConfirmationGrantService;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.grouporder.GroupBuyOrderRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 客服投诉允许不关联订单，但指定订单时必须是当前用户的真实团购订单。 */
@Service
public class ComplaintService {
    private final ComplaintTicketRepository tickets;
    private final GroupBuyOrderRepository orders;
    private final UserRepository users;
    private final ConfirmationGrantService grants;

    /** 注入工单、订单、用户和凭证依赖。 */
    public ComplaintService(ComplaintTicketRepository tickets, GroupBuyOrderRepository orders,
                            UserRepository users, ConfirmationGrantService grants) {
        this.tickets = tickets; this.orders = orders; this.users = users; this.grants = grants;
    }

    /** 签发只适用于该订单的短时投诉确认凭证。 */
    @Transactional
    public Map<String, Object> prepare(Long userId, String sessionId, String orderId) {
        ownedOrderId(userId, orderId);
        String resource = resourceId(orderId);
        String grant = grants.issueForAction(userId, sessionId, "SUBMIT_COMPLAINT", resource, 0, "complaint-v1").getGrantId();
        return Map.of("orderId", orderId == null ? "" : orderId, "confirmationGrant", grant);
    }

    /** 重查归属并消费动作专属凭证，随后仅记录 `OPEN` 工单。 */
    @Transactional
    public Map<String, Object> submit(Long userId, String sessionId, String orderId, String subject,
                                      String description, String grantId) {
        if (subject == null || subject.isBlank() || subject.length() > 255
                || description == null || description.isBlank() || description.length() > 2000) {
            throw new DomainException("投诉内容长度不合法");
        }
        Long internalOrderId = ownedOrderId(userId, orderId);
        // 投诉无支付金额，但仍要求零金额和规则版本匹配，不能接受同资源的其他业务凭证。
        grants.consumeBound(userId, sessionId, grantId, "SUBMIT_COMPLAINT", resourceId(orderId), 0, "complaint-v1");
        var user = users.findById(userId).orElseThrow(() -> new DomainException("用户不存在"));
        var ticket = tickets.save(new ComplaintTicketEntity(UUID.randomUUID().toString(), user, internalOrderId,
                null, subject.trim(), description.trim()));
        // 工具结果会写入 JSON 审计列，时间必须先转成稳定字符串，不能直接放 Java 时间对象。
        return Map.of("ticketId", ticket.getPublicId(), "status", ticket.getStatus().name(),
                "subject", ticket.getSubject(), "createdAt", ticket.getCreatedAt().toString());
    }

    /** 可选订单一旦出现就必须验证归属，不能按客户端自报主键保存。 */
    private Long ownedOrderId(Long userId, String orderId) {
        if (orderId == null || orderId.isBlank()) return null;
        return orders.findByUserIdAndPublicId(userId, orderId)
                .orElseThrow(() -> new DomainException("团购订单不存在")).getId();
    }

    /** 未关联订单时绑定统一资源字面值，防止空值破坏确认凭证约束。 */
    private String resourceId(String orderId) { return orderId == null || orderId.isBlank() ? "general" : orderId; }
}
