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

| 分支 | 任务/基线 | 工作区 | 状态 | 验收记录 |
|---|---|---|---|---|
| `master` | 接收固定源 `0aad72a`，原主分支 `422381f` | `D:\project\phone-agent` | 已合并，合并提交 `ff6e111` | `docs/verification/2026-10-07-fandazi-master-integration.md` |
| `feature/fandazi-ai` | 固定交付 `0aad72a` | `D:\project\phone-agent\.worktrees\fandazi-ai` | 基线纳入后保留原未提交内容；不删除 | 同上及 `docs/long-login-technical.md` |
| `codex/intent-under1gb-pilot`，Agent 仓 | 模拟器五模型实验 | `D:\project\intent-under1gb-pilot` | 实验中，本次不合入 | Agent 仓实验报告 |

当前手机交付依赖的 Python Agent 开发基线为 `1e16d081b3d8aba3bae72c341af2f1149879406e`（`codex/group-buying-protocol-service`）。它尚未因本次手机仓合并而进入 Agent 仓 `master`；这不是跨仓库发布标签或线上部署记录。

ASR 人工验证、模型许可、arm64 真机性能和既有延期核销仍以原报告为准。主工作区原冲突计划和原未跟踪 `AGENTS.md` 定向保留到命名 stash，恢复标识和证据位置见本次报告。
