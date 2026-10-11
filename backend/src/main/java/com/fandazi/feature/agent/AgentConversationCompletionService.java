// 会话出口短事务：最终卡片重查之后，原子登记展示、绑定待确认摘要并保存任务停点。
package com.fandazi.feature.agent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 只处理已经完成模型和业务调用的可信公开结果，不在事务内调用外部HTTP或创建业务订单。
 * 依赖既有展示服务和会话服务的归属/epoch/run校验；任一步失败都回滚本次出口变更。
 */
@Service
public class AgentConversationCompletionService {
    private final AgentPresentationService presentations;
    private final AgentSessionService sessions;

    /** 注入同库领域服务，复用既有严格校验，不建立绕过对象资格或确认的第二条路径。 */
    public AgentConversationCompletionService(AgentPresentationService presentations,AgentSessionService sessions) {
        this.presentations=presentations;this.sessions=sessions;
    }

    /**
     * 原子保存2.1本轮出口，返回带真实展示元数据的同一公开结果。
     * userId/sessionId/runId/sourceId均由已鉴权网关冻结；result必须已完成资源重查。
     * 归属、来源、重复卡片或旧run确认校验失败抛DomainException并整体回滚。
     */
    @Transactional
    public AgentResult complete(Long userId,String sessionId,String runId,String sourceId,AgentResult result) {
        // 空列表也必须经过原登记逻辑：同来源重试可能使旧列表全部失效，不能直接省略。
        var registered=presentations.register(userId,sessionId,sourceId,result.cards());
        var completed=new AgentResult(result.status(),result.message(),result.nextAction(),result.candidateCart(),
            result.events(),registered,result.error(),result.eventsRecorded());
        // 仅实际等待确认且有合法动作的结果绑定摘要；登记对象不构成确认授权。
        if(completed.status()==AgentResult.Status.AWAITING_CONFIRMATION && completed.candidateCart().containsKey("action"))
            sessions.bindPendingConfirmation(userId,sessionId,runId,completed.candidateCart());
        // 终态与展示/摘要共用本次事务；旧run的迟到普通结果仍由原会话服务防覆盖。
        sessions.finishRun(userId,sessionId,runId,completed.status());
        return completed;
    }
}
