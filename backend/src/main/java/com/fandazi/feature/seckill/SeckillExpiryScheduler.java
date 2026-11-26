// 秒杀独立调度：由Spring生命周期拥有唯一后台线程，不依赖Agent功能开关或其远端请求线程。
package com.fandazi.feature.seckill;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 固定每秒触发领域扫描；异常只终止本轮，下一轮仍按持久截止核查，不重置订单期限。 */
@Component
public final class SeckillExpiryScheduler {
    /** 受信维护适配器登记的稳定写者名称；多进程还须结合各自PID/发布身份登记。 */
    public static final String WRITER_ID="seckill-expiry";
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(SeckillExpiryScheduler.class);
    private final SeckillExpiryWorker worker;
    private final boolean enabled;
    private ScheduledExecutorService executor;
    private ScheduledFuture<?> future;

    /** 构造只保存依赖；关闭配置不创建线程，手工领域测试仍能显式调用worker。 */
    public SeckillExpiryScheduler(SeckillExpiryWorker worker,@Value("${seckill.expiry.enabled:true}") boolean enabled){
        this.worker=worker;this.enabled=enabled;
    }
    /** 应用初始化后创建唯一任务；定向测试不得通过手工调用扫描冒充本入口实际执行。 */
    @PostConstruct public synchronized void start(){
        if(!enabled||executor!=null)return;
        executor=Executors.newSingleThreadScheduledExecutor(task->{
            var thread=new Thread(task,"seckill-expiry-scheduler");thread.setDaemon(true);return thread;
        });
        scheduleRecovery();
    }
    /** 启动或恢复先立即补偿，再按一秒间隔扫描；调用者持有调度锁，且始终共用唯一执行线程。 */
    private void scheduleRecovery(){
        future=executor.scheduleWithFixedDelay(()->{
            try{worker.tick();}
            catch(RuntimeException failure){
                // 候选查询失败不能让ScheduledExecutor永久取消后续扫描；不输出SQL或连接秘密。
                LOG.error("秒杀后台扫描失败，保留后续补偿，异常类型={}",failure.getClass().getSimpleName());
            }
        },0,1,TimeUnit.SECONDS);
    }
    /** 受信维护入口：先禁止领域写入并等待已进入事务的扫描排空，失败保持关闭。 */
    public boolean pauseAndDrain(long timeoutMillis){return worker.pauseAndDrain(timeoutMillis);}
    /** 仅受信维护流程退出时调用；不会创建新订单或延长原支付截止。 */
    public synchronized void resume(){
        worker.resume();
        if(enabled&&executor!=null&&!executor.isShutdown()){
            // 取消尚未触发的旧节拍，不中断当前事务；单线程保证当前扫描完成后才执行新的即时补偿。
            if(future!=null)future.cancel(false);
            scheduleRecovery();
        }
    }
    /** 登记实际后台调度及领域写者状态，不能用健康接口200代替停写证明。 */
    public synchronized Map<String,Object> snapshot(){
        return Map.of("writerId",WRITER_ID,"enabled",enabled,"scheduled",future!=null&&!future.isCancelled(),
                "terminated",executor==null||executor.isTerminated(),"worker",worker.snapshot());
    }
    /** 正常关闭只撤销未来触发，等待已进入领域事务完成；不强杀线程或其他任务进程。 */
    @PreDestroy public synchronized void stop(){
        if(future!=null)future.cancel(false);
        if(executor!=null)executor.shutdown();
        if(!worker.pauseAndDrain(5000))LOG.error("秒杀调度停写排空超时，禁止据此签发备份凭证");
    }
}
