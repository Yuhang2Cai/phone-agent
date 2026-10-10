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

## 当前台账

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
