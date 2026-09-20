# 饭搭子 AI 技术架构

本文档记录当前技术架构和跨层边界。Android UI、页面状态、导航和 UI 验收标准以 [`ui-redesign.md`](ui-redesign.md) 为唯一来源；API、SSE 和 AI 授权协议以 [`api-and-agent.md`](api-and-agent.md) 为准。

Android UI 不以 HTML 原型作为实现或视觉还原中间层。HTML 原型仅可作为历史交互参考，不能替代设计稿，也不能成为 Android UI 验收依据。

饭搭子 AI 有三个运行层：Android 客户端、Spring Boot 后端和 Python Agent Service。

## Android 客户端

Android 使用 XML 布局、单 Activity、多 Fragment、MVVM 风格的 `ViewModel` 状态、OkHttp、Glide、协程和 Flow。`ApiClient` 统一处理 Bearer 鉴权、超时、JSON 错误映射以及幂等 GET 请求的一次重试。Glide 通过图片加载器封装；图片失败不能阻断文字内容展示。

Android 的页面状态由 ViewModel 暴露，页面统一处理 Initial、Loading、Content、Empty、Error，以及提交操作中的 Submitting、Disabled、AwaitingConfirmation 和 Success。临时输入、搜索关键词、购物车、当前地址和页面栈需要支持旋转屏幕与进程重建后的恢复；订单和 AI 会话以后端为事实来源。

应用采用四项底部导航：首页、商家、订单、我的。认证页和模拟支付页隐藏底部导航。AI 助手以浮动入口和底部面板呈现，在首页、搜索、商家和订单页可用；认证、地址编辑和支付页面隐藏入口，但会话和候选订单仍可保留。

AI 助手面板支持半屏、全屏、录音、识别、发送、执行、等待确认、创建订单和错误状态。用户只看到“AI 助手”和可读执行进度，不直接看到 Agent、模型、工具调用或工作流等技术概念。

无地址时 Android 仍允许搜索和 AI 推荐；创建订单前必须有有效地址；自动下单必须有有效默认地址和未超限授权。AI 确认后创建待支付订单，再进入模拟支付页，不在客户端绕过订单或授权校验。

离线 ASR 仅用于本地文本输入。模型随 APK 打包，录音结束后识别，用户可以编辑识别结果再发送。Android 不运行 LLM 或 LangGraph，也不保存或展示服务端模型 API Key。

## Spring Boot 后端

Spring Boot 负责用户身份、菜单、地址、购物车校验、订单、模拟支付、配送时间线、AI Gateway、内部工具授权和幂等性。它是价格、库存、地址归属、金额上限、订单状态和自动化授权的最终裁决方。

SSE 事件由 Spring Boot 持久化并推送。客户端可以使用 `Last-Event-ID` 重连；公开的 AI 消息和事件流使用普通用户 Bearer Token。内部工具调用需要共享内部 Token，不能暴露给 Android。

## Python Agent Service

Python 承载受约束的单一 `FoodOrderingAgent`。LangChain 用于模型适配和结构化意图输出，LangGraph 编排理解需求、补充信息、搜索候选、构建候选购物车、校验订单、等待确认、创建订单和支付后续流程。

Agent Service 不连接 MySQL，也不直接执行业务副作用。所有副作用都通过 Spring Boot 白名单工具完成，并使用稳定的工作流级幂等键。确认、订单恢复、支付重试和取消状态支持导出/恢复；同主机重启恢复可使用持久化工作流存储。

## 用户可见边界

- Android 只展示“AI 助手”，不展示 Agent、模型名称、服务状态、API Base URL 或 API Key。
- 普通确认流程为：候选订单确认 → 创建待支付订单 → 模拟支付 → 配送状态。
- 本次自动和长期自动模式都必须经过后端对地址、金额、价格、库存和授权有效期的校验。
- Agent 服务不可用时，普通搜索、商家浏览、购物车、结算和模拟支付仍应可用。

## 为什么使用一个 Agent

首版使用一个受约束的点餐 Agent，不拆分多个 Agent。这样可以保持单一工作流、单一工具白名单、单一确认协议和 Spring Boot 单一业务权威，便于测试和审计。
