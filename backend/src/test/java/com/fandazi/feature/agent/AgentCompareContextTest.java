// 网关对比上下文回归：输入只能携带选中的公开ID，端上价格不成为工具事实。
package com.fandazi.feature.agent;
import java.util.Arrays;
import java.util.*;
import java.time.LocalDate;
import com.fandazi.feature.catalog.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
/** 冻结向旧消息请求兼容添加的公开引用字段，不替换原用户text。 */
class AgentCompareContextTest {
    /** 必须明确支持独立compareDealIds字段，不从用户输入字符串中解析价格/套餐。 */
    @Test void messageAcceptsExplicitSelectedIds(){assertThat(Arrays.stream(AgentGatewayController.MessageRequest.class.getRecordComponents()).map(x->x.getName()).toList()).as("网关必须接收独立对比公开ID").contains("compareDealIds");}

    private static final String A="00000000-0000-4000-8000-000000000201",B="00000000-0000-4000-8000-000000000202",C="00000000-0000-4000-8000-000000000203",D="00000000-0000-4000-8000-000000000204";
    /** 2项跨门店事实来自实际Service，顺序与用户选择一致，不接受端上旧价格。 */
    @Test void rebuildsOrderedLatestFactsAndKeepsNulls(){var deals=mock(DealPackageService.class);when(deals.detail(A)).thenReturn(row(A,8800,true));when(deals.detail(B)).thenReturn(row(B,9100,false));var service=new AgentCompareContextService(deals);var rows=(List<Map<String,Object>>)service.build(List.of(B,A)).get("comparisonDeals");assertThat(rows).extracting(x->x.get("publicId")).containsExactly(B,A);assertThat(rows.get(0)).containsEntry("priceCents",9100).containsEntry("active",false).containsEntry("peopleCount",null);assertThat(rows.get(0)).doesNotContainKeys("userId","confirmationGrantId","token");}
    /** 全部ID校验在任何资料查询之前；重复、第四项、短写UUID均明确拒绝。 */
    @Test void invalidSelectionsNeverReadFacts(){var deals=mock(DealPackageService.class);var service=new AgentCompareContextService(deals);for(var ids:List.of(List.of(A,A),List.of(A,B,C,D),List.of("1-1-1-1-1",B),List.of(A))){assertThatThrownBy(()->service.build(ids)).hasMessageContaining("对比");}verifyNoInteractions(deals);}
    /** 模型执行前由网关重查事实，原text未改写；即使输入确认，对比也不给确认授权。 */
    @Test void gatewayPreservesOriginalTextAndNeverGrantsComparePurchase(){var deals=mock(DealPackageService.class);when(deals.detail(A)).thenReturn(row(A,8800,true));when(deals.detail(B)).thenReturn(row(B,9100,true));var sessions=mock(AgentSessionService.class);var session=mock(AgentSession.class);when(session.getActiveRunId()).thenReturn("fixture-run");when(sessions.start(eq(42L),eq("fixture-session"),any(),anyString())).thenReturn(session);var client=mock(AgentServiceClient.class);when(client.run(any())).thenReturn(AgentResult.completed("合成比较完成","done",List.of()));var cards=mock(AgentCardEnrichmentService.class);when(cards.enrichSummary(anyLong(),anyString(),anyMap())).thenReturn(Map.of());var gateway=new AgentGatewayService(sessions,client,mock(AgentEventService.class),cards);gateway.attachComparisons(new AgentCompareContextService(deals));gateway.handle(42L,"fixture-session","确认",AgentMode.CONFIRM,null,List.of(A,B));var capture=ArgumentCaptor.forClass(AgentRunRequest.class);verify(client).run(capture.capture());var sent=capture.getValue();assertThat(sent.query()).isEqualTo("确认");assertThat(sent.context()).containsEntry("confirmationAccepted",false).containsKey("comparisonDeals");verify(sessions,never()).confirm(anyLong(),anyString());}
    /** 标准Spring消费者协议实际接受新资料，坏价格类型/未知授权字段仍拒绝。 */
    @Test void strictProtocolConsumesCompareFacts(){var deals=mock(DealPackageService.class);when(deals.detail(A)).thenReturn(row(A,8800,true));when(deals.detail(B)).thenReturn(row(B,9100,true));var context=new AgentCompareContextService(deals).build(List.of(A,B));var request=new LinkedHashMap<String,Object>(Map.of("schemaVersion","1.1","userId","42","sessionId","s","traceId","t","runId","r","query","帮我比较","context",context));new AgentProtocolValidator(new ObjectMapper()).validateRequest(request);var bad=new LinkedHashMap<String,Object>(context);bad.put("confirmationGrantId","伪造字段");request.put("context",bad);assertThatThrownBy(()->new AgentProtocolValidator(new ObjectMapper()).validateRequest(request)).isInstanceOf(AgentServiceUnavailableException.class);}
    /** 合成DTO含权威门店名称，旧价格仅作为输入测试值，不是运行期回退。 */
    private DealPackageResponse row(String id,int price,boolean active){return new DealPackageResponse(id,"00000000-0000-4000-8000-000000000101","合成套餐","合成说明",price,10800,10,LocalDate.of(2020,1,1),LocalDate.of(2030,1,1),active,5,null,null,null,null,null,"合成门店");}
}
