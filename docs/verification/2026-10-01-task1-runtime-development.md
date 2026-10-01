# Task1 运行时开发自测

状态：首次独立验收 FAIL，两项阻塞已修复，待独立复验。本文不是验收报告，不据此勾选任务完成。

## 范围

- Spring 请求使用 `schemaVersion=1.1`、`query` 与可信 `context`。
- `AgentSession` 持久化任务标识：等待输入/确认沿用 `runId`，每轮输入生成新 `traceId`，终态后新建任务。
- 网关在外部调用前冻结本轮 `runId`，较晚返回的旧响应不能更新同一会话的较新任务。
- 追加 `V9__persist_agent_run_identity.sql`，未修改已有迁移。
- NDJSON 请求与事件直接按打包的权威 Draft 7 YAML 校验，另核对任务链路与单调序号。
- `input_required` 和 `confirmation_required` 结束本轮传输但不完成任务；取消保留内部状态与公开 `nextAction`。
- 工具兼容字段集中归一为 `confirmationGrantId`、FAQ `question`；进度 `label/stepStatus`、追问 `question` 和公开确认摘要集中适配。
- NDJSON 事件审计写入失败记录链路后继续，不把已发生的业务结果改为失败。

## 测试过程

运行环境：IntelliJ JBR Java 21，Maven 编译目标 Java 17；Spring 集成测试使用真实 H2/Flyway 与隔离事务，HTTP 边界测试使用临时本地 HTTP 服务。未调用线上模型；未更改 Android UI，不把本轮结果作为 APK/视觉验收。

1. 请求、NDJSON 和任务状态初始测试共 13 项：7 项断言失败、3 项错误，复现旧版本、暂停流、固定 runId 等缺口；补正后 13/13 通过。
2. 工具字段 3 项初始全部断言失败，复现旧确认字段输出、退款新凭证未读取与 FAQ 查询字段丢失；集中适配后通过。
3. 权威载荷负例扩展后，HTTP 边界 9 项有 3 项失败：追问文字丢失、缺少暂停字段未拒绝、负数金额未拒绝；标准契约校验后通过。
4. 组合执行暴露受控 YAML 超过 SnakeYAML 默认 50 别名上限；运行时和契约测试改为安全构造器与有限 500 上限，没有关闭重复键或膨胀保护。
5. 交错响应回归初始 3 项中 1 项断言失败，证明旧请求能覆盖较新任务；冻结本轮身份后交给独立验收 Agent 重跑组合，最终项数以独立报告为准。

最终命令（在手机 `backend/` 执行，预先设置 `JAVA_HOME` 与 UTF-8 JVM 参数）：

```powershell
& 'D:/software/IntelliJ IDEA 2025.2.4/plugins/maven/lib/maven3/bin/mvn.cmd' '-Dtest=AgentProtocolBoundaryTest,AgentToolFieldBoundaryTest,AgentGatewayV1ContractTest,AgentRunIdentityTest,AgentContractSchemaTest,AgentGatewayTest' test
```

实测 2026-10-01 21:37:35：29 项，失败 0、错误 0、跳过 0，BUILD SUCCESS。

| 测试类 | 项数 | 结果 |
|---|---:|---|
| AgentContractSchemaTest | 5 | 通过 |
| AgentGatewayTest | 6 | 通过 |
| AgentGatewayV1ContractTest | 4 | 通过 |
| AgentProtocolBoundaryTest | 9 | 通过 |
| AgentRunIdentityTest | 2 | 通过 |
| AgentToolFieldBoundaryTest | 3 | 通过 |

新 Agent 仓库另执行：

```powershell
$env:MOBILE_PROJECT_ROOT = 'C:/Users/caid0/Documents/ChatGPT/手机agent/.worktrees/fandazi-ai'
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -m pytest tests/test_cross_repo_contracts.py -q
```

实测：34 项通过，包含与当前手机权威文件逐字节及摘要核对。

## 边界

### 首次独立验收后的修复

验收者实际执行核心组合 30/30、跨仓 34/34 和额外负例后发现两项阻塞，完整原始证据保留在 `2026-10-01-task1-independent-acceptance.md`：

- 合法 `tool_result.error=null` 在 `Collectors.toMap` 转换时产生空指针。已改为保留 JSON 空值的结构化复制，并同步修正事件实体的入库与读取复制，避免合法事件审计丢失。
- 内部 `prepare_refund` 的确认凭证经 Spring 工具审计泄漏到公开 SSE。已增加集中递归公开载荷过滤：新事件入库前脱敏，历史事件经公开 DTO 出口再次脱敏；内部工具响应仍保留受控工作流所需的凭证，没有删除内部授权机制。

本轮未更改独立验收用例或抹去失败日志。最终独立复验 PASS：Maven 44/44、跨仓 34/34、独立载荷 106/106；详见独立报告。两项首次阻塞均已修复并亲自复验。

Task1 验收后已进入 Task3：新 Python 服务入口、版本化工具路由、实际进程来源、完整公开卡片的领域重查与工具工作流在 Task3 对契约落地。本报告不宣称新服务已接通、真实 MySQL 已验收或 Task8 聊天页面已通过。

独立验收必须重新执行测试、核查权威副本与兼容边界；发现阻塞问题后修复并复验，不能沿用上述开发自测作为 PASS。
