// 智能助手模块：提供接口并将请求交给业务层。
package com.fandazi.feature.agent;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/internal/tools") public class InternalToolController {
    private final InternalTokenValidator tokens; private final AgentToolService tools;
// 初始化对象依赖与默认状态。
    public InternalToolController(InternalTokenValidator tokens,AgentToolService tools){this.tokens=tokens;this.tools=tools;}
// 处理 `call` 相关逻辑。
    @PostMapping("/{toolName}") public ToolResult call(@PathVariable String toolName,@RequestHeader(value="X-Agent-Token",required=false) String token,@RequestBody(required=false) ToolRequest request){tokens.validate(token);if(!tools.isAllowed(toolName))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"工具不存在");if(request==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"工具请求不能为空");return tools.execute(toolName,request);}
}
