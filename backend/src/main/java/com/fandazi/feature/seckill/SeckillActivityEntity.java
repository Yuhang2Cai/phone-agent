// 秒杀活动领域：约束专属套餐与UTC抢购窗口，供目录、资格及订单事务读取。
package com.fandazi.feature.seckill;

import com.fandazi.feature.catalog.DealPackageEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 秒杀活动的持久化定义。每个活动独占一个套餐，剩余库存仍由套餐inventory唯一保存。
 * 本实体不提供商家管理接口，版本与停止状态仅作为购买链路的可信输入。
 */
@Entity
@Table(name="seckill_activities", uniqueConstraints=@UniqueConstraint(columnNames="deal_id"))
public class SeckillActivityEntity {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="public_id",nullable=false,unique=true,length=80) private String publicId;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="deal_id",nullable=false) private DealPackageEntity deal;
    @Column(name="starts_at",nullable=false) private Instant startsAt;
    @Column(name="ends_at",nullable=false) private Instant endsAt;
    @Column(name="total_stock",nullable=false) private int totalStock;
    @Column(name="per_user_limit",nullable=false) private int perUserLimit;
    @Column(nullable=false,length=20) private String status;
    @Version @Column(name="activity_version",nullable=false) private long activityVersion;

    /** 北京时间用于券自然日截止转换；写入/接口仍使用UTC瞬时时间。 */
    private static final ZoneId VOUCHER_ZONE = ZoneId.of("Asia/Shanghai");

    /** 仅供JPA恢复持久状态；领域创建必须使用完整校验构造器。 */
    protected SeckillActivityEntity() {}

    /**
     * 建立活动定义，不保存数据库或扣库存。
     * @param publicId 公开活动标识，1至80个Unicode字符
     * @param deal 活动专属套餐，包含合法价格、初始库存、规则与使用日期
     * @param startsAt UTC开抢时刻，包含边界
     * @param endsAt UTC停止新购时刻，不包含边界
     * @throws IllegalArgumentException 字段、金额、库存、限购或窗口不合法
     */
    public SeckillActivityEntity(String publicId, DealPackageEntity deal, Instant startsAt, Instant endsAt) {
        // 输入校验：先验证所有依赖字段，避免构造中途失败后暴露半初始化活动。
        requireText(publicId,80,"活动标识");
        if(deal==null || deal.getStore()==null) throw new IllegalArgumentException("活动必须关联真实套餐和门店");
        requireText(deal.getTitle(),40,"券名称");
        requireText(deal.getDescription(),1000,"券描述");
        requireText(deal.getUsageRules(),1000,"使用规则");
        if(deal.getPriceCents()<=0 || deal.getOriginalPriceCents()<deal.getPriceCents()
                || deal.getOriginalPriceCents()>99999999) throw new IllegalArgumentException("秒杀价格无效");
        if(deal.getInventory()<1 || deal.getInventory()>100000) throw new IllegalArgumentException("活动总库存无效");
        if(deal.getPurchaseLimit()<1 || deal.getPurchaseLimit()>Math.min(deal.getInventory(),99))
            throw new IllegalArgumentException("每人限购无效");
        if(startsAt==null || endsAt==null || !startsAt.isBefore(endsAt))
            throw new IllegalArgumentException("活动结束必须晚于开始");
        if(deal.getValidFrom()==null || deal.getValidTo()==null || deal.getValidFrom().isAfter(deal.getValidTo()))
            throw new IllegalArgumentException("券使用日期无效");
        // 规则判断：最晚建单仍保留300秒支付期限，且截止必须严格早于券到期，不能生成付款即失效权益。
        Instant voucherEnd=deal.getValidTo().plusDays(1).atStartOfDay(VOUCHER_ZONE).toInstant();
        if(!endsAt.isBefore(voucherEnd.minusSeconds(300)))
            throw new IllegalArgumentException("活动支付期限不能到达券使用截止");
        // 结果构造：初始库存与限购只保存定义快照，不建立第二份可写剩余库存。
        this.publicId=publicId.strip();this.deal=deal;this.startsAt=startsAt;this.endsAt=endsAt;
        this.totalStock=deal.getInventory();this.perUserLimit=deal.getPurchaseLimit();
        this.status="PUBLISHED";this.activityVersion=1;
    }

    /** 校验去首尾空白后的Unicode字符数；不按UTF-16单元误拒绝合法补充平面文字。 */
    private static void requireText(String value,int maximum,String field) {
        if(value==null || value.isBlank()) throw new IllegalArgumentException(field+"不能为空");
        String normalized=value.strip();
        if(normalized.codePointCount(0,normalized.length())>maximum)
            throw new IllegalArgumentException(field+"过长");
    }

    /**
     * 输入服务端权威时刻，返回活动时间投影；不使用手机时间，也不以库存0永久结束活动。
     * 未知持久状态按停止处理，避免异常数据开放购买；null时刻拒绝。
     */
    public String stateAt(Instant now) {
        if(now==null) throw new IllegalArgumentException("缺少权威时间");
        // 停止优先于时间；服务端外部停止后，库存释放也不能让活动重新开抢。
        if(!"PUBLISHED".equals(status)) return "STOPPED";
        if(now.isBefore(startsAt)) return "UPCOMING";
        if(!now.isBefore(endsAt)) return "ENDED";
        return "LIVE";
    }

    /** 返回内部活动主键，仅供持久化关联；不得作为公开资源标识。 */
    public Long getId(){return id;}
    /** 返回公开活动标识；只读且无副作用。 */
    public String getPublicId(){return publicId;}
    /** 返回专属套餐；调用者须处于有效持久化上下文以读取懒关联。 */
    public DealPackageEntity getDeal(){return deal;}
    /** 返回UTC开抢时刻，边界包含。 */
    public Instant getStartsAt(){return startsAt;}
    /** 返回UTC结束时刻，边界不包含。 */
    public Instant getEndsAt(){return endsAt;}
    /** 返回初始总库存，不能将其作为当前剩余库存。 */
    public int getTotalStock(){return totalStock;}
    /** 返回活动期内每个用户累计占用与购买总数上限。 */
    public int getPerUserLimit(){return perUserLimit;}
    /** 返回活动定义版本，供报价和确认防止使用过期定义。 */
    public long getActivityVersion(){return activityVersion;}
}
