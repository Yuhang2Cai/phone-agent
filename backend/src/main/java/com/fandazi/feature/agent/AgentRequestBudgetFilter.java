// AG08公开业务入口预算：不读取客户端时限，鉴权/归属/执行共享本次服务器保护上限。
package com.fandazi.feature.agent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 仅消息与任务输入POST绑定预算；SSE订阅、取消控制、健康及离线管理保持独立。 */
@Component
@Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE+20)
public class AgentRequestBudgetFilter extends OncePerRequestFilter {
    private final long millis;
    /** 部署保护值必须有界；此值不构成未经测量的性能承诺。 */
    public AgentRequestBudgetFilter(@Value("${agent.request-budget-millis:120000}") long millis){
        if(millis<=0||millis>120000)throw new IllegalArgumentException("请求预算配置无效");this.millis=millis;
    }
    /** 路径仅选择业务入口，不使用用户正文或头部决定是否放宽截止。 */
    @Override protected boolean shouldNotFilter(HttpServletRequest request){
        String path=request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod())||!path.matches("/api/agent/sessions/[^/]+/(messages|task-inputs)");
    }
    /** 同步请求结束恢复ThreadLocal；不会取消其它线程或声称已提交交易回滚。 */
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException{
        try(var budget=AgentRequestBudget.open(millis)){chain.doFilter(request,response);}
    }
}
