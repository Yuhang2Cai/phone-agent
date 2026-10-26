package com.fandazi.feature.agent;

import java.util.*;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * AG07单进程入口保护：普通1、控制2，账号突发2且滑动60秒最多6次，无等待队列。
 * 调用方必须先只读校验身份与目标；许可不替代业务鉴权、确认或数据库幂等。
 */
@Component
public final class AgentRequestAdmission {
    private final boolean enabled;
    private final LongSupplier clock;
    private final int maxAccounts;
    private final Map<String,Account> accounts=new HashMap<>();
    private int ordinary,control;
    private boolean maintenance;

    /** 仅部署环境启用；默认关闭，不将本机保护值宣传成可承诺吞吐量。 */
    @Autowired public AgentRequestAdmission(Environment environment) {
        this(parse(environment.getProperty("AG07_ADMISSION_ENABLED","false")),()->System.nanoTime()/1_000_000,4096);
    }
    /** 显式装配时提供单调时钟，非法账户容量在服务启动前拒绝。 */
    public AgentRequestAdmission(boolean enabled,LongSupplier clock,int maxAccounts) {
        if(clock==null||maxAccounts<1)throw new IllegalArgumentException("准入配置无效");
        this.enabled=enabled;this.clock=clock;this.maxAccounts=maxAccounts;
    }
    /** 严格开关避免拼写错误导致保护被静默关闭。 */
    private static boolean parse(String value) {
        if(!Set.of("true","false").contains(value))throw new IllegalArgumentException("准入开关无效");
        return "true".equals(value);
    }
    /** 旧手工装配保持未开启；生产由同一应用Bean注入共享，不能每请求新建桶。 */
    public static AgentRequestAdmission disabled() {return new AgentRequestAdmission(false,()->System.nanoTime()/1_000_000,4096);}
    /** 调用方据此决定是否执行新增只读前置，旧受控配置不被隐式开启。 */
    public boolean enabled() {return enabled;}

    /** 原子核容量再扣速率；容量拒绝不偷扣另一账户的额度，控制不扣普通桶。 */
    public synchronized Lease acquire(String account,boolean management) {
        if(account==null||account.isBlank()||account.length()>256)throw new IllegalArgumentException("准入账号无效");
        if(enabled) {
            if(!management&&maintenance)throw denied(503,1,"maintenance");
            if((management?control:ordinary)>=(management?2:1))throw denied(503,1,"capacity");
            if(!management)debit(account);
        }
        if(management)control++;else ordinary++;
        return new Lease(this,management);
    }
    /** 锁内同时检查突发桶和一分钟滑窗，仅过期完全恢复账号可删除。 */
    private void debit(String key) {
        long now=clock.getAsLong();
        accounts.entrySet().removeIf(entry->now-entry.getValue().last>=60000);
        Account row=accounts.get(key);
        if(row==null) {
            if(accounts.size()>=maxAccounts)throw denied(503,1,"account_capacity");
            row=new Account(now);
        }
        if(now<row.last)throw new IllegalStateException("准入时钟回退");
        double tokens=Math.min(2,row.tokens+(now-row.last)/10000.0);
        var history=new ArrayDeque<Long>();for(long t:row.times)if(now-t<60000)history.add(t);
        double wait=Math.max((1-tokens)*10000,history.size()>=6?60000-(now-history.peek()):0);
        if(wait>0)throw denied(429,(int)Math.ceil(wait/1000),"account_rate");
        history.add(now);row.last=now;row.tokens=tokens-1;row.times=history;accounts.put(key,row);
    }
    /** 仅许可最后关闭调用，不能因为重复finally减成负数。 */
    private synchronized void release(boolean management) {if(management)control--;else ordinary--;}
    /** 记录真实拒绝用于告警计数；观测异常不改变拒绝决定，不包含账户或正文。 */
    private Denied denied(int status,int retryAfter,String reason) {
        var runtime=AgentOperationsRuntime.current();
        if(runtime!=null) {
            try {runtime.emit("admission",Map.of("status","denied","errorCode",status==429?"RATE_LIMITED":"DEPENDENCY_UNAVAILABLE"));}
            catch(RuntimeException ignored) { /* 观测失败不能放行原被拒绝请求。 */ }
        }
        return new Denied(status,retryAfter,reason);
    }
    /** 本机维护只拒绝新普通输入，不打断已有工作和原键核查。 */
    public synchronized void setMaintenance(boolean value) {maintenance=value;}
    /** 返回不含账户身份的计数；不能据瞬间归零推断分布式资源已清空。 */
    public synchronized Map<String,Object> snapshot() {return Map.of("enabled",enabled,"ordinary",ordinary,"control",control,"maintenance",maintenance,"accounts",accounts.size());}

    /** 小型账号窗口只保留时刻，不保留消息、会话或业务资源。 */
    private static final class Account {
        long last;double tokens=2;ArrayDeque<Long> times=new ArrayDeque<>();
        Account(long now) {last=now;}
    }
    /** 同步Servlet执行与HTTP调用真实返回后关闭，取消外层等待不能提前调用close。 */
    public static final class Lease implements AutoCloseable {
        private final AgentRequestAdmission owner;private final boolean management;private boolean closed;
        private Lease(AgentRequestAdmission owner,boolean management) {this.owner=owner;this.management=management;}
        /** 幂等关闭，无可见业务副作用，不抛受检异常。 */
        @Override public synchronized void close() {if(!closed){closed=true;owner.release(management);}}
    }
    /** 仅固定原因、HTTP状态和重试秒数；公开信封不带用户内容或原请求身份。 */
    public static final class Denied extends RuntimeException {
        private final int status,retryAfter;
        public Denied(int status,int retryAfter,String reason) {super(reason);this.status=status;this.retryAfter=Math.max(1,retryAfter);}
        /** 429为账号速率；503为容量/维护，均表示新请求未受理。 */
        public int status() {return status;}
        /** 最短再次尝试时间，不代表客户端应自动重发交易。 */
        public int retryAfter() {return retryAfter;}
    }
}
