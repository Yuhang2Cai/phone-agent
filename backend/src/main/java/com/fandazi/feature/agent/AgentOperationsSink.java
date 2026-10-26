package com.fandazi.feature.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 每应用实例一个有界事件接收器；业务线程只做白名单重建与非阻塞入队。
 * 文件写入/轮换/保留期处理仅在自有worker执行，关闭最多5秒且明确披露未写及仍存活线程。
 */
public final class AgentOperationsSink {
    /** 冻结硬边界与目录命名只覆盖自有运维文件，不碰审计或业务资料。 */
    private static final int MAX_RECORD = 8192;
    private static final long RETENTION_MILLIS = TimeUnit.DAYS.toMillis(7);
    private final ObjectMapper json = new ObjectMapper();
    private final Path root;
    private final int maxBytes, maxFiles;
    private final ArrayBlockingQueue<byte[]> queue;
    private final Object lock = new Object();
    private volatile boolean closed;
    private Thread worker;
    private long accepted, written, dropped, errors;
    private String lastFlush;

    /** 指定自有绝对目录及可收紧的容量；构造不创建线程或文件，越过硬限额拒绝。 */
    public AgentOperationsSink(Path root, int capacity, int maxBytes, int maxFiles) {
        if (root == null || !root.isAbsolute() || capacity < 1 || capacity > 256
                || maxBytes < 1 || maxBytes > 20*1024*1024 || maxFiles < 1 || maxFiles > 5)
            throw new IllegalArgumentException("invalid_sink_limits");
        this.root=root.normalize(); this.maxBytes=maxBytes; this.maxFiles=maxFiles;
        this.queue=new ArrayBlockingQueue<>(capacity);
    }

    /** 安全复制事件后立即入队；非法输入、满队列或已关闭返回false，不做磁盘操作。 */
    public boolean offer(Map<String,?> event) {
        byte[] data;
        try {
            Object kind=event.get("eventType");
            if (!(kind instanceof String)) throw new IllegalArgumentException("invalid_event");
            data=(json.writeValueAsString(AgentOperationsEvent.make((String)kind,event))+"\n").getBytes(StandardCharsets.UTF_8);
            if (data.length > Math.min(MAX_RECORD,maxBytes)) throw new IllegalArgumentException("event_too_large");
        } catch (Exception invalid) {
            synchronized(lock) {dropped++;}
            return false;
        }
        synchronized(lock) {
            if (closed || !queue.offer(data)) {dropped++;return false;}
            accepted++;return true;
        }
    }

    /** 显式启动单个守护worker；幂等start不产生额外消费线程，关闭后禁止重启。 */
    public void start() {
        synchronized(lock) {
            if (closed) throw new IllegalStateException("sink_closed");
            if (worker != null) return;
            worker=new Thread(this::run,"ag07-event-sink");
            worker.setDaemon(true);worker.start();
        }
    }

    /** 返回内存状态，未写计数包含写失败和正在写入，不把队列清空当完整落盘。 */
    public Map<String,Object> snapshot() {
        synchronized(lock) {
            Map<String,Object> state=new LinkedHashMap<>();
            state.put("accepted",accepted);state.put("written",written);state.put("dropped",dropped);
            state.put("sink_error",errors);state.put("queued",(long)queue.size());
            state.put("unwritten",accepted-written);state.put("last_flush",lastFlush);
            state.put("closed",closed);state.put("worker_alive",worker != null && worker.isAlive());
            return state;
        }
    }

    /** 停止新接收且最多等待5000ms；零等待不调用无限期join，超时保留真实存活状态。 */
    public Map<String,Object> close(long timeoutMillis) {
        if (timeoutMillis < 0 || timeoutMillis > 5000) throw new IllegalArgumentException("invalid_close_timeout");
        Thread thread;
        synchronized(lock) {closed=true;thread=worker;}
        if (thread != null && thread != Thread.currentThread() && timeoutMillis > 0) {
            try {thread.join(timeoutMillis);}
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
        }
        return snapshot();
    }

    /** 拒绝目标及祖先链接，避免只检查末级目录却通过父目录写入其他任务。 */
    private void prepare() throws IOException {
        for(Path p=root;p != null;p=p.getParent()) rejectLink(p);
        Files.createDirectories(root);
    }

    /** 固定名称限制文件操作范围；root已在构造时规范化为绝对路径。 */
    private Path path(int index) {return root.resolve(index == 0 ? "events.jsonl" : "events."+index+".jsonl");}

    /** Windows重解析点和符号链接均拒绝，失败由worker计数而非冒称正常写入。 */
    private void rejectLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("sink_path_link");
        if (Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            var attrs=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            if (attrs.isOther()) throw new IOException("sink_path_reparse");
        }
    }

    /** 即使没有新事件，心跳也执行7天保留期；只处理本实例固定运维文件。 */
    private void expire() throws IOException {
        prepare();
        long cutoff=System.currentTimeMillis()-RETENTION_MILLIS;
        for(int i=0;i<maxFiles;i++) {
            Path file=path(i);rejectLink(file);
            if(Files.exists(file) && Files.getLastModifiedTime(file).toMillis()<cutoff) Files.delete(file);
        }
    }

    /** 单worker按顺序追加并关句柄，容量包含当前文件；从不删除其他命名文件。 */
    private void write(byte[] data) throws IOException {
        expire();
        Path current=path(0);
        if(Files.exists(current) && Files.size(current)+data.length>maxBytes) {
            Files.deleteIfExists(path(maxFiles-1));
            for(int i=maxFiles-2;i>=0;i--) if(Files.exists(path(i)))
                Files.move(path(i),path(i+1),StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(current,data,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
        synchronized(lock) {written++;lastFlush=Instant.now().toString();}
    }

    /** 原子更新状态，finalizing指worker已停止消费；不伪造线程已经退出。 */
    private void status(boolean finalizing) {
        try {
            expire();
            Path temp=root.resolve("status.tmp"), dest=root.resolve("status.json");
            rejectLink(temp);rejectLink(dest);
            // 本机状态使用固定白名单与未知覆盖标记，不把sink正常推断成业务恢复。
            var state=AgentOperationsHealth.project(snapshot());state.put("heartbeat",Instant.now().toString());
            state.put("consumer_stopped",finalizing);
            Files.writeString(temp,json.writeValueAsString(state),StandardCharsets.UTF_8);
            Files.move(temp,dest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        } catch(Exception unavailable) {
            synchronized(lock) {errors++;}
            System.err.println("AG07_MONITOR_UNAVAILABLE");
        }
    }

    /** 错误只计观测失败，不重试业务；退出前写收尾状态，所有文件按次关闭。 */
    private void run() {
        long lastStatus=0;
        try {
            while(!closed || !queue.isEmpty()) {
                byte[] data=queue.poll(100,TimeUnit.MILLISECONDS);
                if(data != null) {
                    try {write(data);}
                    catch(Exception failure) {synchronized(lock) {errors++;}}
                }
                long now=System.nanoTime();
                if(lastStatus==0 || now-lastStatus>=TimeUnit.SECONDS.toNanos(10)) {
                    status(false);lastStatus=now;
                }
            }
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {status(true);}
    }
}
