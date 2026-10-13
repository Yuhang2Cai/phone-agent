// TL-09在线合成前置桥：只准备真实身份、目录和目录对象；归属/控制/业务均走正式HTTP与产品Bean。
package com.fandazi.feature.agent;

import com.fandazi.feature.auth.UserRepository;
import com.fandazi.feature.auth.UserEntity;
import com.fandazi.feature.catalog.StoreRepository;
import com.fandazi.feature.catalog.DealPackageRepository;
import java.util.*;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

/** 仅在测试classpath，不能进入生产JAR；直接种暂停行为只代表前置，不算控制成功证据。 */
public final class AgentTaskOnlineFixtureBridge {
    private final ApplicationContext context;
    /** 注入真实容器，禁止通过请求替换实现、网络地址或数据库。 */
    public AgentTaskOnlineFixtureBridge(ApplicationContext context){this.context=context;}
    /** 所有事务仍交给产品容器代理，不在桥中复制控制/订单实现。 */
    private <T>T bean(Class<T> type){return context.getBean(type);}
    /** 固定合成驱动主体并复用产品内部JWT校验；密钥不输出。 */
    public void authorize(String authorization){bean(InternalV1JwtValidator.class).validate(authorization,"910000");}
    /** 按冻结前置创建合成scope；返回实际公开ID，不能接收金标动作或伪造模型答案。 */
    @SuppressWarnings("unchecked") public Object call(Map<String,Object> raw){
        if("database".equals(raw.get("op")))return bean(JdbcTemplate.class).queryForMap("SELECT DATABASE() AS database_name, VERSION() AS database_version");
        if(!"seed".equals(raw.get("op")))throw new IllegalArgumentException("未知在线前置操作");
        var setup=(Map<String,Object>)raw.get("setup");var registry=bean(AgentTaskRegistryService.class);var jdbc=bean(JdbcTemplate.class);
        if(Boolean.TRUE.equals(setup.get("legacy"))) {
            // 默认保留TL09的真实2.0隔离前置；TL10显式选择2.1时，必须同时提供随后真实输入的原文和防重键。
            // 先经正式来源服务登记该输入身份以登记合成白名单，随后公开messages入口复用同键并真实保存原文。
            // 此处不生成模型结果或购买停点；2.1停点只能由真实请求建立，不能先创建2.2再绕回旧入口。
            if(!Boolean.FALSE.equals(setup.get("current"))||((Number)setup.getOrDefault("paused",0)).intValue()!=0)
                throw new IllegalArgumentException("旧会话前置不能创建2.2任务");
            String id=UUID.randomUUID().toString(),session="ag03-legacy-"+id;
            var user=bean(UserRepository.class).saveAndFlush(new UserEntity("ag03-"+id,"ag03-"+id+"@example.invalid","synthetic-hash"));
            String protocol=(String)setup.getOrDefault("legacyProtocol","2.0");
            if(!java.util.Set.of("2.0","2.1").contains(protocol))throw new IllegalArgumentException("旧会话协议不受支持");
            String body="2.1".equals(protocol) ? (String)setup.get("preludeText") : "合成旧会话前置";
            String key="2.1".equals(protocol) ? (String)setup.get("preludeKey") : null;
            if("2.1".equals(protocol)&&(body==null||key==null))throw new IllegalArgumentException("2.1前置须绑定真实输入");
            bean(AgentSessionService.class).start(user.getId(),session,AgentMode.CONFIRM,"synthetic-legacy-trace",protocol);
            var source=bean(AgentConversationSourceService.class).issue(user.getId(),session,body,key);
            return Map.of("userId",String.valueOf(user.getId()),"sessionId",session,"conversationEpoch",source.get("conversationEpoch"),"roles",Map.of());
        }
        var f=new AgentTaskTestFixtures(bean(UserRepository.class),bean(AgentSessionService.class),registry,jdbc);
        var result=new LinkedHashMap<String,Object>();var roles=new LinkedHashMap<String,String>();
        String intent=(String)setup.getOrDefault("intent","deal_recommendation");
        var kinds=Map.ofEntries(Map.entry("store_recommendation","selection"),Map.entry("deal_recommendation","selection"),
            Map.entry("store_info","lookup"),Map.entry("review_query","lookup"),Map.entry("group_buy_order","transaction"),
            Map.entry("coupon","lookup"),Map.entry("refund_after_sale","transaction"),Map.entry("complaint","transaction"),
            Map.entry("faq","lookup"),Map.entry("chitchat","chat"),Map.entry("composite","composite"));
        if(!kinds.containsKey(intent))throw new IllegalArgumentException("前置意图不能生成业务任务");
        int paused=((Number)setup.getOrDefault("paused",0)).intValue();if(paused<0||paused>5)throw new IllegalArgumentException("前置暂停数量不合法");
        // 这些暂停任务没有执行过业务；仅建立目录歧义/容量前置，后续真实控制不得引用种行作为ACK。
        for(int index=0;index<paused;index++){
            String id=AgentTaskTestFixtures.id(registry.create(f.user,f.session,kinds.get(intent),intent,"合成暂停任务"+(index+1)));
            jdbc.update("UPDATE agent_conversation_tasks SET lifecycle='paused',active_scope_key=NULL,last_paused_at=CURRENT_TIMESTAMP,stop_kind='waiting_input' WHERE conversation_task_id=?",id);
            jdbc.update("UPDATE agent_sessions SET current_conversation_task_id=NULL,task_focus_version=task_focus_version+1 WHERE session_id=?",f.session);
            roles.put("paused_"+(index+1),id);
        }
        if(!Boolean.FALSE.equals(setup.get("current"))){
            String id=AgentTaskTestFixtures.id(registry.create(f.user,f.session,kinds.get(intent),intent,"合成当前任务"));roles.put("current",id);
            // stop_kind仅描述前置，不创建run/checkpoint/确认；真实字段/确认场景须从正式消息链路产生。
            if("awaiting_followup".equals(setup.get("stop_kind")))jdbc.update("UPDATE agent_conversation_tasks SET stop_kind='awaiting_followup' WHERE conversation_task_id=?",id);
        }
        result.put("userId",String.valueOf(f.user));result.put("sessionId",f.session);result.put("conversationEpoch",f.epoch);
        result.put("roles",roles);result.put("directory",f.directory());return result;
    }
}
