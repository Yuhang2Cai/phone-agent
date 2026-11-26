// 秒杀目录：公开预告、详情及可信时间，不在浏览期间创建报价、订单或库存占用。
package com.fandazi.feature.seckill;
import com.fandazi.feature.agent.ToolBoundaryException;
import com.fandazi.feature.catalog.DealPackageResponse;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 只投影后端事实；个人额度由独立的认证资格入口提供。 */
@Service
public class SeckillCatalogService {
    private final SeckillActivityRepository activities;
    private final SeckillTimeSource time;
    /** 注入活动查询与数据库权威时钟，不依赖客户端库存或时间。 */
    public SeckillCatalogService(SeckillActivityRepository activities,SeckillTimeSource time){this.activities=activities;this.time=time;}

    /** 返回门店未结束活动；预告和售罄可见，未知门店自然返回空列表。 */
    @Transactional(readOnly=true)
    public List<ActivityView> list(String storeId){
        Instant now=time.now();
        // 不能复用普通团购按使用日期/库存筛选，否则预告和超时释放后的活动会消失。
        return activities.findByDealStorePublicIdOrderByStartsAtAsc(storeId).stream()
                .filter(a->a.getDeal().isActive() && ("UPCOMING".equals(a.stateAt(now)) || "LIVE".equals(a.stateAt(now))))
                .map(a->view(a,now)).toList();
    }

    /** 返回已结束/停止活动的详情供订单返回查看；不存在时给出稳定NOT_FOUND。 */
    @Transactional(readOnly=true)
    public ActivityView detail(String activityId){
        var activity=activities.findByPublicId(activityId).orElseThrow(()->new ToolBoundaryException("NOT_FOUND","秒杀活动不存在"));
        return view(activity,time.now());
    }

    /** 在只读事务内读取真实套餐说明和关联门店；库存0不改变活动的时间投影。 */
    private ActivityView view(SeckillActivityEntity a,Instant now){
        return new ActivityView(a.getPublicId(),a.getDeal().getPublicId(),a.stateAt(now),a.getActivityVersion(),
                now,a.getStartsAt(),a.getEndsAt(),a.getDeal().getInventory(),a.getTotalStock(),a.getPerUserLimit(),DealPackageResponse.from(a.getDeal()));
    }

    /** 公开活动事实；Instant序列化为UTC，未包含其他用户订单或额度。 */
    public record ActivityView(String activityId,String dealId,String state,long activityVersion,Instant serverTime,
            Instant startsAt,Instant endsAt,int availableStock,int totalStock,int perUserLimit,DealPackageResponse deal){}
}
