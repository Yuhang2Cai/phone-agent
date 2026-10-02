/** R3独立编译产物探针：只加载拟提交快照class及其原测试依赖，验证Spring注入和MVC路由，不访问数据库。
 * 请求使用合成身份；200只证明路由透传身份，持久化真实性由已完成H2测试及另行真实API检查判断。
 */
import com.fandazi.feature.grouporder.GroupBuyOrderController;
import com.fandazi.feature.grouporder.GroupBuyOrderService;
import com.fandazi.feature.purchase.PurchaseQuoteService;
import com.fandazi.feature.refund.RefundController;
import com.fandazi.feature.refund.RefundService;
import com.fandazi.feature.DomainException;
import com.fandazi.feature.DomainExceptionHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongConsumer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

public class CompiledReadProbe {
    /** 运行真实Spring构造注入及MockMvc分发；任何断言失败退出非零，结果只含合成身份和覆盖边界。 */
    public static void main(String[] args) throws Exception {
        // 输入校验：classpath必须缺少未来开发故障类，避免从混合工作区偷取该编译依赖。
        boolean futureClassAbsent = false;
        try { Class.forName("com.fandazi.development.DevelopmentOrderFailure"); }
        catch (ClassNotFoundException expected) { futureClassAbsent = true; }
        require(futureClassAbsent, "classpath包含未来开发类");
        var orderService = mock(GroupBuyOrderService.class);
        var quoteService = mock(PurchaseQuoteService.class);
        List<Map<String,Object>> expectedOrders = List.of(Map.of("orderId", "probe-order"));
        when(orderService.listForPage(701L)).thenReturn(expectedOrders);
        // 规则判断：让真实容器解析具名ObjectProvider，完全不注册LongConsumer bean。
        try (var context = new AnnotationConfigApplicationContext()) {
            // 模拟服务作为已就绪单例注册，避免给Mockito对象额外注入JPA；Controller仍由真实Spring构造。
            context.getBeanFactory().registerSingleton("groupBuyOrderService", orderService);
            context.getBeanFactory().registerSingleton("purchaseQuoteService", quoteService);
            context.register(GroupBuyOrderController.class);
            context.refresh();
            require(context.getBeansOfType(LongConsumer.class).isEmpty(), "出现可选故障bean");
            require(context.getBean(GroupBuyOrderController.class).list(701L) == expectedOrders,
                    "无bean正常读取未返回原领域服务结果");
            verify(orderService).listForPage(701L);
        }
        var refunds = mock(RefundService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new RefundController(refunds))
                .setControllerAdvice(new DomainExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver()).build();
        try {
            // 规范化认证：用户ID来自安全上下文，不允许请求参数冒充他人。
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(701L, "unused", List.of()));
            when(refunds.detailByVoucher(701L, "probe-voucher")).thenReturn(Optional.empty());
            mvc.perform(get("/api/refunds/by-voucher/probe-voucher")).andExpect(status().isNotFound());
            // 结果构造：真实@Controller路由将已有申请的同一公开身份透传，不伪造业务默认值。
            var existing = Map.<String,Object>of("requestId", "probe-request", "voucherId", "probe-voucher", "status", "PENDING");
            when(refunds.detailByVoucher(701L, "probe-voucher")).thenReturn(Optional.of(existing));
            mvc.perform(get("/api/refunds/by-voucher/probe-voucher"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.requestId").value("probe-request"))
                    .andExpect(jsonPath("$.voucherId").value("probe-voucher"));
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(702L, "unused", List.of()));
            when(refunds.detailByVoucher(702L, "probe-voucher")).thenThrow(new DomainException("团购券不存在"));
            mvc.perform(get("/api/refunds/by-voucher/probe-voucher"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DOMAIN_ERROR"));
            verify(refunds, times(2)).detailByVoucher(701L, "probe-voucher");
            verify(refunds).detailByVoucher(702L, "probe-voucher");
        } finally { SecurityContextHolder.clearContext(); }
        // 输出边界：明确200为合成服务结果的MVC检查，不算真实MySQL已有申请覆盖。
        Files.writeString(Path.of(args[0]), "{\"passed\":true,\"cases\":4,\"futureClassAbsent\":true,\"noLongConsumerBean\":true,\"registeredGetStatuses\":[404,200,400],\"existing200IsMockMvcOnly\":true}");
        System.out.println("R3编译产物探针4场景通过：无bean正常路径、GET404/同身份200/他人400");
    }

    /** 验证关键前置与真实返回；失败抛异常，阻止把编译或注入缺口记为成功。 */
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
