# 团购重构逐任务执行记录

实施计划：`docs/superpowers/plans/2026-09-29-group-buying-agent-refactor-implementation.md`。规格：同目录体系的团购重构 spec。原 Task1 → Task3 → Task2 → Task8 顺序只保留为历史，当前依实施计划逐任务推进，每项独立验收后进入下一项。

**当前（2026-10-03）：Task8已独立R2通过并精确提交，计划已勾选，本轮结束。以下10月2日暂停/恢复叙述为历史，不是当前执行指令；Task9–12、ASR和核销未实施。**

2026-10-02用户要求暂停 Task8：计划顺序需要重新讨论。本台账保留前三项已完成状态；Task8 停止实现、测试与验收，未提交、不勾选完成、不自动进入 Task9。恢复检查点：`D:/project/-Demo-master/docs/verification/2026-10-02-task8-paused.md`。

随后用户明确授权从 Task4 连续执行至 Task12：Task4 → Task5 → Task6 → Task7 → Task8 → Task9 → Task10 → Task11 → Task12。现从 Task4 开始；Task8 待4–7独立通过后恢复，ASR与核销仍延期。前述暂停为历史记录，不阻止当前Task4执行，也不授权跳过前置。每项实际证据见独立报告；开发自测不能当验收通过。

最新执行范围（2026-10-02）：用户要求“y验收完不要停 一直完成到task8”。当前按 Task5 → Task6 → Task7 → Task8 连续推进，每项独立通过后直接接续，不重复询问是否继续；Task8 收尾后结束本轮，Task9–12保留后续计划。此前Task4已独立通过并提交，不重做。用户的“y”同意当前九个AI聊天Frame作为实施基线、修复已记录的必要设计问题，以及最小公开事件身份透传；此确认不等于实现或独立验收通过。

## 工作区与边界

- 手机工作区：`C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai`，已有独立 worktree，分支 `feature/fandazi-ai`。
- 新 Agent 工作区：`D:/project/-Demo-master`，从 master 创建 `codex/group-buying-protocol-service` 分支，保留所有原有未提交改动。
- `C:/fdz-build` 是手机工作区 junction，不执行源码同步。
- 不回滚原有改动，不提交密钥；核销延期。UI 不采用 TDD，不以轻微尺寸和颜色差异阻塞验收。
- 验收必须由不同于实现者的独立 Agent 亲自运行适用测试；Android 改动须最新 APK 与 Maestro。

## 执行状态

| 任务 | 实现 | 开发自测 | 独立验收 | 提交 |
|---|---|---|---|---|
| Task1 协议补正 | 已实现 | 已通过 | 独立 PASS：44+34+106，首次 FAIL 已保留 | 手机 f4902b8 / Agent fad28f8（与 Task3 集成依赖一同提交） |
| Task3 新 Agent 接入 | 已实现：新服务/12类/DAG/工具桥接 | 最新 283 项离线、5 项真实双服务通过，首次失败已保留 | 独立 PASS：Python 356、Maven 72 | 手机 f4902b8 / Agent fad28f8 |
| Task2 页面数据准备 | 已实现：DECIMAL/UUID/UTC/页面字段/七夹具 | 自测通过；旧全量文案失败及首次图片超时保留证据 | Hume 独立 PASS：Maven70、离线352、真实Task2在线9/9、Task3在线5/5、40项HTTP/MySQL及10项Java；全量194/195非全绿 | 手机 d6c6441 / Agent 8265f08 |
| Task4 浏览推荐 | 已实现：真实接口、筛选排序、三Tab、不同套餐与新服务推荐 | 开发Maven213/213、Android147/147、Agent37通过/5跳过、真实在线3/3、四条Maestro退出0；原失败保留 | Goodall整体PASS（非阻塞关注）：独立Maven213、Android147、真实在线3、分类136、最新APK四条Maestro及六页Figma；同源隔离原H2测试1/1，原清单及34表无变化 | 手机8d1870d / Agent88c386e |
| Task5 公共文字聊天 | 已修复旧确认卡P1，公共聊天交付；ASR延期 | 修复64/64及最新APK开发回归，原失败保留 | Jason独立R1整体PASS：64项、11流程、11事件配对、游客禁发/全离线恢复；R0 FAIL历史保留 | 手机3a3bafe / Agent文档f5a33db；当前工作区与干净BASE编译边界见报告 |
| Task6 购买支付 | 已实现；B1隔离修复 | 开发与独立证据分列，首次失败保留 | Tesla独立R1需求/质量/运行整体PASS：217全量、真实购买/三页401/断网/过期/变价/历史守卫/Figma | 手机f66d9e5 / Agent c339237 |
| Task7 订单与券查询 | 已实现，B1/B2交付依赖最小补正；核销延期 | 开发原失败与恢复记录保留 | Lorentz独立R3需求/质量/运行/源码闭合PASS；隔离32/93/69，新跑4编译探针、16HTTP、110Maestro命令 | 手机93b97a2 / Agent d9fc396 |
| Task8 售后收尾 | 已完成；本轮结束 | 开发自测及旧失败完整保留 | Kant最终R2整体PASS、阻塞0，用户接受同一库存71→70事故例外；原FAIL不改。Android177/后端220/Agent380通过20跳过，R1隔离3/接口2通过 | 手机5476e4a / Agent d3cabc3；最终报告2026-10-03-task8-independent-final.md，计划已勾选 |
| Task9 意图评测 | 未开始 | 未运行本轮 | 未开始 | 未提交 |
| Task10 长期记忆 | 未开始 | 未运行本轮 | 未开始 | 未提交 |
| Task11 旧路径退役 | 未开始 | 未运行本轮 | 未开始 | 未提交 |
| Task12 最终验收 | 未开始 | 未运行本轮 | 未开始 | 未提交 |

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
- Task1/3 的手机范围提交包含原来尚未入库、但新工具桥接直接依赖的团购领域服务与 V8 评价绑定；保留现有实现，没有把延期核销或 Android 验收算作完成。用户原 UI、旧 Agent、无关后端注释改动和本地临时材料仍留工作区。
- Task2 应用真实迁移前已完成业务开发库 `--single-transaction` 备份，文件位于 Git 忽略的 `.runtime/backups/task2-before-20261001.sql`；没有清库、重置 Docker 卷或删除用户。
- Task4 独立验收整体PASS，证据在 `docs/verification/2026-10-02-task4-independent-acceptance.md`。一次性H2产物曾覆盖，独立撤回过宽阻塞并原样隔离复验；原失败、现存文件和差异保留，不伪造恢复、不修改Task2源码。完整审计容量和图片瞬态稳定性关注仍未修复，不由本次PASS消除。
- Task4 精确提交共享图片加载器及现行目录导航回归的必要依赖；来源仍注明既有工作区，不将购买、售后或Task8提前算通过。验收基于冻结工作区，最终Task12再核对全部已验收提交的集成部署。

- 当前执行方式以两仓AGENTS及计划3.4为准：主Agent开发/修复/文档/提交，每Task一名独立验收，失败复用原人；Task8子步骤不拆多个验收Agent。Task9–12本轮仅完善计划，ASR和核销延期。
- Task7 R0资源遗漏与R2后端GET遗漏均保留为首次FAIL，R3已亲跑精确索引隔离构建并关闭。完整报告为2026-10-02-task7-independent-acceptance.md；工作区APK54A8AAB1…、拟提交APKDD6441AD…分别验收，895冻结源/两索引/714原业务行/438私有文件未变。真实已有退款申请200及进度UI待Task8，不用H2或MVC结果代替线上覆盖。
- Task5只读核对报告 `docs/verification/2026-10-02-task5-development.md`：九个完整AI Frame缺明确确认，H当前越界需恢复确认结构；原始事件身份在Spring桥接部分丢失。已异步请求人类确认设计基线及最小透传范围，不将Task4–12连续执行授权解释为Frame确认或静默跨任务补正。未修改产品源码、未运行构建/测试/Maestro、未开始Task6；后续按Task5检查点继续。
- 上一条为确认前的历史核对状态。收到用户“y”后，Task5已恢复实现；批准范围为现有九Frame基线及H裁切修复、E既定原价折扣字段、D字段白名单、G真实目标语义和原eventId/sequence/runId/status最小透传。已释放设计等待，不声称修复或验收已经完成。
