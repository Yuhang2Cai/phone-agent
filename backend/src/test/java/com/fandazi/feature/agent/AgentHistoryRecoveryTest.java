// 长会话恢复集成回归：真实Spring/JPA保存合法输入与完整助手结果，外部模型由确定性替身控制。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 网关真实输入链路必须产生消息；空旧表不能用事件反造用户话语。 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AgentHistoryRecoveryTest {
    @Autowired private AgentGatewayService gateway;
    @Autowired private AgentMessageRepository messages;
    @Autowired private AgentSessionService sessions;
    @Autowired private UserRepository users;
    @MockBean private AgentServiceClient client;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private AgentEventService events;
    @org.springframework.boot.test.mock.mockito.SpyBean private AgentMessageWriter writer;
    @Autowired private AgentHistoryRecordingService recording;

    /** 长正文与重复终态测试锁定消息缺口，防止仅新增读取接口却仍没有可恢复数据。 */
    @Test void legalInputAndDuplicateTerminalPersistExactlyTwoFullMessages() {
        UserEntity user=users.save(new UserEntity("history-"+UUID.randomUUID(),"history-"+UUID.randomUUID()+"@example.invalid","unused-test-hash"));
        String session="history-"+UUID.randomUUID();
        String input="合法的完整用户内容".repeat(1500);
        // 指定缺口红测已保留；修复后同时覆盖超过旧消息列与事件摘要列的完整助手正文。
        String answer="完整助手回复".repeat(1800);
        AgentResult.Event terminal=new AgentResult.Event("completed","已结束",null,"succeeded",Map.of("eventId",UUID.randomUUID().toString()));
        when(client.run(any())).thenReturn(AgentResult.completed(answer,"completed",List.of(terminal,terminal)));
        gateway.handle(user.getId(),session,input,AgentMode.CONFIRM,"display-key-1");
        var stored=messages.findByUserIdAndSessionIdOrderByCreatedAtAsc(user.getId(),session);
        assertThat(stored).as("合法输入和助手终态必须持久化，重复终态不得增加消息").hasSize(2);
        assertThat(stored.get(0).getRole()).isEqualTo("user");
        assertThat(stored.get(0).getClientMessageKey()).isEqualTo("display-key-1");
        assertThat(stored.get(0).getContent()).isEqualTo(input);
        assertThat(stored.get(1).getRole()).isEqualTo("assistant");
        assertThat(stored.get(1).getContent()).isEqualTo(answer);
        assertThat(stored.get(0).getTraceId()).isEqualTo(stored.get(1).getTraceId()).isNotBlank();
        assertThat(stored.get(0).getRunId()).isEqualTo(stored.get(1).getRunId()).isNotBlank();
    }

    /** 合法会话没有消息记录时保持真实空集合，不补造历史用户内容。 */
    @Test void emptyLegacySessionHasNoInventedUserMessages() {
        UserEntity user=users.save(new UserEntity("empty-"+UUID.randomUUID(),"empty-"+UUID.randomUUID()+"@example.invalid","unused-test-hash"));
        String session="empty-"+UUID.randomUUID();
        sessions.start(user.getId(),session,AgentMode.CONFIRM,UUID.randomUUID().toString());
        assertThat(messages.findByUserIdAndSessionIdOrderByCreatedAtAsc(user.getId(),session)).isEmpty();
    }

    /** 消息与事件各冻结上界；后续新行不能混入旧快照，每页合计受limit限制。 */
    @Test void historyPagesFreezeBothBoundsAndLimitCombinedItems() throws Exception {
        UserEntity user=users.save(new UserEntity("page-"+UUID.randomUUID(),"page-"+UUID.randomUUID()+"@example.invalid","unused-test-hash"));
        String session="page-"+UUID.randomUUID();
        sessions.start(user.getId(),session,AgentMode.CONFIRM,"trace-page");
        for(int i=0;i<12;i++) {
            // 使用已有实体写合成旧记录，测试读取契约不依赖尚未实现的新生产写入器。
            messages.save(new AgentMessageEntity(UUID.randomUUID().toString(),user,session,"trace-"+i,"run-page",i%2==0?"user":"assistant","正文"+i,java.time.Instant.now()));
            events.record(user.getId(),session,"parsed","事件"+i,null,"running",Map.of("eventId",UUID.randomUUID().toString()));
        }
        var owner=new UsernamePasswordAuthenticationToken(user.getId(),null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        var response=mvc.perform(get("/api/agent/sessions/"+session+"/history").param("limit","7").with(authentication(owner))).andExpect(status().isOk()).andReturn();
        var page=json.readTree(response.getResponse().getContentAsString());
        long through=page.get("throughEventId").asLong();
        // 第一页形成快照后插入合法新消息/事件；旧游标仍必须只返回前24项。
        messages.save(new AgentMessageEntity("late-message",user,session,"late-trace","late-run","user","晚到正文",java.time.Instant.now()));
        events.record(user.getId(),session,"parsed","晚到事件",null,"running",Map.of("eventId","late-event"));
        int count=0;java.util.Set<String> ids=new java.util.HashSet<>();
        for(int pageNumber=0;pageNumber<10;pageNumber++) {
            int size=page.get("messages").size()+page.get("events").size();
            assertThat(size).isBetween(1,7);count+=size;
            assertThat(page.get("throughEventId").asLong()).isEqualTo(through);
            page.get("messages").forEach(row-> { assertThat(row.get("publicId").asText()).isNotEqualTo("late-message");assertThat(ids.add("message:"+row.get("publicId").asText())).isTrue(); });
            page.get("events").forEach(row-> { assertThat(row.get("id").asLong()).isLessThanOrEqualTo(through);assertThat(ids.add("event:"+row.get("id").asLong())).isTrue(); });
            if(page.get("nextCursor").isNull())break;
            response=mvc.perform(get("/api/agent/sessions/"+session+"/history").param("limit","7").param("cursor",page.get("nextCursor").asText()).with(authentication(owner))).andExpect(status().isOk()).andReturn();
            page=json.readTree(response.getResponse().getContentAsString());
        }
        assertThat(count).isEqualTo(24);
    }

    /** 历史读取拒绝越权和非法输入，旧负载也必须走现有公开脱敏出口。 */
    @Test void historyRejectsOwnershipLimitCursorAndDoesNotExposeGrants() throws Exception {
        UserEntity user=users.save(new UserEntity("private-"+UUID.randomUUID(),"private-"+UUID.randomUUID()+"@example.invalid","unused-test-hash"));
        String session="private-"+UUID.randomUUID();sessions.start(user.getId(),session,AgentMode.CONFIRM,"trace-private");
        events.record(user.getId(),session,"confirmation_required","仅供历史展示",null,"waiting_confirmation",Map.of("confirmationGrant","合成敏感占位","summary",Map.of("confirmation_grant","合成敏感占位","title","真实摘要")));
        var owner=new UsernamePasswordAuthenticationToken(user.getId(),null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        String path="/api/agent/sessions/"+session+"/history";
        var response=mvc.perform(get(path).with(authentication(owner))).andExpect(status().isOk()).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("confirmationGrant","confirmation_grant","合成敏感占位");
        assertThat(json.readTree(response.getResponse().getContentAsString()).get("messages")).isEmpty();
        mvc.perform(get(path).param("limit","0").with(authentication(owner))).andExpect(status().isBadRequest());
        mvc.perform(get(path).param("limit","101").with(authentication(owner))).andExpect(status().isBadRequest());
        mvc.perform(get(path).param("cursor","invalid").with(authentication(owner))).andExpect(status().isBadRequest());
        var stranger=new UsernamePasswordAuthenticationToken(user.getId()+99999,null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mvc.perform(get(path).with(authentication(stranger))).andExpect(status().isNotFound());
    }

    /** 重复记录同一终态只读首条完整正文；同文案的新trace必须保留为另一轮。 */
    @Test void duplicateRecordingRetainsIdentityAndDifferentTraceIsAnotherRound() {
        UserEntity user=users.save(new UserEntity("duplicate-"+UUID.randomUUID(),"duplicate-"+UUID.randomUUID()+"@example.invalid","unused-test-hash"));
        String session="duplicate-"+UUID.randomUUID();
        var first=writer.write(user.getId(),session,"run-1","trace-1","assistant","第一条完整正文",null);
        var duplicate=writer.write(user.getId(),session,"run-1","trace-1","assistant","不得覆盖首条正文",null);
        var another=writer.write(user.getId(),session,"run-1","trace-2","assistant","第一条完整正文",null);
        assertThat(duplicate.getPublicId()).isEqualTo(first.getPublicId());
        assertThat(duplicate.getContent()).isEqualTo("第一条完整正文");
        assertThat(another.getPublicId()).isNotEqualTo(first.getPublicId());
        assertThat(messages.findByUserIdAndSessionIdOrderByCreatedAtAsc(user.getId(),session)).hasSize(2);
    }

    /** 写入器故障发生在独立边界；业务只执行一次并正常返回，历史单独报告503而非伪造成功。 */
    @Test
    @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void recordingFaultNeverRetriesBusinessAndHistoryIsUnavailable() throws Exception {
        UserEntity user=users.save(new UserEntity("fault-"+UUID.randomUUID(),"fault-"+UUID.randomUUID()+"@example.invalid","unused-test-hash"));
        String session="fault-"+UUID.randomUUID();
        org.mockito.Mockito.doThrow(new IllegalStateException("合成记录故障"))
            .when(writer).write(org.mockito.ArgumentMatchers.eq(user.getId()),org.mockito.ArgumentMatchers.eq(session),any(),any(),any(),any(),any());
        when(client.run(any())).thenReturn(AgentResult.completed("业务已经正常完成","completed",List.of()));
        var result=gateway.handle(user.getId(),session,"合法输入",AgentMode.CONFIRM);
        assertThat(result.status()).isEqualTo(AgentResult.Status.COMPLETED);
        assertThat(result.message()).isEqualTo("业务已经正常完成");
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(1)).run(any());
        assertThat(recording.isAvailable(user.getId(),session)).isFalse();
        // 重建服务对象模拟Spring重启丢失进程内Set；数据库缺口仍须明确不可完整恢复。
        var rebuiltRecording=new AgentHistoryRecordingService(writer);
        assertThat(rebuiltRecording.isAvailable(user.getId(),session)).as("服务重建不能遗忘已发生的历史记录缺口").isFalse();
        var owner=new UsernamePasswordAuthenticationToken(user.getId(),null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        mvc.perform(get("/api/agent/sessions/"+session+"/history").with(authentication(owner)))
            .andExpect(status().isServiceUnavailable());
    }

    /** 默认50与最大100条真实分页；签名游标不能移到同账号另一会话或被改写。 */
    @Test void twoHundredMessagesDefaultLimitAndCursorBinding() throws Exception {
        UserEntity user=users.save(new UserEntity("large-"+UUID.randomUUID(),"large-"+UUID.randomUUID()+"@example.invalid","unused-test-hash"));
        String session="large-"+UUID.randomUUID();String other="other-"+UUID.randomUUID();
        sessions.start(user.getId(),session,AgentMode.CONFIRM,"trace-large");
        sessions.start(user.getId(),other,AgentMode.CONFIRM,"trace-other");
        for(int i=0;i<200;i++) {
            // 不调用模型生成长会话；后端真实持久合成旧记录，正文不可因分页而截断。
            messages.save(new AgentMessageEntity(UUID.randomUUID().toString(),user,session,"trace-"+i,"run-large",i%2==0?"user":"assistant","完整长正文".repeat(1600),java.time.Instant.now()));
        }
        var owner=new UsernamePasswordAuthenticationToken(user.getId(),null,List.of(new SimpleGrantedAuthority("ROLE_USER")));
        String path="/api/agent/sessions/"+session+"/history";
        var first=mvc.perform(get(path).with(authentication(owner))).andExpect(status().isOk()).andReturn();
        var page=json.readTree(first.getResponse().getContentAsString());
        assertThat(page.get("messages").size()).isEqualTo(50);
        String cursor=page.get("nextCursor").asText();
        mvc.perform(get("/api/agent/sessions/"+other+"/history").param("cursor",cursor).with(authentication(owner))).andExpect(status().isBadRequest());
        // 改变MAC的首字符，避免仅改末尾base64填充位产生等价解码。
        int dot=cursor.indexOf('.');String changed=cursor.substring(0,dot+1)+(cursor.charAt(dot+1)=='A'?"B":"A")+cursor.substring(dot+2);
        mvc.perform(get(path).param("cursor",changed).with(authentication(owner))).andExpect(status().isBadRequest());
        int total=50;
        while(!page.get("nextCursor").isNull()) {
            var next=mvc.perform(get(path).param("cursor",page.get("nextCursor").asText()).param("limit","100").with(authentication(owner))).andExpect(status().isOk()).andReturn();
            page=json.readTree(next.getResponse().getContentAsString());
            assertThat(page.get("messages").size()).isLessThanOrEqualTo(100);
            page.get("messages").forEach(row->assertThat(row.get("content").asText()).isEqualTo("完整长正文".repeat(1600)));
            total+=page.get("messages").size();
        }
        assertThat(total).isEqualTo(200);
    }


    /** 同一气泡重试换trace，但用户展示消息不重复；不同气泡即使同文案仍要真实保留。 */
    @Test void displayRetryDoesNotDuplicateUserMessageOrBecomeBusinessIdempotency() {
        UserEntity user=users.save(new UserEntity("retry-"+UUID.randomUUID(),"retry-"+UUID.randomUUID()+"@example.invalid","unused-test-hash"));
        String session="retry-"+UUID.randomUUID();
        var first=writer.write(user.getId(),session,"run-1","trace-first","user","合法输入","same-bubble");
        var retry=writer.write(user.getId(),session,"run-1","trace-retry","user","合法输入","same-bubble");
        assertThat(retry.getPublicId()).as("同一展示气泡网络重试不得增加用户消息").isEqualTo(first.getPublicId());
        var another=writer.write(user.getId(),session,"run-2","trace-new","user","合法输入","another-bubble");
        assertThat(another.getPublicId()).isNotEqualTo(first.getPublicId());
        assertThat(messages.findByUserIdAndSessionIdOrderByCreatedAtAsc(user.getId(),session)).hasSize(2);
        // 展示键复用不同正文不能悄悄覆盖或丢弃，记录故障须由外层单独报告。
        org.assertj.core.api.Assertions.assertThatThrownBy(()->writer.write(user.getId(),session,"run-3","trace-different","user","不同正文","same-bubble"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    /** 展示键只消除重复用户气泡，不充当业务幂等键；显式新请求仍分别进入网关执行并记录助手轮次。 */
    @Test void sameDisplayKeyDoesNotSuppressGatewayExecution() {
        UserEntity user=users.save(new UserEntity("execution-"+UUID.randomUUID(),"execution-"+UUID.randomUUID()+"@example.invalid","unused-test-hash"));
        String session="execution-"+UUID.randomUUID();
        when(client.run(any())).thenReturn(AgentResult.completed("独立执行完成","completed",List.of()));
        gateway.handle(user.getId(),session,"合法输入",AgentMode.CONFIRM,"same-display-key");
        gateway.handle(user.getId(),session,"合法输入",AgentMode.CONFIRM,"same-display-key");
        org.mockito.Mockito.verify(client,org.mockito.Mockito.times(2)).run(any());
        var stored=messages.findByUserIdAndSessionIdOrderByCreatedAtAsc(user.getId(),session);
        // 用户展示身份可复用，但两次独立执行的助手结果必须保留各自trace，不因正文相同丢失。
        assertThat(stored.stream().filter(row->row.getRole().equals("user")).count()).isEqualTo(1);
        var replies=stored.stream().filter(row->row.getRole().equals("assistant")).toList();
        assertThat(replies).hasSize(2);
        assertThat(replies.stream().map(AgentMessageEntity::getTraceId).distinct().count()).isEqualTo(2);
    }
}
