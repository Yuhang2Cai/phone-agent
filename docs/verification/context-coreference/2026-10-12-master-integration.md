# 上下文管理与指代消除：双仓 master 合入验收

记录时间：2026-10-12 01:01（北京时间）。同一主 Agent 完成合并、验证和文档回写。用户在本次双仓合并询问后明确回复“合入”；此次授权对应下表两个来源，未沿用开发或历史合并授权。

| 仓库 | 合并前 master | 获批来源 | 本次合并提交 |
|---|---|---|---|
| Agent | `7487751815a1c8166271f3892ad10f8a35f69c1a` | `5d2591bd8236974f4a8226d36cdf8b6a5d16e0c6` | `3376fdd65d3cc8b97a07bbef926888d7f3202a07` |
| Android / Spring | `3e5096b951a752d2d93a87b75dd6f8d745dbddbc` | `52902ed01e27fbdb4dfab658edb0a2038510ffcb` | `9fe8c5be5e238c8de1f72710a5b175daed1c25c1` |

两仓均使用 `git merge --no-ff`，实际退出 0；每个合并提交的两个父节点分别为表内主分支起点和获批来源，合并树与获批来源树逐字节一致。没有冲突、部分挑拣或额外产品修改。后续仅精确提交本报告、进度和台账文字，产品源仍分别定位到 Agent `ea4ab99e8de6a5b437518b63e528b076ae2bcd20`、Phone `25feaa2f54d1e0958b27e9312abfc07d2d78292e`。

## 按步骤验收

| 步骤 | 验收动作与通过条件 | 实际结果与证据 |
|---|---|---|
| MI-00 | 变更前固定两仓来源/master；已跟踪内容干净，原未跟踪路径不被新增源覆盖 | 通过。Agent master 原状态干净；手机原 9,585 项未跟踪文件与合并后完整状态字节相同，无 stash/删除/覆盖。共享旧功能工作区的原 dirty 保留 |
| MI-01 | 保留合并边界；父节点精确匹配；合并树与批准来源一致 | 通过。上表两个合并提交及树经实际 Git 核对，两个 merge 退出 0 |
| MI-02 | 仅以精确合并提交 Git 归档构建/回归，真实退出 0，复用须核对产品输入 | 通过。Agent 1120 通过、42 显式跳过，compileall 退出 0；Spring 230 项、57 套件，0 失败/错误/跳过，package 退出 0；Android 与 R2 所有输入相同，复用 392 单测、lint 0 错误/710 警告及已验证 APK；详细条件见下文 |
| MI-03 | 验证结果先入报告，再回写计划/TODO/台账；提交只包含明确文档清单，原工作区内容保留 | 本文及安全 JSON 是本次回写依据；文档提交后的主分支号由最终 Git 核查及交付答复记录，不要求报告包含自己的提交号 |

合并前预检及每步输入/条件已先固定到任务 `.runtime/context-coreference/cc-master-preflight-r1.json`。主工作区未参与构建；精确归档 Agent 为 `C:/fdz-build/.runtime/cc-master-agent-r1`，Phone 为 `C:/fdz-build/.runtime/cc-master-phone-r1`。实际命令、退出码、耗时与安全结果保存在 [本次核验 JSON](cc-master-integration-r1.json)；原日志位于本任务 `.runtime/context-coreference/cc-master-*.log`，未提交框架私有输出。

## 实际回归和复用范围

- Agent：在精确归档设置任务原私有 MySQL 配置及 `MOBILE_PROJECT_ROOT` 指向本次 Phone 归档，亲跑 `python -m pytest tests -q --disable-warnings --tb=short --basetemp=<任务目录> --junitxml=<任务文件>`，1120 passed / 42 skipped / 1 warning，退出 0。跳过为显式真实服务/开发夹具等环境门控，不算本轮通过；对应真实链路按 CC-10 专门运行报告保留。`python -m compileall -q conversation intent dag service evaluation` 退出 0。
- Spring：在本次隔离归档 `backend` 亲跑 `mvn -q test`，57 个实际 XML 套件共 230 tests，failures/errors/skipped 均 0；随后 `mvn -q -DskipTests package` 退出 0，真实 JAR 已生成并记录 SHA256。`-DskipTests` 仅用于前一步已通过后的打包，不把跳过打包测试冒称新增测试通过。
- Android：本次 Phone 合并树相对已实跑 R2 Git 树 `50322f9b8a866e445e079272dca99c10cac28ed2` 只有分支台账差异；全部 Android/契约文件原字节另行核对相同，构建与依赖未变。按项目“同源码/配置/依赖/环境、未受影响证据可复用”规则，复用 R2 的 392 单测、lint 0 错误/710 警告、双 APK 构建及 R5 最新真实 Maestro。APK 重新算 SHA256：应用 `650fe418c67bad87816f95cc954058f20bc1ea0af44a4c8849c5f5502b6c4954`；配套测试 `405345bf092841a2171ca10361d2e7201dbfb7305a200b876b5c1c728ba601f1`，均保持原值。未新增 APK，因此无需新增替代包清理；前轮 7 包/966765683 字节清理及其他任务包保留原因不变。
- 真实链路：本次两个产品树与获批来源相同，R23/R24/R26 同产品字节关系已在 CC-11 记录。复用 R4 首次留出 117 场景、Full39/39、真实需绑定对象9/9，R5 最新八屏/4次唯一执行/6闭合/业务零增量，真实数量纠正/复合购买/重启/故障场景等各自的原冻结报告。未再次调用在线模型，未重写失败结果或冻结数据。
- 性能：复用 R7 固定条件 2700 样本的原三档/三批控制门槛通过结论，c5 单批 +92.18% 离群保留。在线 Full P95 22.14s、近期21.80s、B2 15.29s及缺失 usage 的限制保留，不声称新增整体提速或生产准确率保证。

## 五维结论与保留事项

需求符合性：已确认 CC-00 至 CC-11 范围保持通过；十三类四类事实、澄清及工具门控不随合并变化。代码质量：原定向/组合回归、签名门槛、中文注释和凭证扫描证据保持有效，合并没有新增产品代码；lint 710 警告未宣称全部为历史问题或已清零。实际运行：上述亲跑回归和明确版本的真实模型/Spring/UI证据分别记录。源码可构建性：精确主分支归档 Agent 编译与 Spring 打包成功，Android 输入相同复用构建。适用性能：仅声明原控制门槛通过及原在线实测，未测 Android 帧率。

生产四开关仍 false，留存仅用于合成范围；本次仅本地 Git 合并，未 push、部署或开启真实用户采集。ASR、核销、instrumentation 未运行、生产留存/流量验证及其他 AG 未完成事项不因合并改为通过。来源分支和工作树保留：任务私有环境、原始测量、截图和必要依赖仍在使用；未清理或归档这些证据工作区。

原报告：[CC-10](C:/Users/caid0/.codex/worktrees/agent-chat-master-20261010/-Demo-master/docs/verification/context-coreference/2026-10-11-cc10-acceptance.md)、[CC-11](C:/Users/caid0/.codex/worktrees/agent-chat-master-20261010/-Demo-master/docs/verification/context-coreference/2026-10-11-cc11-acceptance.md)。手机仓仅同步本集成报告/JSON及分支台账，其余原运行证据仍由 Agent 仓对应版本定位。
