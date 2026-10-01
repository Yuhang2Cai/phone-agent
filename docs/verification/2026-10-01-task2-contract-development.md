# Task2 页面字段契约开发记录

状态：开发自测通过，Task2 领域实现/真实迁移/独立验收未完成，不勾选 Task2 验收。

## 范围与兼容

为权威 `contracts/` 和新 Agent 受控副本补充券支付分摊金额、创建时间、订单卡真实名称、缺图及替代文本、退款完整关联摘要。仅增加可选字段，1.1 已有载荷保持兼容；没有使用宽泛 `additionalProperties=true` 绕过出口。

待支付券 `amountCents` 允许 null，表示没有已付分摊，不使用套餐现价或虚构零元退款额。图片允许 null，URL 有 URI 约束；替代文本不由 Android 生成假菜名。退款最终状态按既有实体和工具契约 REQUESTED（待处理）同步修正文档，不改业务枚举，不冒称到账。

## 实际测试

新增 `tests/test_task2_page_data_contract.py`，修改前实际 **6 FAIL**，捕获未知字段与缺少金额/时间/图片规则。同步权威文件、原始字节副本及双摘要后执行：

```powershell
$env:MOBILE_PROJECT_ROOT='C:/fdz-build'
C:/Users/caid0/anaconda3/envs/pinnT/python.exe -X utf8 -m pytest tests/test_task2_page_data_contract.py tests/test_cross_repo_contracts.py tests/test_task3_tool_preparation_contract.py tests/test_agent_service_api.py tests/test_agent_service_reads.py -q --tb=short --basetemp=.scratch/task2-contract-green
```

实际 **54/54 PASS，6.76 秒，exit 0**。这是离线字段/解析/服务回归，不是真实后端返回字段已经验收。

当前 SHA-256：工具 `56d52e44c6e89998eb26c2e43d43e41fa9fce82c2434347d6392b5b01253dcd4`，协议 `838542d06b2ef05bf3e2ba125d3cab9ba28533359883334ecf002683544d7019`。Task3 原摘要作为历史保留，不覆盖其独立报告。

扩大到新 Agent 全部当前离线测试（显式排除两个 Task3 live 文件和 Task2 live 文件），使用 `--basetemp=.scratch/task2-offline-all`：实际 **352/352 PASS，14.41 秒，exit 0**。没有把被排除的在线检查计入通过数量。

新增 `tests/test_task2_page_data_live.py`，当前旧后端上实际 **2 FAIL**：目录 `store-001` 非 UUID、订单创建时间无时区。测试还覆盖真实图片下载、未支付券空金额、支付分摊合计、退款摘要和提交后资格刷新。原红灯保留；Java 纳秒时间用标准解析器支持，断言检查真实 UTC 偏移，不因 Python 3.10 精度限制误判新返回。等待领域实现和真实迁移后运行 GREEN，目前不能声明在线数据通过。

真实业务库应用新迁移前已使用 mysqldump --single-transaction 备份，备份 462264 字节，位于 `.runtime/backups/task2-before-20261001.sql` 并经 git check-ignore 确认，不提交密码或数据库原始备份。

## 真实库升级前只读核对

通过共享环境读取容器凭据，仅输出计数及金额合计，未输出用户身份或密钥，未应用迁移。实际最高成功 Flyway 版本为 11（按数字比较，不能用字符串 MAX 得到 9）。

| 项目 | 升级前实际值 |
|---|---:|
| 订单 / 券 / 退款申请 | 25 / 29 / 6 |
| 门店 / 套餐 | 30 / 120 |
| 报价 / 确认凭证 | 21 / 45 |
| 订单总金额合计（分） | 248200 |
| 非法订单数量或券数量不一致 | 0 |
| 缺少已支付事实的历史退款 | 0 |
| 同券重复退款申请分组 | 0 |

迁移保留旧整数分列作为历史材料，实体切换到 DECIMAL 权威列；旧列不用于新金额计算。破坏性旧列清理不属于 Task2，留到 Task11 的备份恢复验收之后。此处只读检查不是迁移已通过的结论，升级后须再核对历史行、关联与精确金额。

已将不含建库/切库语句的真实 V11 dump 恢复到隔离库 `task2_upgrade_b7b37c05148a`，恢复实测 exit 0：25 笔订单、248200 分合计、29 张券、6 条退款、Flyway 11，与原库核对一致。原库未被恢复或替换；测试副本只在本机 MySQL 内，原始 dump 不入 Git。下一步先在副本应用新迁移，确认历史数据后再升级开发原库，目前尚未执行。

## MySQL 副本与原库实际升级

已执行 `scripts/Test-Task2UpgradeCopy.ps1 -DatabaseName task2_upgrade_b7b37c05148a`。首轮组合命令被执行策略拒绝，未执行；改为限定隔离库、禁用种子/夹具、隔离端口的可审查脚本后执行。副本 Flyway 11→13、Hibernate validate、目录 HTTP 200 均实测通过。

副本保持 25/29/6 订单/券/退款、legacy 总额 248200 分、新总额 2482.00 元；金额转换差异 0、券分摊差异 0，旧活动确认凭证 0，稳定别名 215。首轮进程清理因 Maven 使用参数文件而未匹配工作区文本，记录警告后按已确认 PID/父进程清理；脚本已改为本轮 launcher 父子链校验。再次启动副本健康 200、exit 0，端口 8082 已释放，未重复迁移或生成种子数据。

副本通过后重启实际 Spring 与新 Agent，Spring PID 42560、Agent PID 48108，8080/8091 健康 200；新 Agent 来源仍为 `D:/project/-Demo-master`、`service.app:create_app`。Spring 明确 development profile 与夹具开关，随机凭据仅在 `.runtime/task2-fixtures.json`。原业务库实际 V13：旧 25 笔订单合计 248200 分/2482.00 元、旧券 29/退款 6 保留，历史金额与实付分摊差异均为 0。

### 在线首轮失败与定位

`RUN_TASK2_LIVE=1` 执行页面数据与七夹具，共 9 项：1 PASS、8 FAIL（6.72 秒、exit 1）。真实目录 UUID、餐图/缺图及各场景正向状态已实际进入检查，不能把全组声明通过。

- 1 项：退款首次提交返回纳秒时间、MySQL TIMESTAMP(6) 回读微秒，其他 13 字段完全一致。开发修复统一生成微秒精度，待重启后复验，不删除时间一致性断言。
- 7 项：普通业务 API 一直用 400 DOMAIN_ERROR 对本人查询不到的资源拒绝，测试误写为 403/404。按已有契约改成准确断言 400 且完整载荷仅 `{code: DOMAIN_ERROR, error: 团购订单不存在}`，不能把任意 400 或携带他人摘要算通过。只读七夹具复跑实际 **7/7 PASS、2.02 秒、exit 0**。

以上仍是开发自测，Task2 尚待全部线上复验与独立验收。

时间精度修复在 `BusinessTime.now` 创建边界统一截断到微秒，新增 100 次取样回归；不删除原时间相等断言，不修改已执行迁移。新增单测修改前 3 项中 1 FAIL（纳秒尾数实际复现）。实现 Agent 的首轮 GREEN 命令在交接时被终止，日志不完整，不算通过；控制端完整重跑 `BusinessTimeTest,RefundEligibilityTest,GroupOrderSummaryContractTest` 实际 **13/13 PASS、12.324 秒、exit 0**。

重启最新 Spring（PID 47616，Agent PID 48108 无需重启）后原两份在线测试完整复跑 **9/9 PASS、15.17 秒、exit 0**，退款首返与 MySQL 回读时间字符串完全一致。已启动新的独立 Task2 验收 Agent，尚待其结论。

扩大七夹具负面检查：从真实 `/api/refunds/prepare` 拒绝未支付、已申请、已核销、过期券，不仅断言页面布尔值；仍不提交评价/退款或执行核销。实际 **7/7 PASS、2.12 秒、exit 0**。

新版字段与 UUID 下重跑 Task3 双服务闭环：第一次因测试进程未加载 `AGENT_INTERNAL_TOKEN`，1 个内部鉴权测试 KeyError、4 个公开业务测试 PASS；源共享环境后原断言复跑 **5/5 PASS、26.39 秒、exit 0**。覆盖服务身份、JWT、NDJSON、问候/空订单、投诉与退款显式确认、真实 Planner/DAG，不计为意图准确率实验。
