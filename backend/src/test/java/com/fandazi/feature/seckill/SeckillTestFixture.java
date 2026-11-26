// 秒杀测试数据：仅测试源码可见，在调用方隔离事务中建立真实门店、套餐和活动。
package com.fandazi.feature.seckill;

import com.fandazi.feature.catalog.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.UUID;

/** 合成夹具不随生产JAR打包，不开放HTTP发布入口，不向客户端提供静态兜底。 */
final class SeckillTestFixture {
    /** 返回已持久化活动；调用者必须使用事务，非法定义导致外层事务回滚所有关联行。 */
    static SeckillActivityEntity create(EntityManager em, Instant startsAt, Instant endsAt, int stock, int limit) {
        String suffix=UUID.randomUUID().toString();
        var store=new StoreEntity("sk-store-"+suffix,"测试品牌","测试小馆","test","测试地址","000","中餐",
                BigDecimal.ZERO,BigDecimal.ZERO,1000,true,"全天",null);
        em.persist(store);
        var deal=new DealPackageEntity("sk-deal-"+suffix,store,"双人午市套餐","主菜和双人饮品",3990,7900,stock,
                LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1),LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(30),true,limit,1);
        deal.replaceCompareFacts(2,null,"午市堂食");
        em.persist(deal);
        // 刻意在关联写入后构造活动，非法库存等异常可以证明整段事务不会遗留半成品。
        var activity=new SeckillActivityEntity("sk-activity-"+suffix,deal,startsAt,endsAt);
        em.persist(activity);
        em.flush();
        return activity;
    }
}
