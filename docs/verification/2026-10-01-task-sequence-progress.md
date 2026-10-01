# Task1 → Task3 → Task2 → Task8 执行记录

实施计划：`docs/superpowers/plans/2026-09-29-group-buying-agent-refactor-implementation.md`。规格：同目录体系的团购重构 spec。用户授权按此顺序逐项实施，每项独立验收后进入下一项。

## 工作区与边界

- 手机工作区：`C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai`，已有独立 worktree，分支 `feature/fandazi-ai`。
- 新 Agent 工作区：`D:/project/-Demo-master`，从 master 创建 `codex/group-buying-protocol-service` 分支，保留所有原有未提交改动。
- `C:/fdz-build` 是手机工作区 junction，不执行源码同步。
- 不回滚原有改动，不提交密钥；核销延期。UI 不采用 TDD，不以轻微尺寸和颜色差异阻塞验收。
- 验收必须由不同于实现者的独立 Agent 亲自运行适用测试；Android 改动须最新 APK 与 Maestro。

## 执行状态

| 任务 | 实现 | 开发自测 | 独立验收 | 提交 |
|---|---|---|---|---|
| Task1 协议补正 | 已实现 | 已通过 | 独立 PASS：44+34+106，首次 FAIL 已保留 | 待范围提交 |
| Task3 新 Agent 接入 | 已实现：新服务/12类/DAG/工具桥接 | 最新 283 项离线、5 项真实双服务通过，首次失败已保留 | 独立 PASS：Python 356、Maven 72 | 待范围提交 |
| Task2 页面数据准备 | 进行中：金额/资源/页面字段与隔离夹具 | 未运行 | 未运行 | 未提交 |
| Task8 售后收尾 | 未开始本轮实施 | 未运行本轮自测 | 尚未通过 | 未提交 |

## 前置与共享接口核对

| 任务/接口 | 产出与消费 | 当前处理 |
|---|---|---|
| Task1 自身 | 1.1 YAML、模型、兼容适配、字段测试 | 先测试关键边界，再更新契约和运行时；不以工具名称检查替代字段检查 |
| Task1 → Task3 | 1.1 请求、暂停事件、确认字段、17 工具 | Task3 使用权威副本，不能自行定义另一套字段 |
| Task1 → Task2 | 确认摘要和资源字段 | Task2 保持已冻结字段，不在 Android 生成业务事实 |
| Task1 → Task8 | waiting_input、确认动作和终态 | Task8 复用协议与聊天状态，不依赖旧 CandidateCart |
| Task3 自身 | 新项目入口与最小 12 类识别 | 必须核对进程来源，不将旧服务 200 算成新服务通过 |
| Task3 → Task8 | 真实分类、工作流、恢复和工具调用 | Task8 AI 路径不得早于 Task3 验收 |
| Task2 自身 | 页面必需字段、支付快照、资格、夹具 | 数据迁移仅追加，不修改已执行迁移 |
| Task2 → Task8 | 退款金额、本人资源、评价资格和图片 | 先接口验证，再绑定页面 |
| Task8 自身 | 八个子步骤、功能与合理视觉容差 | 设计人工确认不等于最新 APK 通过 |

## 决策与证据

- 当前新 Agent 原主仓含用户改动；在原 checkout 切换新工作分支而不另建空 worktree，避免丢失现有设计与配置。手机沿用现有隔离 worktree；不移动或覆盖两仓。
- 若协议稳定 runId 需要最小持久化字段，归属 Task1，迁移从实际最高版本后追加；不借用旧外卖工作流字段承载不同语义。
- Task1 契约/测试由独立实现子 Agent 修改，控制端仅实现不重叠的运行时边界；最终统一交给新的验收 Agent。
