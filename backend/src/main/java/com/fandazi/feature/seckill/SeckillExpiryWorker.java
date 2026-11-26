// 秒杀过期扫描入口：有界读取候选，逐订单事务结算，支持维护时停止并排空自有写者。
package com.fandazi.feature.seckill;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import com.fandazi.feature.grouporder.GroupBuyOrderRepository;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
/** 扫描门禁覆盖整批实际提交，维护排空不能只看瞬时“无候选”就认定后台已停写。 */
@Component
public class SeckillExpiryWorker {
    private final GroupBuyOrderRepository orders;
    private final SeckillExpiryService expiry;
    private final SeckillTimeSource time;
    private final boolean automatic;
    private final ReentrantReadWriteLock gate=new ReentrantReadWriteLock(true);
    private final AtomicInteger active=new AtomicInteger();
    private volatile boolean accepting=true;
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SeckillExpiryWorker.class);
    /** 注入候选查询、独立事务服务及数据库时钟；测试关闭自动调度但仍可显式验证扫描。 */
    public SeckillExpiryWorker(GroupBuyOrderRepository orders,SeckillExpiryService expiry,SeckillTimeSource time,
                               @Value("${seckill.expiry.enabled:true}") boolean automatic){
        this.orders=orders;this.expiry=expiry;this.time=time;this.automatic=automatic;
    }
    /** 每秒最多扫描100笔；应用恢复即沿持久截止补偿，不按进程启动时间重置订单计时。 */
    // 调度由SeckillExpiryScheduler独占，避免Agent开关关闭时漏扫或同时注册两个定时入口。
    public void tick(){if(automatic)scanOnce();}
    /** 扫描最多100个候选并返回实际关闭数。 */
    public int scanOnce(){
        if(!accepting)return 0;
        var lease=gate.readLock();lease.lock();
        try{
            // 再检查一次门禁，避免取得锁之前进入维护的扫描仍开始写入。
            if(!accepting)return 0;
            active.incrementAndGet();
            try{
                int closed=0;
                for(String id:orders.expiredSeckillIds(time.now(),PageRequest.of(0,100))){
                    try{if(expiry.expire(id))closed++;}
                    catch(RuntimeException failure){
                        // 某笔事务失败完整回滚，其余候选继续；只记录可定位订单号和异常类型，不输出SQL或请求正文。
                        LOG.error("秒杀到期结算失败，订单={}，异常类型={}",id,failure.getClass().getSimpleName());
                    }
                }
                return closed;
            }finally{active.decrementAndGet();}
        }finally{lease.unlock();}
    }
    /** 停止新扫描并等待本进程写者排空，超时返回false。 */
    public synchronized boolean pauseAndDrain(long timeoutMillis){
        if(timeoutMillis<0||timeoutMillis>120000)throw new IllegalArgumentException("排空时限必须在0到120000毫秒内");
        accepting=false;
        try{
            if(!gate.writeLock().tryLock(timeoutMillis,TimeUnit.MILLISECONDS))return false;
            try{return active.get()==0;}finally{gate.writeLock().unlock();}
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
    }
    /** 受控退出维护后恢复扫描，不改变既有订单支付期限。 */
    public synchronized void resume(){
        gate.writeLock().lock();try{accepting=true;}finally{gate.writeLock().unlock();}
    }
    /** 返回本实例真实后台写者状态，供受信维护适配器记录；无普通用户管理HTTP入口。 */
    public Map<String,Object> snapshot(){
        return Map.of("automatic",automatic,"accepting",accepting,"activeScans",active.get(),
                "backgroundWriters",accepting||active.get()>0?1:0);
    }
    /** Spring正常关闭时最多等5秒；超时仅报失败，不能伪称已经排空或终止其他任务进程。 */
    @jakarta.annotation.PreDestroy
    public void stop(){if(!pauseAndDrain(5000))LOG.error("秒杀后台写者关闭超时，停写证明无效");}
}
