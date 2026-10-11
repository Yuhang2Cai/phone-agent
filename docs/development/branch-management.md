# Git 分支管理规范与台账

本规范由用户于 2026-10-07 确认。手机项目与 Python Agent 是两个独立 Git 仓库，版本组合必须分别登记。

## 分支职责

| 仓库 | 稳定交付分支 | 过渡开发分支 |
|---|---|---|
| `phone-agent`：Android 与 Java 后端 | `master` | `feature/fandazi-ai` |
| `-Demo-master`：Python Agent 与意图实验 | `master`，待单独整理 | `codex/group-buying-protocol-service` |

统一用“主分支”指 `master`。过渡分支完成已验收基线同步后，不再无限追加新任务。Git 合并只表示纳入版本历史，不能代替具体功能和性能验收。

## 开发规则

1. 分支围绕一个明确目标，使用 `codex/feat-*`、`codex/fix-*`、`codex/refactor-*`、`codex/perf-*`、`codex/exp-*`、`codex/docs-*`。旧分支完成后逐步退出，不为命名强制改名。
2. 通常从已确认的最新 `master` 建立短期分支；依赖未合并功能时，记录依赖分支和完整基线提交。父分支继承内容不算本任务增量。
3. 开工登记仓库、分支、绝对工作区、起始提交、范围、验收标准、原未提交改动及来源、共享设备/服务/端口。
4. 并行任务分别使用独立 worktree，不在同一目录同时修改。模拟器、包名、ADB 端口、服务和输出目录登记归属；性能报告检查共享宿主干扰。
5. 正式测量冻结源码、APK、模型和配置，其他任务不能更改测量设备或服务。
6. 提交前逐项检查暂存区，只提交明确清单；原改动、私有配置、密钥、真实用户数据、模型权重和构建产物不混入提交。
7. 同一个主 Agent 按步骤完成开发、自测、冻结、验收、修复与精确提交。每个必需步骤有实际证据；未运行、跳过、受阻及性能未测不能写通过。
8. 合并前在隔离快照验证精确拟交付树可构建，并执行适用回归。默认保留合并边界，记录来源提交、任务及验收报告；部分移植须重新验证组合行为。
9. 跨仓库交付登记 Android/Java 后端与 Python Agent 对应提交；只合并一个仓库不代表整套系统交付完成。
10. 合并后核对主分支适用构建和回归，更新台账。存在未提交内容、运行中实验或未保存证据时，不清理分支和工作区。

状态统一为：开发中、待验收、已验收待合并、已合并、已归档。既有明确允许仅实施/编译的功能，必须单列未验收范围，不因合并成为完整通过。

## AG03执行台账（2026-10-12）

Phone从master `0b057716667b183677682e225153b8c513df1ca5`建立`codex/ag03-task-lifecycle`，工作区`D:/project/phone-agent/.worktrees/codex/ag03-task-lifecycle`；对应Agent master `58a3314973c611e1482612a1863b5f9d8352afe8`及同名方案分支。用户授权TL-00至TL-11依r2顺序实施，合入前另问。TL-00夹具/基线按步骤通过：Agent夹具38及跨仓34、隔离编译0，Phone资源镜像及隔离package0；没有真实调度/模型/UI/性能通过声明。原Phone未跟踪资料不移动，共享服务不切换。详见[TL-00报告](../verification/ag03-task-lifecycle/2026-10-12-tl-00-acceptance.md)。状态：开发中。

## 已完成台账：上下文与指代消除已合入（2026-10-12 01:01）

| 仓库 | 获批来源 | master合并提交 | 状态与验收 |
|---|---|---|---|
| Agent | `5d2591bd8236974f4a8226d36cdf8b6a5d16e0c6` | `3376fdd65d3cc8b97a07bbef926888d7f3202a07` | 已本地合并；合并后隔离1120通过/42显式跳过/编译0 |
| Android/Spring | `52902ed01e27fbdb4dfab658edb0a2038510ffcb` | `9fe8c5be5e238c8de1f72710a5b175daed1c25c1` | 已本地合并；合并后隔离Spring230全通过/package0，Android同输入复用392/lint/最新UI |

本次用户明确回复“合入”。两仓使用--no-ff，合并树与获批来源一致；原Phone 9,585项未跟踪完整保留，未stash/覆盖。精确回写文档追加提交不改变产品。来源分支、证据工作树及私有配置保留，未push/部署，生产四开关仍false。详见[本次合入报告](../verification/context-coreference/2026-10-12-master-integration.md)，历史状态与未验收范围继续按原事实保留。

## 历史台账（此前交付）

2026-10-10 上下文重构开工：`codex/context-coreference` 从手机 master
`3e5096b951a752d2d93a87b75dd6f8d745dbddbc` 创建，工作区
`D:/project/phone-agent/.worktrees/codex/context-coreference`；对应 Agent 从
`7487751815a1c8166271f3892ad10f8a35f69c1a` 创建同名分支，目录
`C:/Users/caid0/.codex/worktrees/context-coreference/-Demo-master`。用户授权 CC-00
至 CC-11 顺序开发、自测及同一主 Agent 按步骤验收，合并前另行询问。
原主工作区未跟踪资料不移动；共享 8080/8091 服务不切换。本任务 MySQL
容器 `fandazi-context-20261010-mysql` 仅监听 127.0.0.1:13315，合成库
`dianping_intent`，随机凭据仅存忽略目录。状态：开发中。

| 分支 | 任务/基线 | 工作区 | 状态 | 验收记录 |
|---|---|---|---|---|
| `master`，手机仓 | B2 固定来源 `55ea95a`，合并前 `11e7c83`；既有聊天/门店修复保留 | `D:\project\phone-agent` | 已合并，合并提交 `c4c3b16`；本地 Git 完成，共享部署未切换 | `docs/verification/intent13-b2-master/2026-10-10-phone-integration-report.md`；历史基线报告仍保留 |
| `feature/fandazi-ai` | 固定交付 `4a26636`，本轮四个修复提交 | `D:\project\phone-agent\.worktrees\fandazi-ai` | 已提交修复纳入 master；原未提交内容与暂存保留，不删除 | `docs/verification/2026-10-10-chat-store-master-integration.md` 及各任务 `.scratch/*/acceptance/` 报告 |
| `master`，Agent 仓 | B2 固定来源 `81a0874`，合并前 `2904033a`；既有聊天/门店修复保留 | `C:\Users\caid0\.codex\worktrees\agent-chat-master-20261010\-Demo-master` | 已合并，合并提交 `73ce5c6`；本地 Git 完成，共享部署未切换 | Agent 仓 `docs/verification/intent13-b2-master/2026-10-10-integration-report.md` |
| `codex/recyclerview-performance-20261007`，Agent 仓 | 固定交付 `0dbcc35`；两个业务修复及既有性能实验材料 | `C:\Users\caid0\.codex\worktrees\recyclerview-performance\-Demo-master` | 已合并；来源分支保留 | `docs/verification/2026-10-10-chat-store-master-integration.md`；实验材料没有应用到手机产品 |
| `codex/intent-under1gb-pilot`，Agent 仓 | 模拟器五模型实验 | `D:\project\intent-under1gb-pilot` | 实验中，本次不合入 | Agent 仓实验报告 |
| `codex/refactor-intent13-b2-integration`，双仓各一支 | B2 十三类单标签及 Android/Spring v2；Agent 基于 `2904033a`，手机基于 `11e7c83` | Agent `D:\project\intent13-b2-agent`；手机 `D:\project\phone-agent\.worktrees\codex\intent13-b2-integration` | 已合并；批准源 Agent `81a0874`、手机 `55ea95a`，合并分别 `73ce5c6`、`c4c3b16`；来源分支和证据保留，共享部署未切换 | 两仓 `docs/verification/intent13-b2-master/`，精确快照 Agent `fc24ff8e`、手机 `d4207bef` |

2026-10-07 手机历史交付依赖 Agent `1e16d081b3d8aba3bae72c341af2f1149879406e`，当时的单仓报告仍按原事实保留。2026-10-10 此前聊天/门店集成获用户明确授权双仓一起合入：当次手机固定来源为 `4a2663676847b8e6b59f0b08890667b113b495ad`，对应 Agent 固定来源为 `0dbcc359649234693da65538f43edd6bfe70be96`；两者分别已进入手机 `master` 的 `a106287e37940269258c874cdd4fbd4033b1af64` 和 Agent `master` 的 `d6dc57f97682d9cb3d8217db9401ebce31982c2a`。完整回归和实际合并树一致性见本轮报告。这是本地 Git 集成记录，不是线上部署。

ASR 人工验证、模型许可、arm64 真机性能和既有延期核销仍以原报告为准。主工作区原冲突计划和原未跟踪 `AGENTS.md` 定向保留到命名 stash，恢复标识和证据位置见本次报告。

2026-10-10 B2 本次合并授权为用户明确回复“同意合入”。两仓 master 合并树均与批准来源一致；合并后 Agent 610 通过/20 跳过、compileall 通过，Spring 202 项全通过及 package 成功。Android 与隔离快照产品无差异，复用 387 单测、lint、最终 APK 与 Maestro 证据；手机原未跟踪 9585 项保留。旧任务 6 个未收尾，部署不随 Git 合并自动切换。后续新分支仍统一从对应仓库最新 master 创建并记录完整起点。


2026-10-11 CC-10/CC-11检查点（2026-10-11 23:44）：同一主Agent在既有`codex/context-coreference`双仓开发；当前Phone HEAD `cf772fba9f5d597c41f098d330a7ee922f4d42bc`、Agent HEAD `00c242350127cf7d984931c35aefb098ea0d1506`，两仓master相对开工记录未变。MySQL实际验收迁至本任务native `127.0.0.1:13316`，原13315容器记录保留为历史，不重新初始化数据库。独占端口完整8083/8093、近期8085/8095、B2 8084/8094；真实运行后均已停止，专用AVD5580已关闭；共享8080/8091未改变。

Phone R2拟提交Git树`50322f9b8a866e445e079272dca99c10cac28ed2`的17文件增量经Maven230、Android392/lint0错误710警告及最新APK双构建通过；Agent R23 Git树`7e91632d2b1dad9d2cd68d08f72cbf53d27020b9`隔离1119通过42跳过/编译0，真实事务2通过，R4 Full39/39、最新Maestro八屏/4唯一执行/6轮及业务零增量通过。最终仅台账/报告与实验计量测试再闭合；原未跟踪ASR模型不纳入提交。最新APK SHA`650fe418c67bad87816f95cc954058f20bc1ea0af44a4c8849c5f5502b6c4954`，清理7个替代/临时包966765683字节，证据见任务.runtime及Agent CC-10/CC-11验收报告。状态：CC-10已按步骤验收、CC-11最终提交待完成；本次合入master仍须用户明确同意。


2026-10-11 23:58 CC-11结案：Phone产品源提交`25feaa2f54d1e0958b27e9312abfc07d2d78292e`，对应Agent产品源提交`ea4ab99e8de6a5b437518b63e528b076ae2bcd20`；源树分别`d88a89e0c4e9a97579ce8f6c5a5d677164d143b4`、`5aeee2f698c4ac4c4d44d986988d17d0c2ac225f`。状态：已按步骤验收、已精确提交、待合并。1120/42显式跳过/编译0、Spring230、Android392/lint0错误710警告、R4 Full39/39、最新APK八屏/4唯一执行/6闭合及业务增量0。最终仅文档回写提交，不改变产品或APK字节。两仓master仍为开工基线，本次合并需用户明确同意；未push/部署，原ASR未跟踪资源保留。

2026-10-12 AG03 TL-01：权威2.2及工具2.0、严格校验/资源打包、旧兼容组件验收完成；Python97/Java26、隔离编译/package通过。新执行仍关闭；真实目录/控制/模型/UI/性能待后续TL。报告`docs/verification/ag03-task-lifecycle/2026-10-12-tl-01-acceptance.md`，未合入master。

2026-10-12 AG03 TL-02：持久目录/task—run、唯一前台/容量预检/归属/版本按步骤验收完成。隔离Python91/H2 30/真实MySQL18/编译package通过；8086同JAR两次启动真实JWT回读一致且业务增量0。13326任务库保留供后续TL，8086/8096已释放；报告见`docs/verification/ag03-task-lifecycle/2026-10-12-tl-02-acceptance.md`。控制交接、组合模型/UI/性能尚未完成，未合入master。
