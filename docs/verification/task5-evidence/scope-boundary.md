# Task5 源码归属与最小依赖边界

## 基准与取证方法

手机实施 BASE `c614bd57218dd315e4ce03a60537023526cd6d49`，D 新 Agent BASE `ee04c2cd0fa0232e857da915c4ca2773842afb28`。实施开始时两端已有大量 dirty/未跟踪文件；Git HEAD→工作区全量 diff 不是 Task5 写集。

本任务在改产品前将既有聊天 Kotlin、MainActivity、相关布局与 Spring agent 源码/测试复制至忽略目录 `.runtime/task5-source-before`。`task5-freeze-manifest.json` 每项保存前像/当前 SHA-256；`task5-own-source.patch` 只取前像→当前增量。补丁不是对干净 c614bd5 承诺可直接编译，必须先满足下面明确列出的既存依赖。前像不可从 Git 恢复的未跟踪布局留原字节副本，不能推定它们是本 Agent 从零创建。

## Task5 真正新增与修改

- 真正新增产品/测试9项：AgentPublicCards.kt、Task5ChatStateTest.kt、item_agent_empty.xml、item_agent_context.xml，以及 agent_send_background / agent_chat_input_background / agent_chat_bot_bubble / agent_chat_user_bubble / agent_chat_empty_avatar 五个 drawable。
- 前像对比修改22项，逐项在 manifest；其中7个 item_agent 布局原本已未跟踪，属于“Task5 修改原未跟踪依赖”，不是 Task5 创建整文件。AgentViewModel、AgentModels、AgentRepository、AgentCardRenderer 原有购买、授权、退款、投诉分支保留在前像，补丁仅描述本轮聊天/展示增量。
- Spring 最小桥接仅 AgentMessageService/HttpAgentServiceClient 原事件身份与任务状态保留、AgentGatewayService 兼容记录带真实runId/traceId、AgentCardEnrichmentService 只读门店事实/订单有效期补全及2个定向测试；不改17工具、数据库游标、脱敏、鉴权或审计列。
- 本轮 Task5 运行器/流程另列 manifest；`task5-ai-chat-page.yaml` 原本未跟踪，是本轮重写的 Task5 流程，非Task8流程。其余Task5运行器/流程为本轮新增。
- D 新 Agent 产品源码/测试/配置未修改。实验文档只追加 `EXP-016`，原EXP-014/015与既存脏内容不属Task5。

## 共享文件逐项划界

| 文件 | Task5 最小增量/依赖 | 明确排除的后续业务 |
| --- | --- | --- |
| MainActivity.kt | 本轮仅两个 hunk：AI tab 无论游客均进入唯一 agentChatFragment；navigateForSession 调用 bindAuthenticatedOwner 清除跨账号本地聊天。前像与 c614bd5 相同，补丁中没有Task6/7/8新增hunk | 既有 ordering cart owner、下单/支付路由等不新增、不归Task5 |
| nav_graph.xml | 本 Agent 未修改。agentChatFragment、storeFragment、dealDetailFragment 及登录/首页入口已存在 c614bd5；F 卡显式点击依赖工作区已存在的 `groupOrderDetailFragment` + string `orderId` 参数三行注册 | c614bd5以外其余新增 checkout/payment/success、voucher/QR/redemption、refund/progress/review/help/complaint 均是原Task6/7/8或延期代码，不能整文件归Task5 |
| SessionRouteCoordinator.kt | 本 Agent 未修改；相对c614bd5没有内容差异，仅Git状态曾显示M/换行警告。所需最小版本就是c614bd5的AUTH/HOME及Guest→HOME | 无本轮产品hunk，不需要为Task5整文件重提交 |
| FandaziApplication.kt | 本 Agent 未修改。F订单详情读取的最小依赖为原有 HttpVoucherRepository/VoucherRepository 两个import、voucherRepository属性及HttpVoucherRepository(apiClient)初始化 | purchaseRepository 与 afterSaleRepository import/属性/初始化属Task6/8，不列Task5增量 |
| AgentEntryPoint/AgentFloatingButtonPolicy/AgentSheetFragment/AgentProgressPresentation | 本 Agent 未改；有前像的逐字节相同，保留当前工作区调用关系和版本。正式入口不新增第二聊天页面 | 旧半屏/语音/授权实现不是本轮交付，不以保留代表ASR验收 |
| AgentCardRenderer.kt/item_agent_order_card.xml | 增量涉及公共卡片事实、订单展示、真实共享准备摘要及确认/取消按钮；原 after_sale_voucher_choice/refund/complaint 逻辑保留，未当成本轮实现 | Task8具体选券、退款、投诉行为与测试仍需Task8独立验收 |

F 卡需要的导航最小 hunk（已有脏依赖，本任务未写入）：

```xml
<fragment android:id="@+id/groupOrderDetailFragment" android:name="com.fandazi.app.feature.voucher.GroupOrderDetailFragment" android:label="订单详情">
    <argument android:name="orderId" app:argType="string" />
</fragment>
```

## 原 dirty/未跟踪只读依赖版本

`task5-freeze-manifest.json.unchangedDependencies` 给出确切当前文件 SHA，包含上述共享文件、GroupOrderDetailFragment/VoucherRepository/VoucherModels、fragment_group_order_detail/item_voucher_row。这些没有独立已提交的最低版本，不编造提交号；只能用列出的前像/当前 SHA 与最小 hunk 指认。

F 卡的只读调用闭环为 AgentCardRenderer.Order→groupOrderDetailFragment(orderId)→既存GroupOrderDetailFragment.load→VoucherRepository.getOrder→GET group-orders/{orderId}。所需数据转换为 toGroupOrder/toVoucher/mapJson 和模型/布局；不需要 createDisplayToken/redeem 等写方法。当前完整 GroupOrderDetailFragment 还引用既有 voucherDetailFragment/reviewFragment 的资源ID，这属于保留后续页面的编译依赖，不表示本任务测试了这些入口。不能只抽三行导航、却声称未列其他依赖就能从干净BASE构建整应用。

D 的真实复合恢复依赖其实施前已 dirty 的 service/engine.py、service/dag_runtime.py、service/recommendation.py 等当前工作区实现；本任务不将它们归为新源码或修改任务状态。服务/测试/配置文件总摘要与原审计值一致的证据另见 release-environment.json；确切工作区依赖由独立验收在当前冻结环境重跑。

## 隔离与提交说明

本 Agent 不暂存、不提交、不回滚；冻结是供控制端核对归属，不授权把混合脏文件整包提交。控制端按本轮补丁及最小依赖 hunk处理，其他Task6/7/8源码保留原所有者。真实夹具摘要在manifest.protectedFiles；本任务新待支付订单源于独立随机用户展示夹具，不消费Task8七场景八券。

原GroupOrderDetailFragment显示“实付”及券状态投影等既存细节属于Task7页面，不用F聊天卡的通过替代Task7验收；本轮聊天卡使用“合计/待支付”真实字段。审计payload容量8000关注保持，未扩大Task5修改数据库列。
