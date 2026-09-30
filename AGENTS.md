## Agent skills

### Issue tracker

本项目的问题与规格说明使用 Markdown 文件管理，存放在 `.scratch/<feature>/` 下。详见 `docs/agents/issue-tracker.md`。

### Triage labels

使用默认标签：`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`。详见 `docs/agents/triage-labels.md`。

### Domain docs

使用 single-context 布局：根目录的 `CONTEXT.md` 与 `docs/adr/`。详见 `docs/agents/domain.md`。

## Grilling 持久化约束

进行 `grilling` 或 `grill-with-docs` 时，问题按累计编号记录。累计达到第 30 个问题后，必须先将当前前 30 个问题、用户答案、已确认决策和未决分支写入 `.scratch/<feature>/grilling/round-<NN>.md`，并确认文件已保存，再继续提出第 31 个问题。后续每增加 30 个问题，重复一次。记录应使用中文，保留原问题编号，确保新会话可以从记录继续而不依赖当前上下文。

## Android Maestro 验收约束

每次 Android implement 或 Maestro 验收开始前，先从模拟器卸载旧版 `com.fandazi.app`，再安装本次构建的最新 debug APK；同时卸载模拟器中用于占用空间或产生干扰的薄荷音乐应用 `com.dirror.music`。确认旧包已移除后，才开始新的流程。

每次执行 Android `implement` ticket，都必须为该 ticket 维护对应的 Maestro 流程，并在实现完成后使用最新 debug APK 连接 Android 模拟器实际运行一遍。验收必须覆盖该 ticket 的完整用户操作路径；只有模拟器流程执行成功，才能将 ticket 标记为完成。若模拟器、服务或依赖不可用，必须记录具体阻塞原因，不得以“已生成 Maestro YAML”替代运行结果。所有 Android ticket 完成后，再运行一次全部 Maestro 流程做回归。

## 代码注释语言

所有新增或修改的代码注释、文档字符串和配置注释均使用中文。引用代码标识符、接口路径或必要的技术专有名词时保留原文。注释应说明职责、关键约束或不直观的原因，避免逐行复述代码。

## Android 业务数据来源

- Android 端禁止写死门店、套餐、菜品、评价、订单、优惠券、价格、库存、状态和用户账户等业务数据。
- 页面展示、详情切换、排序筛选和聊天卡片必须使用 Spring Boot 后端接口返回的数据；接口失败时展示明确的加载失败或重试状态，不得使用静态业务数据兜底。
- 允许写死的内容仅限底部 Tab 等纯 UI 结构、无业务含义的布局常量和设计稿规定的静态文案；任何带有 `publicId`、金额、库存或业务状态的数据都必须来自接口。
