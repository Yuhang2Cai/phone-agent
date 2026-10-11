// 可恢复消息记录：隔离记录故障与业务结果，保存用户输入和每次输入的助手终态，不重新执行模型或工具。
package com.fandazi.feature.agent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** 网关同步记录服务；故障会话显式不可完整恢复，不能把部分历史误称完整。 */
@Service
public class AgentHistoryRecordingService {
    private static final Logger LOG=LoggerFactory.getLogger(AgentHistoryRecordingService.class);
    private final AgentMessageWriter writer;
    private final Set<OwnerSession> unavailable=ConcurrentHashMap.newKeySet();

    /** 注入独立事务写入器；服务本身不持有业务事务，也不保存正文的磁盘副本。 */
    public AgentHistoryRecordingService(AgentMessageWriter writer) { this.writer=writer; }

    /** 保存已校验输入；失败只记录可定位链路和异常类型，禁止在日志输出正文或凭据。 */
    public void recordUser(Long userId,String sessionId,String runId,String traceId,String content,String clientMessageKey) {
        record(userId,sessionId,runId,traceId,"user",content,clientMessageKey);
    }

    /** 返回实际历史消息 ID 供来源关联；失败为 null，不能把记录请求当作成功证据。 */
    public String recordUserSource(Long userId,String sessionId,String runId,String traceId,String content,String clientMessageKey) {
        return record(userId,sessionId,runId,traceId,"user",content,clientMessageKey);
    }

    /** 用户原文与已签发来源在同一事务关联；返回值只在提交成功后可用于公开historyRecorded。 */
    public String recordUserLinkedSource(Long userId,String sessionId,String runId,String traceId,String content,String clientMessageKey,String sourceId) {
        return record(userId,sessionId,runId,traceId,"user",content,clientMessageKey,sourceId);
    }

    /** 保存本次输入的完整助手结果；重复终态由数据库唯一键防重，不触发任何业务重跑。 */
    public void recordAssistant(Long userId,String sessionId,String runId,String traceId,String content) {
        record(userId,sessionId,runId,traceId,"assistant",content,null);
    }

    /** 合并内存与持久缺口事实；重建仍拒绝不完整恢复，旧表空缺另由历史DTO真实声明。 */
    public boolean isAvailable(Long userId,String sessionId) { return !unavailable.contains(new OwnerSession(userId,sessionId)) && !writer.hasPersistentFault(userId,sessionId); }

    /** 事务提交异常在此边界捕获；保留故障标记直到人工修复，后续成功不能掩盖之前缺失。 */
    private String record(Long userId,String sessionId,String runId,String traceId,String role,String content,String clientMessageKey) {
        return record(userId,sessionId,runId,traceId,role,content,clientMessageKey,null);
    }

    /** 共用记录与故障处理；2.1可合并来源关联，2.0和助手终态继续使用原消息事务。 */
    private String record(Long userId,String sessionId,String runId,String traceId,String role,String content,String clientMessageKey,String sourceId) {
        try { return sourceId==null ? writer.write(userId,sessionId,runId,traceId,role,content,clientMessageKey).getPublicId()
            : writer.writeLinkedSource(userId,sessionId,runId,traceId,content,clientMessageKey,sourceId).getPublicId(); }
        catch(RuntimeException error) {
            // 两次终态并发查重可能同时未命中；唯一索引先提交的一条已完整可读时，后者是正常重复。
            if(error instanceof org.springframework.dao.DataIntegrityViolationException) {
                try {
                    var existing=writer.existing(userId,sessionId,traceId,role,clientMessageKey);
                    // 唯一约束冲突后也核对精确正文，不能把不同内容当重复成功。
                    if(existing.isPresent() && existing.get().getContent().equals(content)) {
                        // 唯一键竞争的首条消息确已提交后，仍必须在新事务完成来源关联，不能只返回旧ID伪装关联成功。
                        return sourceId==null ? existing.get().getPublicId()
                            : writer.writeLinkedSource(userId,sessionId,runId,traceId,content,clientMessageKey,sourceId).getPublicId();
                    }
                }
                catch(RuntimeException ignored) { /* 读取也故障时继续标记恢复不可用，不推测已提交。 */ }
            }
            unavailable.add(new OwnerSession(userId,sessionId));
            // 数据库仍可用时将缺口事实独立提交，重启继续报告503；数据库整体不可用则先保留内存标记。
            try { writer.markUnavailable(userId,sessionId); }
            catch(RuntimeException markerError) {
                LOG.warn("历史缺口标记写入失败：session={} errorType={}",sessionId,markerError.getClass().getSimpleName());
            }
            LOG.warn("消息恢复记录失败：session={} trace={} role={} errorType={}",sessionId,traceId,role,error.getClass().getSimpleName());
            return null;
        }
    }

    /** 故障归属只保存内部标识，不携带用户正文、JWT或交易凭证。 */
    private record OwnerSession(Long userId,String sessionId) {}
}
