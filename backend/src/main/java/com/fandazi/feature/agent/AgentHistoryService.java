// 私有会话历史恢复：校验所有权→固定双上界→有界分页→公开脱敏，只读执行而不恢复旧授权。
package com.fandazi.feature.agent;

import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 稳定快照依赖消息与事件ID读取上界；默认每页50，最大100项合计，不一次性读取无限历史。 */
@Service
public class AgentHistoryService {
    private final AgentSessionService sessions;
    private final AgentMessageRepository messages;
    private final AgentEventRepository events;
    private final AgentHistoryCursor cursors;
    private final AgentHistoryRecordingService recording;

    /** 注入既有归属、公开事件及独立记录服务；不依赖模型、交易工具或私人缓存。 */
    public AgentHistoryService(AgentSessionService sessions,AgentMessageRepository messages,AgentEventRepository events,AgentHistoryCursor cursors,AgentHistoryRecordingService recording) {
        this.sessions=sessions;this.messages=messages;this.events=events;this.cursors=cursors;this.recording=recording;
    }

    /**
     * 返回V1有界页；首次在同一可重复读事务固定两组上界，后续只沿签名游标前进。
     * 未授权会话404、非法输入400、已观察到记录缺口503；均不假造历史或重发业务。
     */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public AgentHistoryResponse page(Long ownerId,String sessionId,String cursor,int limit) {
        if(limit<1 || limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"历史limit必须为1至100");
        final AgentSession session;
        try { session=sessions.owned(ownerId,sessionId); }
        catch(com.fandazi.feature.DomainException error) { throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在"); }
        if(!recording.isAvailable(ownerId,sessionId))throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"历史记录暂时无法完整恢复");
        var snapshot=cursor==null ? new AgentHistoryCursor.Snapshot(ownerId,sessionId,messages.maximumId(ownerId,sessionId),events.maximumId(ownerId,sessionId),0,0)
                : cursors.decode(cursor,ownerId,sessionId);
        // 每次最多读取limit+1以判断续页；消息先按ID返回，再按ID返回事件，Android完整收齐后按trace归属组装。
        var messageRows=messages.snapshotPage(ownerId,sessionId,snapshot.messageAfter(),snapshot.messageUpper(),PageRequest.of(0,limit+1));
        boolean moreMessages=messageRows.size()>limit;
        var acceptedMessages=messageRows.stream().limit(limit).toList();
        int remaining=limit-acceptedMessages.size();
        var eventRows=moreMessages ? List.<AgentEventEntity>of()
                : events.snapshotPage(ownerId,sessionId,snapshot.eventAfter(),snapshot.eventUpper(),PageRequest.of(0,remaining+1));
        var acceptedEvents=eventRows.stream().limit(remaining).toList();
        boolean more=moreMessages || eventRows.size()>remaining;
        long messageAfter=acceptedMessages.isEmpty()?snapshot.messageAfter():acceptedMessages.get(acceptedMessages.size()-1).getId();
        long eventAfter=acceptedEvents.isEmpty()?snapshot.eventAfter():acceptedEvents.get(acceptedEvents.size()-1).getId();
        String next=more ? cursors.encode(new AgentHistoryCursor.Snapshot(ownerId,sessionId,snapshot.messageUpper(),snapshot.eventUpper(),messageAfter,eventAfter)) : null;
        // 所有历史事件沿用实时公开出口脱敏；当前run只有状态，不携带任何历史确认摘要或自动授权。
        return new AgentHistoryResponse("1.0",sessionId,acceptedMessages.stream().map(AgentHistoryResponse.Message::from).toList(),
                acceptedEvents.stream().map(AgentGatewayController.EventResponse::from).toList(),snapshot.eventUpper(),next,
                snapshot.messageUpper()==0?"EMPTY":"AVAILABLE",
                new AgentHistoryResponse.CurrentRun(session.getActiveRunId(),session.getTraceId(),session.getActiveRunStatus(),true));
    }
}
