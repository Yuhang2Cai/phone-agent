// 权威执行回查模块：只读原任务/轮次/执行键的已提交事实，不因查不到而重执业务。
package com.fandazi.feature.agent;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 内部结果可能含grant；本服务仅提取固定资源ID与状态白名单，不能返回完整result_json。 */
@Service
public class AgentTaskReceiptService {
    private final AgentTaskRegistryService registry;private final AgentTaskTurnRepository turns;private final AgentToolExecutionRepository executions;
    /** 注入只读仓储；不注入工具桥、HTTP或领域写服务。 */
    public AgentTaskReceiptService(AgentTaskRegistryService registry,AgentTaskTurnRepository turns,AgentToolExecutionRepository executions){this.registry=registry;this.turns=turns;this.executions=executions;}
    /** 核验账号/session/epoch/原task/run/turn，返回committed或unknown，永不推断not_executed。 */
    @Transactional(readOnly=true)
    public Map<String,Object> read(Long user,String session,String epoch,String task,String run,String turn,String key){
        if(key==null||key.isBlank()||key.length()>255)throw new ToolBoundaryException("VALIDATION_ERROR","执行键无效");
        var scope=registry.scope(user,session);if(!scope.epoch().equals(epoch))throw new ToolBoundaryException("FORBIDDEN","原执行范围已失效");
        registry.task(user,session,task,epoch);var admitted=turns.findById(turn).orElseThrow(()->new ToolBoundaryException("NOT_FOUND","原轮次不存在"));
        if(!admitted.scope().equals(scope)||!task.equals(admitted.taskId())||!run.equals(admitted.runId()))throw new ToolBoundaryException("FORBIDDEN","原执行归属不一致");
        // 回执格式绑定原turn持久协议；旧2.2字段保持不变，2.3才启用目标恢复安全映射。
        String protocol=admitted.protocolVersion();var row=executions.findById(key);var result=new LinkedHashMap<String,Object>();result.put("schemaVersion",protocol);result.put("executionKeyHash",AgentTaskFingerprint.of(key));
        result.put("conversationTaskId",task);result.put("runId",run);result.put("turnId",turn);
        if(row.isEmpty()){result.put("state","unknown");result.put("resources",Map.of());result.put("summary",Map.of("reasonCode","record_not_found"));return result;}
        if(!row.get().ownedTask(scope,task,run,turn))throw new ToolBoundaryException("NOT_FOUND","原执行记录不可用");
        // 资源和安全状态来自已提交记录；不复制内部prepared、价格正文、授权或任意动态字段。
        var resources=new LinkedHashMap<String,Object>();var data=row.get().getResult();String tool=row.get().tool();
        if("2.3".equals(protocol)){
            // 2.3按真实四写输出逐工具映射：恢复请求requestId与业务退款ID必须使用不同公开字段名。
            for(String field:List.of("orderId","voucherId","quoteId"))if(data.get(field) instanceof String id)resources.put(field,id);
            if("request_refund".equals(tool)&&data.get("requestId") instanceof String id)resources.put("refundRequestId",id);
            if("submit_complaint".equals(tool)&&data.get("ticketId") instanceof String id)resources.put("complaintTicketId",id);
        }else{
            // 2.2回执已冻结，继续只读取原白名单，不能因2.3修复改变老任务字节语义。
            for(String field:List.of("orderId","voucherId","refundId","complaintId","quoteId"))if(data.get(field) instanceof String id)resources.put(field,id);
        }
        var summary=new LinkedHashMap<String,Object>();if(data.get("status") instanceof String status)summary.put("status",status);
        result.put("state","committed");result.put("toolName",tool);result.put("resources",resources);result.put("summary",summary);return result;
    }
}
