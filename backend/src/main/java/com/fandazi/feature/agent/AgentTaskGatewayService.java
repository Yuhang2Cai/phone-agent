// AG03显式2.2网关：先解释归属/验证新目标，后控制交接或普通受理；不持DB锁调用Agent。
package com.fandazi.feature.agent;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 旧/messages不经过此模块；短回复/控制不伪造业务run完成，不授予工具权限。 */
@Service
public class AgentTaskGatewayService {
    private final AgentSessionService sessions;private final AgentTaskRegistryService registry;
    private final AgentTaskTurnService turns;private final AgentTaskTurnRepository turnRows;
    private final AgentTaskControlService controls;private final AgentTaskCommandRepository commands;
    private final AgentTaskReconciliationService reconcile;private final AgentTaskInputClient inputs;private final AgentServiceClient client;
    private final AgentTaskFenceService fences;private final AgentTaskExecutionContext context;
    private final AgentCardEnrichmentService cards;private final AgentHistoryRecordingService history;
    private final boolean enabled;
    // 只读等待同命令的预算上限；不是网络重试或业务执行期限，不随客户端输入变化。
    private static final long MAX_INSERT_WAIT_MILLIS=10000;
    // 短间隔只读取持久命令，无数据库锁跨等待；不调用模型或再次派发控制。
    private static final long INSERT_POLL_MILLIS=25;
    private final long insertWaitMillis;
    private final AgentTaskReturnService returns;
    private final AgentPresentationService presentations;
    /** 只注入短事务服务，网关自身不加@Transactional，模型/HTTP始终在事务外。 */
    public AgentTaskGatewayService(AgentSessionService sessions,AgentTaskRegistryService registry,AgentTaskTurnService turns,
            AgentTaskTurnRepository turnRows,AgentTaskControlService controls,AgentTaskCommandRepository commands,
            AgentTaskReconciliationService reconcile,AgentTaskInputClient inputs,AgentServiceClient client,
            AgentTaskFenceService fences,AgentTaskExecutionContext context,AgentCardEnrichmentService cards,
            AgentHistoryRecordingService history,AgentTaskReturnService returns,AgentPresentationService presentations,@Value("${agent.task-lifecycle.enabled:false}") boolean enabled,
            @Value("${agent.task-lifecycle.insert-wait-ms:10000}") long insertWaitMillis) {
        this.sessions=sessions;this.registry=registry;this.turns=turns;this.turnRows=turnRows;this.controls=controls;this.commands=commands;
        this.reconcile=reconcile;this.inputs=inputs;this.client=client;this.fences=fences;this.context=context;this.cards=cards;
        this.history=history;this.returns=returns;this.presentations=presentations;this.enabled=enabled;
        if(insertWaitMillis<1||insertWaitMillis>MAX_INSERT_WAIT_MILLIS)throw new IllegalArgumentException("插入交接等待预算必须为1至10000毫秒");
        this.insertWaitMillis=insertWaitMillis;
    }
    /** 严格读取结构化对象；字段缺失/异常类型在任务变更前拒绝。 */
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object value) {
        if(!(value instanceof Map<?,?>))throw AgentTaskRegistryService.bad("任务输入对象无效");return (Map<String,Object>)value;
    }
    /** 数值必须为正整数；浮点/字符串/布尔不做宽松转换。 */
    private static long version(Object value) {
        if(!(value instanceof Integer)&&!(value instanceof Long))throw AgentTaskRegistryService.bad("任务版本必须为整数");
        long number=((Number)value).longValue();if(number<0)throw AgentTaskRegistryService.bad("任务版本不能为负");return number;
    }
    /** 文本不做有损清洗；只检查协议长度，完整原句交Agent解释，不按子串猜控制。 */
    private static String text(Object value,int max) {
        if(!(value instanceof String s)||s.isBlank()||s.length()>max)throw AgentTaskRegistryService.bad("任务输入文本无效");return s;
    }
    /** 只认本轮明确完整确认文字，附加修改必须先解释；真正消费还需当前turn与同展示摘要。 */
    private static boolean confirmationText(String text) {
        return Set.of("确认","确认购买","确认支付","确认退款","确认投诉").contains(text);
    }
    /** 确认动作须与实际展示摘要相同；通用“确认”绑定当前完整摘要，不能用支付文字授权购买等另一业务。 */
    private static boolean confirmationActionMatches(String text,Map<String,Object> summary) {
        // 空摘要或未知动作不能成为通用确认的对象，也不能把null传入不可变映射产生500。
        if(!(summary.get("action") instanceof String action))return false;
        String expected=Map.of("create_group_buy_order","确认购买","pay_mock_order","确认支付",
            "request_refund","确认退款","submit_complaint","确认投诉").get(action);
        return expected!=null&&("确认".equals(text)||expected.equals(text));
    }
    /** 对本人新协议处理一条稳定requestId；所有新目标先分类成功才可推进控制代次。 */
    public AgentResult handle(Long user,String sessionId,Map<String,Object> raw) {
        var allowed=Set.of("schemaVersion","requestId","text","button","confirmation","selectionRef");
        if(raw==null||!allowed.containsAll(raw.keySet())||!raw.keySet().containsAll(Set.of("schemaVersion","requestId","text"))||!"2.2".equals(raw.get("schemaVersion")))
            throw AgentTaskRegistryService.bad("必须显式使用任务协议2.2");
        String requestId=text(raw.get("requestId"),100),original=text(raw.get("text"),4000),inputHash=AgentTaskFingerprint.of(raw);
        if(raw.get("button")!=null) {
            var button=object(raw.get("button"));
            if(!button.keySet().equals(Set.of("taskId","taskVersion","action"))||!Set.of("pause","cancel","resume","switch").contains(button.get("action")))
                throw AgentTaskRegistryService.bad("任务按钮结构无效");
            text(button.get("taskId"),100);if(version(button.get("taskVersion"))<1)throw AgentTaskRegistryService.bad("任务按钮版本无效");
        }
        if(raw.get("confirmation")!=null) {
            var confirmation=object(raw.get("confirmation"));
            if(!confirmation.keySet().equals(Set.of("taskId","taskVersion","focusVersion","summary")))throw AgentTaskRegistryService.bad("任务确认结构无效");
            text(confirmation.get("taskId"),100);version(confirmation.get("taskVersion"));version(confirmation.get("focusVersion"));object(confirmation.get("summary"));
        }
        if(!enabled)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"任务输入暂未启用");
        sessions.startTaskSession(user,sessionId,UUID.randomUUID().toString());var scope=registry.scope(user,sessionId);
        // 查重先于新目录解释；首次受理后焦点/版本改变仍只能返回原结果，不新建第二任务或业务。
        var old=turnRows.findByUserIdAndSessionIdAndEpochAndRequestId(user,sessionId,scope.epoch(),requestId);
        if(old.isPresent()) {
            var turn=old.get();if(!inputHash.equals(turn.gatewayInputHash()))throw AgentTaskRegistryService.conflict("同请求内容已变化");
            if(turn.fence()==null){recordShortReply(user,sessionId,turn,original,(String)turn.result().get("message"));return replyResult((String)turn.result().get("message"));}
            return execute(user,sessionId,turn.id(),turn.fence(),turn.gatewayQuery(),original,raw.get("confirmation")!=null&&confirmationText(original),true);
        }
        // 控制可能已晚提交但原业务turn尚未受理；同请求必须先定位原命令，不能拿已变化目录重新分类或新建任务。
        var pendingInsert=commands.findByUserIdAndSessionIdAndEpochAndRequestId(user,sessionId,scope.epoch(),"input-insert-"+AgentTaskFingerprint.of(requestId));
        if(pendingInsert.isPresent()) {
            var saved=pendingInsert.get();
            if(!inputHash.equals(saved.details().get("gatewayInputHash")))throw AgentTaskRegistryService.conflict("原插入请求内容已变化或恢复资料不完整");
            var selected=raw.get("selectionRef")==null?Map.<String,Object>of():presentations.context(user,sessionId,object(raw.get("selectionRef")));
            return continueInsertedInput(user,sessionId,requestId,original,inputHash,selected,saved.id());
        }
        var directory=registry.directory(user,sessionId);var front=directory.get("currentTask")==null?null:object(directory.get("currentTask"));
        if(raw.get("button")!=null) {
            // 结构合法不等于本人可操作：在归属模型/文字快捷规则之前核账号、会话、epoch、版本及动作。
            // 外部按钮返回统一404；旧版本或终态动作409，不能忽略按钮后把“暂停”重绑到当前任务。
            var button=object(raw.get("button"));var referenced=registry.task(user,sessionId,(String)button.get("taskId"),scope.epoch());
            String permission="switch".equals(button.get("action"))?"resume":(String)button.get("action");
            if(version(referenced.get("taskVersion"))!=version(button.get("taskVersion"))
                    ||!((List<?>)referenced.get("availableActions")).contains(permission))
                throw AgentTaskRegistryService.conflict("任务按钮引用或动作已失效");
        }
        // 本人同epoch展示登记负责解析引用；仅允许最小selectionRef，任意候选/凭证字段在解释和控制前拒绝。
        Map<String,Object> selectedContext=raw.get("selectionRef")==null?Map.of():presentations.context(user,sessionId,object(raw.get("selectionRef")));
        boolean confirmed=false;Map<String,Object> shown=null;
        if(raw.get("confirmation")!=null) {
            var confirmation=object(raw.get("confirmation"));
            if(!confirmation.keySet().equals(Set.of("taskId","taskVersion","focusVersion","summary"))||front==null
                    ||!front.get("conversationTaskId").equals(confirmation.get("taskId"))||version(front.get("taskVersion"))!=version(confirmation.get("taskVersion"))
                    ||version(directory.get("focusVersion"))!=version(confirmation.get("focusVersion")))throw AgentTaskRegistryService.conflict("确认目标或版本已变化");
            shown=object(confirmation.get("summary"));var session=sessions.owned(user,sessionId);
            if(!AgentTaskFingerprint.of(shown).equals(AgentTaskFingerprint.of(session.getPendingConfirmation())))throw AgentTaskRegistryService.conflict("展示确认已变化");
            // 版本/摘要一致仅证明用户看过该动作；不同动作文字仍明确拒绝，不能先交模型后误消费原购买授权。
            if(confirmationText(original)&&!confirmationActionMatches(original,shown))
                throw AgentTaskRegistryService.conflict("确认文字与当前展示动作不一致，请核对后重新确认");
            confirmed=confirmationText(original);
        }
        var preflight=new LinkedHashMap<String,Object>();preflight.put("schemaVersion","2.2");preflight.put("requestId",requestId);
        preflight.put("scope",AgentTaskFingerprint.scope(scope));preflight.put("query",original);preflight.put("directory",directory);
        preflight.put("button",raw.get("button"));preflight.put("confirmationVerified",confirmed);
        preflight.put("selectedContext",selectedContext.isEmpty()?null:selectedContext);
        var decision=inputs.interpret(user,sessionId,preflight);String action=text(decision.get("action"),30);
        // 同一解释固定原文身份/旧焦点；客户端不能通过另一个requestId消费缓存的新目标。
        var decisionFields=Set.of("schemaVersion","requestId","action","targetTaskId","targetTaskVersion","newRequest","reasonCode","question",
            "intent","purpose","taskKind","focusVersion","returnTo","bodySha256","executionQuerySha256");
        String executionQuery="insert".equals(action)?text(decision.get("newRequest"),4000):original;
        if(!decision.keySet().equals(decisionFields)||!"2.2".equals(decision.get("schemaVersion"))||!requestId.equals(decision.get("requestId"))
                ||!sha256(original).equals(decision.get("bodySha256"))||!sha256(executionQuery).equals(decision.get("executionQuerySha256"))
                ||version(directory.get("focusVersion"))!=version(decision.get("focusVersion")))
            throw AgentTaskRegistryService.conflict("输入归属快照已变化");
        boolean showStop="show_current_stop".equals(decision.get("reasonCode"))||
            "reviewed_exact".equals(decision.get("reasonCode"))&&Set.of("继续","继续当前任务").contains(original);
        if("continue".equals(action)&&showStop) {
            // Agent已校验纯展示的两个候选/原文/目标版本；服务端再核当前目录和输入摘要，只读原停点。
            // 自然继续不是字段答案或交易确认，不新建业务turn、不消费/延长grant；普通字段continue仍走受理。
            if(front==null||!front.get("conversationTaskId").equals(decision.get("targetTaskId"))
                    ||version(front.get("taskVersion"))!=version(decision.get("targetTaskVersion")))throw AgentTaskRegistryService.conflict("原任务停点已变化");
            return reply(user,sessionId,requestId,original,inputHash,stopPrompt(front));
        }
        if(Set.of("clarify","no_task").contains(action)) {
            String message="clarify".equals(action)?text(decision.get("question"),1000):"reviewed_exact".equals(decision.get("reasonCode"))?
                "你好，我是饭搭子助手。当前任务保留原停点。":"已收到，当前任务保留原停点；范围外事项暂不支持办理。";
            return reply(user,sessionId,requestId,original,inputHash,message);
        }
        if(Set.of("pause","cancel","resume","switch").contains(action)) {
            var command=controls.prepare(user,sessionId,Map.of("requestId","input-control-"+AgentTaskFingerprint.of(requestId),
                "action",action,"targetTaskId",text(decision.get("targetTaskId"),100),"expectedTaskVersion",version(decision.get("targetTaskVersion")),
                "expectedFocusVersion",version(decision.get("focusVersion"))));
            var result=reconcile.process((String)command.get("commandId"),Instant.now());
            // 恢复只展示停点，不调用业务Agent；处理中保留原command，不说取消/暂停成功。
            String message="committed".equals(result.get("state"))?switch(action){case "pause" -> "当前任务已暂停。";case "cancel" -> "任务已取消，已产生的订单或申请仍保留。";default -> "已恢复任务焦点，请根据原停点继续；原确认需重新准备。";}:"任务正在交接或核查，请查看任务状态。";
            return reply(user,sessionId,requestId,original,inputHash,message);
        }
        String body=original;Map<String,Object> target=front;
        if("insert".equals(action)) {
            body=text(decision.get("newRequest"),4000);
            if(!original.contains(body))throw AgentTaskRegistryService.bad("新请求不属于原文连续区间");
            String kind=text(decision.get("taskKind"),30),intent=text(decision.get("intent"),40);
            AgentTaskControlService.ReturnRequest returnRequest=null;
            if(decision.get("returnTo")!=null) {
                var hint=object(decision.get("returnTo"));
                if(!hint.keySet().equals(Set.of("targetTaskId","targetTaskVersion","focusVersion","sourceStart","sourceEnd")))throw AgentTaskRegistryService.bad("返回指令结构无效");
                returnRequest=new AgentTaskControlService.ReturnRequest(text(hint.get("targetTaskId"),100),version(hint.get("targetTaskVersion")),
                    version(hint.get("focusVersion")),original,Math.toIntExact(version(hint.get("sourceStart"))),Math.toIntExact(version(hint.get("sourceEnd"))));
            }
            var command=controls.prepareGatewayInsert(user,sessionId,new AgentTaskControlService.NewGoal("input-insert-"+AgentTaskFingerprint.of(requestId),body,kind,intent,
                title(intent),version(decision.get("focusVersion")),returnRequest),inputHash,original);
            return continueInsertedInput(user,sessionId,requestId,original,inputHash,selectedContext,(String)command.get("commandId"));
        } else if(!Set.of("continue","revise").contains(action)||target==null||!target.get("conversationTaskId").equals(decision.get("targetTaskId"))
                ||version(target.get("taskVersion"))!=version(decision.get("targetTaskVersion")))throw AgentTaskRegistryService.conflict("当前输入归属目标无效");
        var accepted=turns.admitGatewayTurn(user,sessionId,new AgentTaskTurnService.Input(requestId,body,(String)target.get("conversationTaskId"),
            version(target.get("taskVersion")),version(directory.get("focusVersion")),true),inputHash,selectedContext);
        if(confirmed)fences.confirmCurrent(object(accepted.admission().get("fence")),authority(user,sessionId,accepted.turnId()).get("traceId").toString(),shown);
        return execute(user,sessionId,accepted.turnId(),object(accepted.admission().get("fence")),body,original,confirmed,false);
    }
    /** 仅接续原输入的插入命令；提交前不受理业务，提交后仍核原目标/焦点且普通轮次只受理一次。
     * 调用只来自当前HTTP或用户显式同键请求，不由GET、重连、后台协调器自动执行业务。
     */
    private AgentResult continueInsertedInput(Long user,String sessionId,String requestId,String original,String inputHash,
            Map<String,Object> selectedContext,String commandId) {
        var saved=commands.findById(commandId).orElseThrow(AgentTaskRegistryService::missing);
        var scope=registry.scope(user,sessionId);
        // 元数据在预备事务已固定；核完整信封后才允许处理原命令，正文/引用被修改不得借旧控制生效。
        if(!saved.scope().equals(scope)||!"insert".equals(saved.action())||!inputHash.equals(saved.details().get("gatewayInputHash")))
            throw AgentTaskRegistryService.conflict("原插入请求的身份或内容不一致");
        int start=Math.toIntExact(version(saved.details().get("gatewaySpanStartUtf16")));
        int end=Math.toIntExact(version(saved.details().get("gatewaySpanEndUtf16")));
        if(start>=end||end>original.length())throw AgentTaskRegistryService.conflict("原插入业务子句边界失效");
        String body=original.substring(start,end);
        awaitInsertedCommand(user,sessionId,commandId);
        var directory=registry.directory(user,sessionId);var front=directory.get("currentTask")==null?null:object(directory.get("currentTask"));
        // 晚到请求只能受理仍未开始的原新任务；用户已切换/取消/开始另一轮时不能自动绑到最新前台。
        if(front==null||!saved.target().equals(front.get("conversationTaskId"))||version(directory.get("focusVersion"))!=saved.focusVersion()
                ||front.get("activeRunId")!=null||front.get("lastRunId")!=null)
            throw AgentTaskRegistryService.conflict("原插入目标已变化，请核查当前任务");
        var accepted=turns.admitGatewayTurn(user,sessionId,new AgentTaskTurnService.Input(requestId,body,saved.target(),
            version(front.get("taskVersion")),version(directory.get("focusVersion")),true),inputHash,selectedContext);
        // 新目标只沿原输入准备/查询；插入流程本身不继承旧摘要或确认资格，写入仍等本任务新确认。
        // 受理事务返回后同键并发请求可能已经完成；始终先读原完成回执，不能只依据受理时的快照再发一次Agent。
        return execute(user,sessionId,accepted.turnId(),object(accepted.admission().get("fence")),body,original,false,true);
    }
    /** 有界等待当前派发者提交同一命令；只读轮询不重发apply、不持事务锁、不假报业务完成。 */
    private void awaitInsertedCommand(Long user,String sessionId,String commandId) {
        var result=reconcile.process(commandId,Instant.now());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(insertWaitMillis);
        while(true) {
            String state=(String)result.get("state");
            if("committed".equals(state))return;
            if(Set.of("aborted","rejected").contains(state))throw AgentTaskRegistryService.conflict("原插入交接已退出，不能继续原输入");
            if("needs_attention".equals(result.get("reconcileStatus"))||System.nanoTime()>=deadline)
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"原插入交接尚未核实；原请求未执行业务，请只读核查状态");
            try {Thread.sleep(INSERT_POLL_MILLIS);}
            catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,"等待交接已中断，原请求需核查");
            }
            result=controls.read(user,sessionId,commandId);
        }
    }
    /** 公共标题不复制退款原因、投诉全文或敏感对象，明确显示任务目的。 */
    private String title(String intent) {
        return switch(intent){case "store_info" -> "查询门店信息";case "store_recommendation" -> "选择门店";case "deal_recommendation" -> "选择套餐";
            case "refund_after_sale" -> "退款售后";case "complaint" -> "投诉处理";case "group_buy_order" -> "团购订单";case "faq" -> "常见问题查询";
            case "coupon" -> "优惠券查询";case "review_query" -> "评价查询";case "chitchat" -> "闲聊";default -> "复合业务任务";};
    }
    /** 从公开停点生成只读提示；不把“继续”写入原因/正文，也不重新准备、续期或自动消费确认。 */
    private String stopPrompt(Map<String,Object> task){
        var stop=object(task.get("stopPoint"));String kind=(String)stop.get("kind");
        if("waiting_confirmation".equals(kind))return "当前任务仍在等待确认，请核对原展示内容；若确认已过期，请重新准备。";
        if("awaiting_followup".equals(kind))return "当前选择已完成，可补充限制或明确选择对象继续。";
        if("waiting_input".equals(kind)){
            String field=String.valueOf(stop.get("awaitingField"));
            String label=switch(field){case "reason"->"退款原因";case "subject"->"投诉主题";case "description"->"投诉正文";
                case "quantity"->"购买数量";case "voucherId"->"团购券";case "dealId"->"套餐";case "storeId"->"门店";default->"原任务所需信息或对象";};
            return "当前任务仍在等待"+label+"，请继续补充。";
        }
        return "任务正在处理或核查，请查看当前状态。";
    }
    /** 与Agent原文摘要一致：计算UTF-8正文，不对JSON字符串加引号，不裁剪空白或规范化Unicode。 */
    private static String sha256(String text){
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException("正文摘要算法不可用",unavailable);}
    }
    /** 读取固定turn身份，不依赖随后可能变化的session activeRun/trace。 */
    private Map<String,Object> authority(Long user,String sessionId,String turnId) {return turns.readAuthority(user,sessionId,registry.scope(user,sessionId).epoch(),turnId);}
    /** 无工具短回复有独立幂等turn/outbox；不改前台trace、原待输入字段或确认期限。 */
    private AgentResult reply(Long user,String sessionId,String request,String body,String hash,String message) {
        var turn=turns.admitGatewayTurn(user,sessionId,new AgentTaskTurnService.Input(request,body,null,0,0,false),hash);
        var result=turns.completeReply(user,sessionId,turn.turnId(),message);
        recordShortReply(user,sessionId,turnRows.findById(turn.turnId()).orElseThrow(AgentTaskRegistryService::missing),body,(String)result.get("message"));
        return replyResult((String)result.get("message"));
    }
    /** 无业务run短回复使用自己的trace幂等记录；重复回读可修复写历史前响应中断，不借用前台trace。 */
    private void recordShortReply(Long user,String sessionId,AgentTaskTurnEntity turn,String original,String message) {
        history.recordUserLinkedSource(user,sessionId,null,turn.traceId(),original,turn.requestId(),turn.source());
        history.recordAssistant(user,sessionId,null,turn.traceId(),message);
    }
    /** 回复DTO没有业务run_completed事件；任务状态由真实task outbox展示。 */
    private AgentResult replyResult(String message){return new AgentResult(AgentResult.Status.COMPLETED,message,"task_state",Map.of(),List.of(),List.of(),null,true);}
    /** 完成后只读重查展示事实；2.2事件在领域重查前不得直接发布到旧SSE。 */
    private AgentResult execute(Long user,String sessionId,String turnId,Map<String,Object> fence,String body,String original,boolean confirmed,boolean repeated) {
        var row=authority(user,sessionId,turnId);var admission=new LinkedHashMap<>(object(row.get("admission")));
        // 重连用首次受理信封，Agent持久回执负责只补完成；completed许可绝不能变成新工具执行资格。
        admission.put("admissionState","admitted");admission.put("mayExecuteTools",true);
        var source=object(row.get("source"));String runId=(String)fence.get("runId"),trace=(String)row.get("traceId");
        // 展示历史保留用户完整意图；业务来源仍固定为已审核的连续子句及其摘要，不把返回后缀送进执行。
        // 同requestId的完整原文已在handle核对指纹；来源关联指向真实包含该子句的原始消息，不另造用户气泡。
        if(!original.contains(body))throw AgentTaskRegistryService.conflict("业务子句不属于原输入");
        history.recordUserLinkedSource(user,sessionId,runId,trace,original,(String)row.get("requestId"),(String)source.get("sourceMessageId"));
        source=object(authority(user,sessionId,turnId).get("source"));
        if(repeated) {
            // 业务子句以Spring原来源为准，不能把控制前缀/返回后缀当成新业务指令重新执行。
            var existing=turnRows.findById(turnId).orElseThrow(AgentTaskRegistryService::missing);
            if(existing.result()!=null){returns.completedTask((String)fence.get("conversationTaskId"));
                var recovered=completedReplay(user,sessionId,fence,object(existing.result().get("result")));
                history.recordAssistant(user,sessionId,runId,trace,recovered.message());return recovered;}
        }
        var trusted=new LinkedHashMap<String,Object>();trusted.put("conversationSource",source);trusted.put("taskAdmission",admission);trusted.put("confirmationAccepted",confirmed);
        trusted.putAll(turnRows.findById(turnId).orElseThrow(AgentTaskRegistryService::missing).gatewayContext());
        var result=client.run(new AgentRunRequest(String.valueOf(user),sessionId,body,"confirm",trace,runId,trusted,"2.2"));
        returns.completedTask((String)fence.get("conversationTaskId"));
        history.recordAssistant(user,sessionId,runId,trace,result.message());
        return enrich(user,sessionId,fence,result);
    }
    /** 已完成的HTTP重试只读原结果，既不新建任务、分类，也不重新发写工具。 */
    private AgentResult completedReplay(Long user,String sessionId,Map<String,Object> fence,Map<String,Object> result) {
        var rows=(List<?>)result.get("events");var converted=new ArrayList<AgentResult.Event>();var cardRows=new ArrayList<Map<String,Object>>();
        Map<String,Object> summary=Map.of();String message="原任务结果已恢复。";
        for(var item:rows){var event=object(item);var payload=object(event.get("payload"));String type=(String)event.get("eventType");
            // 持久完成结果保存原协议事件；回读须与实时SSE使用同一原身份，不能靠当前目录或新随机值补造。
            // 先复制业务负载，再以已验证顶层元数据覆盖同名字段；递归脱敏防止内部凭证进入公开HTTP。
            payload=new LinkedHashMap<>(payload);
            for(String key:List.of("schemaVersion","eventId","sequence","runId","traceId","status","taskContext"))
                if(event.get(key)!=null)payload.put(key,event.get(key));
            payload=AgentContractCompatibility.publicEventPayload(payload);
            converted.add(new AgentResult.Event(type,AgentContractCompatibility.summary(payload),null,(String)event.get("status"),payload));
            if("card".equals(type))cardRows.add(object(payload.get("card")));if("confirmation_required".equals(type))summary=object(payload.get("summary"));
            if(payload.get("message") instanceof String text)message=text;}
        var status=switch((String)result.get("status")){case "waiting_input" -> AgentResult.Status.NEEDS_INPUT;case "waiting_confirmation" -> AgentResult.Status.AWAITING_CONFIRMATION;
            case "failed" -> AgentResult.Status.FAILED;case "cancelled" -> AgentResult.Status.CANCELLED;default -> AgentResult.Status.COMPLETED;};
        return enrich(user,sessionId,fence,new AgentResult(status,message,"task_state",summary,converted,cardRows,null,true));
    }
    /** 卡片/金额/资格来自唯一领域重查；可信上下文只限本task代次，旧grant不能进入新确认。 */
    private AgentResult enrich(Long user,String sessionId,Map<String,Object> fence,AgentResult result) {
        return context.within(fence,()->{
            var enriched=result.cards().stream().map(card->cards.enrich(user,sessionId,card)).toList();
            var summary=cards.enrichSummary(user,sessionId,result.candidateCart());
            return new AgentResult(result.status(),result.message(),result.nextAction(),summary,result.events(),enriched,result.error(),true);
        });
    }
}
