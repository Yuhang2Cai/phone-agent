package com.fandazi.feature.agent;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/**
 * 每个同步Servlet分派显式绑定当前应用的安全观测资源；不读取请求正文、用户身份或授权。
 * 默认关闭时资源为空；异步自有执行器须显式capture，不能依赖线程池偶然继承上下文。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public final class AgentOperationsFilter extends OncePerRequestFilter {
    private final AgentOperationsRuntime runtime;

    /** 注入本应用生命周期资源，不使用全局静态sink。 */
    public AgentOperationsFilter(AgentOperationsRuntime runtime) {this.runtime=runtime;}

    /** 仅包裹原过滤链；原HTTP响应/异常与鉴权先后关系保持，finally恢复线程上下文。 */
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                             FilterChain chain) throws ServletException, IOException {
        try(var scope=runtime.openScope()) {chain.doFilter(request,response);}
    }
}
