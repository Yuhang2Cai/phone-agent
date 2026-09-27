// 订单模块：定义持久化数据结构。
package com.fandazi.feature.order;

import com.fandazi.feature.DomainException;
import com.fandazi.feature.auth.UserEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "food_orders", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "idempotency_key"}))
public class OrderEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(optional = false) private UserEntity user;
    @Column(nullable = false) private String idempotencyKey;
    @Column(nullable = false) private String requestFingerprint;
    @Column private String paymentIdempotencyKey;
    @Column(nullable = false) private String merchantName;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private String addressRecipient;
    @Column(nullable = false) private String addressPhone;
    @Column(nullable = false) private String addressDetail;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal subtotal;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal deliveryFee;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal discount;
    @Column(nullable = false, precision = 12, scale = 2) private BigDecimal total;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private OrderStatus status;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<OrderItemEntity> items = new ArrayList<>();
    @ElementCollection
    @CollectionTable(name = "order_timeline", joinColumns = @JoinColumn(name = "order_id"))
    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private List<OrderStatus> timeline = new ArrayList<>();

// 初始化对象依赖与默认状态。
    protected OrderEntity() {}

// 初始化对象依赖与默认状态。
    public OrderEntity(UserEntity user, String key, String fingerprint, String merchantName,
                       String recipient, String phone, String detail, BigDecimal subtotal,
                       BigDecimal fee, BigDecimal discount, BigDecimal total) {
        this.user = user;
        this.idempotencyKey = key;
        this.requestFingerprint = fingerprint;
        this.merchantName = merchantName;
        this.createdAt = LocalDateTime.now();
        this.addressRecipient = recipient;
        this.addressPhone = phone;
        this.addressDetail = detail;
        this.subtotal = subtotal;
        this.deliveryFee = fee;
        this.discount = discount;
        this.total = total;
        this.status = OrderStatus.PENDING_PAYMENT;
        timeline.add(status);
    }

// 获取id。
    public Long getId() { return id; }
// 获取status。
    public OrderStatus getStatus() { return status; }
// 获取subtotal。
    public BigDecimal getSubtotal() { return subtotal; }
// 获取delivery、fee。
    public BigDecimal getDeliveryFee() { return deliveryFee; }
// 获取discount。
    public BigDecimal getDiscount() { return discount; }
// 获取total。
    public BigDecimal getTotal() { return total; }
// 获取商家、name。
    public String getMerchantName() { return merchantName; }
// 获取created、at。
    public LocalDateTime getCreatedAt() { return createdAt; }
// 获取地址、recipient。
    public String getAddressRecipient() { return addressRecipient; }
// 获取地址、phone。
    public String getAddressPhone() { return addressPhone; }
// 获取地址、detail。
    public String getAddressDetail() { return addressDetail; }
// 获取items。
    public List<OrderItemEntity> getItems() { return items; }
// 获取timeline。
    public List<OrderStatus> getTimeline() { return List.copyOf(timeline); }
// 获取用户。
    public UserEntity getUser() { return user; }
// 获取请求、fingerprint。
    public String getRequestFingerprint() { return requestFingerprint; }
// 获取支付、幂等、键。
    public String getPaymentIdempotencyKey() { return paymentIdempotencyKey; }
// 处理 `setPaymentIdempotencyKey` 相关逻辑。
    public void setPaymentIdempotencyKey(String key) { paymentIdempotencyKey = key; }
// 处理 `addItem` 相关逻辑。
    public void addItem(OrderItemEntity item) { items.add(item); }

// 处理 `advance` 相关逻辑。
    public void advance(OrderStatus next) {
        if (!isAdjacent(status, next)) throw new DomainException("订单状态不可逆或跳跃");
        status = next;
        timeline.add(next);
    }

// 判断adjacent。
    private boolean isAdjacent(OrderStatus current, OrderStatus next) {
        return (current == OrderStatus.PENDING_PAYMENT && next == OrderStatus.PAID)
                || (current == OrderStatus.PAID && next == OrderStatus.MERCHANT_ACCEPTED)
                || (current == OrderStatus.MERCHANT_ACCEPTED && next == OrderStatus.PREPARING)
                || (current == OrderStatus.PREPARING && next == OrderStatus.RIDER_PICKED_UP)
                || (current == OrderStatus.RIDER_PICKED_UP && next == OrderStatus.DELIVERING)
                || (current == OrderStatus.DELIVERING && next == OrderStatus.DELIVERED);
    }
}
