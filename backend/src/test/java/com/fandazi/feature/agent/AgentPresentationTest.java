// 展示引用测试：人工固定卡片顺序及拒绝边界，先证明缺失服务会触发指定断言。
package com.fandazi.feature.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.DomainException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 展示身份属于上下文，不是确认或交易权限；后续补充真实 JPA 场景。 */
@SpringBootTest
class AgentPresentationTest {
    @Autowired private AgentPresentationService presentations;
    @Autowired private AgentPresentationRepository repository;
    @Autowired private AgentConversationSourceService sources;
    @Autowired private AgentSessionService sessions;
    @Autowired private AgentSessionRepository sessionRepository;
    @Autowired private UserRepository users;
    private Long user;private String session;private String source;

    /** 每例使用独立合成身份和可信来源，不依赖测试顺序。 */
    @BeforeEach void prepare() {
        String id=UUID.randomUUID().toString();session="presentation-"+id;
        user=users.saveAndFlush(new UserEntity("presentation-"+id,"presentation-"+id+"@example.invalid","synthetic")).getId();
        sessions.start(user,session,AgentMode.CONFIRM,"trace","2.1");
        source=(String)sources.issue(user,session,"展示列表","show-key").get("sourceMessageId");
    }
    @Test void presentationRegistryMustExistBeforeClaimingOrdinalSupport() {
        // 缺少登记模块必须明确失败，不能用已有卡片数组冒充可恢复的展示证据。
        assertThat(java.nio.file.Files.isRegularFile(java.nio.file.Path.of("src/main/java/com/fandazi/feature/agent/AgentPresentationService.java")))
            .as("CC-04 尚未实现展示登记").isTrue();
    }

    /** 卡片为已重查出口的合成结构，本测试验证登记而非冒充真实目录服务。 */
    private Map<String,Object> card(String id) {return Map.of("type","store","resourceId",id,"name","合成门店"+id);}
    /** 从服务端生成的元数据提取客户端允许回传的最小引用。 */
    private Map<String,Object> ref(Map<String,Object> card,int ordinal) {
        var metadata=(Map<?,?>)card.get("presentation");
        return Map.of("presentationId",metadata.get("presentationId"),"version",metadata.get("version"),"ordinal",ordinal);
    }

    @Test void exactOrderRetryAndPersistentReloadRemainStable() {
        // 预期第二项 B 人工固定，不从被测登记器推算答案；查询同一持久记录证明可恢复。
        var cards=List.of(card("a"),card("b"),card("c"));var first=presentations.register(user,session,source,cards);
        assertThat(presentations.register(user,session,source,cards)).isEqualTo(first);
        var context=presentations.context(user,session,ref(first.get(1),2));
        var proof=(Map<?,?>)((List<?>)context.get("presentationEvidence")).get(0);
        assertThat(proof.get("orderedIds")).isEqualTo(List.of("a","b","c"));
        assertThat(repository.findById((String)proof.get("presentationId"))).isPresent();
        assertThat(((Map<?,?>)context.get("selectionRef")).get("ordinal")).isEqualTo(2);
    }

    @Test void staleReorderedFilteredAndForgedReferencesAreRejected() {
        // 同来源重排必须推进版本；成员过滤完也不能让旧列表继续有效。
        var first=presentations.register(user,session,source,List.of(card("a"),card("b"),card("c")));
        var old=ref(first.get(0),2);
        var reordered=presentations.register(user,session,source,List.of(card("c"),card("a")));
        assertThatThrownBy(() -> presentations.context(user,session,old)).isInstanceOf(DomainException.class);
        var current=ref(reordered.get(0),2);
        assertThat(((Map<?,?>)((List<?>)presentations.context(user,session,current).get("presentationEvidence")).get(0)).get("version")).isEqualTo(2);
        for(Object invalid:List.of(0,3,true,"2",2.0)) {
            var changed=new HashMap<>(current);changed.put("ordinal",invalid);
            assertThatThrownBy(() -> presentations.context(user,session,changed)).isInstanceOf(DomainException.class);
        }
        var forged=new HashMap<>(current);forged.put("orderedIds",List.of("forged"));
        assertThatThrownBy(() -> presentations.context(user,session,forged)).isInstanceOf(DomainException.class);
        presentations.register(user,session,source,List.of());
        assertThatThrownBy(() -> presentations.context(user,session,current)).isInstanceOf(DomainException.class);
    }

    @Test void ownershipEpochAndLateDifferentListsCannotBeConfused() {
        // 不同来源的列表各有身份；晚到列表不会改写已有明确引用，旧 epoch 及其他用户拒绝。
        var first=presentations.register(user,session,source,List.of(card("a"),card("b"),card("c")));
        var reference=ref(first.get(0),2);
        String late=(String)sources.issue(user,session,"另一列表","late-key").get("sourceMessageId");
        presentations.register(user,session,late,List.of(card("x")));
        assertThat(((Map<?,?>)((List<?>)presentations.context(user,session,reference).get("presentationEvidence")).get(0)).get("orderedIds")).isEqualTo(List.of("a","b","c"));
        assertThatThrownBy(() -> presentations.context(user+100000,session,reference)).isInstanceOf(DomainException.class);
        String other="other-"+UUID.randomUUID();sessions.start(user,other,AgentMode.CONFIRM,"trace-other","2.1");
        assertThatThrownBy(() -> presentations.context(user,other,reference)).isInstanceOf(DomainException.class);
        var entity=sessionRepository.findBySessionIdAndUserId(session,user).orElseThrow();
        org.springframework.test.util.ReflectionTestUtils.setField(entity,"conversationEpoch","new-epoch");sessionRepository.saveAndFlush(entity);
        assertThatThrownBy(() -> presentations.context(user,session,reference)).isInstanceOf(DomainException.class);
    }

    @Test void gatewayValidatesBeforeCallingAgentAndOnlyRegistersFinalEnrichedCards() {
        // 真实网关与数据库，Agent/卡片重查为替身；确认登记取最终可公开结果而非原模型伪造项。
        var client=mock(AgentServiceClient.class);var events=mock(AgentEventService.class);var enrichment=mock(AgentCardEnrichmentService.class);
        when(client.run(any())).thenReturn(new AgentResult(AgentResult.Status.COMPLETED,"合成回复","completed",Map.of(),List.of(),List.of(card("raw-model-id")),null,false));
        when(enrichment.enrich(eq(user),eq(session),anyMap())).thenReturn(card("backend-verified-id"));
        when(enrichment.enrichSummary(eq(user),eq(session),anyMap())).thenReturn(Map.of());
        var gateway=new AgentGatewayService(sessions,client,events,enrichment);gateway.attachSources(sources,"2.1");gateway.attachPresentations(presentations);
        var result=gateway.handle(user,session,"查询门店",AgentMode.CONFIRM,"request-key");
        var reference=ref(result.cards().get(0),1);
        var proof=(Map<?,?>)((List<?>)presentations.context(user,session,reference).get("presentationEvidence")).get(0);
        assertThat(proof.get("orderedIds")).isEqualTo(List.of("backend-verified-id"));
        var invalid=new HashMap<>(reference);invalid.put("version",999);
        assertThatThrownBy(() -> gateway.handle(user,session,"第二个",AgentMode.CONFIRM,"next-key",null,invalid)).isInstanceOf(DomainException.class);
        verify(client,times(1)).run(any());
        gateway.handle(user,session,"第二个",AgentMode.CONFIRM,"next-key",null,reference);
        var captor=org.mockito.ArgumentCaptor.forClass(AgentRunRequest.class);verify(client,times(2)).run(captor.capture());
        assertThat(captor.getValue().context()).containsKey("presentationEvidence").containsEntry("selectionRef",reference);
        // 相同正文/展示键也不能换引用，防止重试偷换对象；冲突发生于外部调用前。
        var changed=new HashMap<>(reference);changed.put("ordinal",null);
        changed.remove("ordinal");
        assertThatThrownBy(() -> gateway.handle(user,session,"第二个",AgentMode.CONFIRM,"next-key",null,changed)).isInstanceOf(DomainException.class);
        verify(client,times(2)).run(any());
    }
}
