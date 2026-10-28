// 活动准入：限制每个身份的读写频率并限制内存条目，拒绝时不进入业务事务。
package com.fandazi.feature.activity;

import java.util.function.LongSupplier;
import java.util.HashMap;
import org.springframework.stereotype.Component;

/** 单实例本地速率护栏；并发唯一发币仍由数据库保证，不依赖此计数。 */
@Component
public class ActivityAdmission {
    /** 一分钟窗口：每身份读取240次、写入120次；20并发验收仍完整进入数据库唯一性检查。 */
    private static final long WINDOW=60_000_000_000L;
    /** 最多保留一万身份动作组合，超出且无法清理过期项时拒绝新增键，避免无界内存。 */
    private static final int MAX_KEYS=10_000;
    private final LongSupplier now;
    private final HashMap<String,Counter> counters=new HashMap<>();
    /** 计数在 synchronized 准入方法内更新，不允许并发绕过窗口额度。 */
    private record Counter(long start,int used) {}
    /** 生产使用单调时钟，系统校时不延长或跳过限流窗口。 */
    public ActivityAdmission(){this(System::nanoTime);}
    /** 包内测试注入纳秒时钟，精确覆盖窗口边界。 */
    ActivityAdmission(LongSupplier now) {this.now=now;}
    /** 身份键由认证用户或服务端连接地址生成，write 区分读写独立额度。 */
    public synchronized void check(String owner,boolean write) {
        long time=now.getAsLong();String key=owner+":"+write;var counter=counters.get(key);
        if(counter==null&&counters.size()>=MAX_KEYS) {
            counters.entrySet().removeIf(entry->time-entry.getValue().start()>=WINDOW);
            if(counters.size()>=MAX_KEYS)throw new ActivityException(429,"RATE_LIMITED");
        }
        if(counter==null||time-counter.start()>=WINDOW)counter=new Counter(time,0);
        if(counter.used()>=(write?120:240))throw new ActivityException(429,"RATE_LIMITED");
        counters.put(key,new Counter(counter.start(),counter.used()+1));
    }
}
