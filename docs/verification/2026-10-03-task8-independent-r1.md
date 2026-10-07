# Task8 独立定向 R1 复验

## 结论

**技术修复复验 PASS；历史事故处置待用户确认。T8-IND-01 不关闭，Task8 综合结论仍为 FAIL。**

时间：2026-10-03，Asia/Shanghai。本次由原唯一独立验收者亲跑，未另派 Agent、未提交、未勾选任务、未修改产品或计划。主 Agent 尚未收到用户对保留事故订单/库存作为明确审计例外的确认；本报告不是该确认，也不授予自行接受新基线的权限。

| 维度 | 本次实际结论 |
|---|---|
| 需求符合性 | 修复版准备器只购买新独占套餐，既有行保护符合 R1 要求；原库存事故未获处置确认 |
| 代码质量 | 反射复制字段、随机身份、库存2、报价身份断言、quoteId、finally双身份限定下架，未发现本次阻塞 |
| 实际运行 | 3 个离线守卫、2 个真实退款/投诉测试全部通过；两库全部既有行无修改/删除 |
| 源码闭合 | 新索引测试源及同目录动态导入闭合；产品 blob 与前轮完全相同，复用未受影响证据 |

## 版本与源闭合

| 仓库 | HEAD（未提交新版本） | R1 冻结索引树 |
|---|---|---|
| Phone | f2f08035ed23676c88269d083b3536e200741ac0 | b948a281232382a07271237b8d32d39b6f94a939 |
| Agent | 9fdc5e16146423120bab18a722090643fa0d3203 | 6c8c1992cec8d3d875cf057cd93b25bd5f8f9d12 |

与前轮 Phone `1cc8fff9b0077672fd87d26a460a0fda5b7b7996`、Agent `0d29e30b066dd0b33d8cf244fa75d71ee3d5995a` 比较：全部差异只有文档目录内的两测试和四份文档。Phone 735、Agent 205 个非 docs 路径的 blob 未变；原冻结工作文件中的非 docs 部分 Phone 78、Agent 5 份摘要也未变。运行结束再次确认两新索引树及测试工作文件不变。

独立导出目录：`C:/fdz-build/.runtime/task8-independent-final/r1/{phone,-Demo-master}`。实际运行的是本次新索引导出的测试，不是主 Agent 工作区自测结果。

- `test_live_write_boundaries.py` blob：`8f866ebb10e6ed3f22e27c15e6108fc66fefc6ff`。
- `test_boundary_fixture_isolation.py` blob：`e9c753f3e9492500428c92fc4ccf5ec83ad6a8e6`；同目录 importlib 加载的辅助在快照中存在。
- Git archive 导出为 CRLF、工作文件为 LF，首次按原始 SHA 比较失败，**发生在任何数据库测试前**。已保留本报告记录及导出原文件，按 Git 清洗后的 blob 身份与换行规范化文本复核相同；续检只读已有导出，不重新归档、不修改源码、不放宽业务断言。脚本入口 `Prepare-R1.ps1 -VerifyExistingExport`。
- 旧失败副本 `2026-10-02-task8-independent-acceptance-evidence/test_live_write_boundaries_current.py` 保持原样，SHA256 `42053FDC6451D6C26D494F9A56B500CDB319E9659A7E321904812C81FFFEC3E0`；本次没有运行它。

证据：[source-closure.json](2026-10-03-task8-independent-r1-evidence/source-closure.json)。

## 亲跑结果

| 实际运行 | 结果 | 私有日志（r1 根下） |
|---|---|---|
| `test_boundary_fixture_isolation.py` | **3 passed，0.50s**，0失败/跳过 | guards.log、guards.xml |
| 修复版 `test_live_write_boundaries.py` | **2 passed，2.71s**，0失败/跳过 | live.log、live.xml |
| 两库全部表运行前/后既有行比对 | **PASS**，改动0、删除0 | all-rows-before.json、all-rows-after.json、all-rows-difference.json |
| 原事故前事实 guard | **FAIL，exit 1**，仍为 `deal_packages 原事实变化` | historical-guard.log、historical-difference.json |

三个守卫分别覆盖克隆正常结束、模拟请求异常后 finally 下架、空来源拒绝。它们使用内存 SQLite，不冒充 MySQL 业务测试。主 Agent 所述 RED3→GREEN3 是开发历史；本验收独立实测当前索引 GREEN3，没有重跑旧危险副本验证 RED。

两个真实测试实际走本机 Spring/MySQL 公共 HTTP 接口，分别验证退款准备零写入、非法原因/错误会话不消费授权、合法上限原因唯一写入、进度重入、他人/游客拒绝；投诉准备零工单、非法长度/空白/错误会话拒绝、合法正文唯一 OPEN、UTC 时间及重复提交拒绝。它们不经过 Agent 聊天，本次没有冒称新跑双服务聊天端到端。

新套餐库存由准备器设为2，实际购买1份后为1，已由 finally 按随机 public_id 与唯一 title 下架；新订单引用这条新套餐，退款记录保留。没有购买旧目录套餐、没有回填共享库存、没有清理订单/退款历史。

## 全部既有行保护

本轮 before 是**R1 增量测量起点**，不是历史原事实的新基线。读取两库 information_schema 中全部基础表，使用只读一致性事务抓取全部列；有主键按完整主键比较，无主键按完整行多重集比较。运行后验证所有运行前存在的行未变、未删，新增行单独记账。

| 数据库 | 表数 | 运行前既有行 | 修改 | 删除 | 本轮新增 |
|---|---:|---:|---:|---:|---:|
| fandazi_groupbuy_dev | 34 | 6988 | 0 | 0 | 12 |
| dianping_intent | 2 | 424 | 0 | 0 | 0 |

业务库新增：账户3、投诉1、确认凭证3、套餐1、订单1、报价1、退款1、券1。新增套餐/订单/退款关联审计通过，全部完整行与登录名只留 `.runtime`。表级摘要：[rows-before-summary.json](2026-10-03-task8-independent-r1-evidence/rows-before-summary.json)、[rows-after-summary.json](2026-10-03-task8-independent-r1-evidence/rows-after-summary.json)。

之后再次对**原** `.runtime/task8-resumed/facts-start.json` 比较：仍只有一条旧 `deal_packages.inventory` **71→70**，其他历史字段和行无变化。原 guard 也亲跑并失败。这证明 R1 没有扩大损害，**不证明事故已处理，也不把历史 FAIL 改为 PASS**。775 份原私有 JSON 哈希全部不变。

## 实际命令

下面命令均由独立验收者执行。Python 使用 `C:/Users/caid0/anaconda3/envs/pinnT/python.exe -X utf8`；真实测试/行采集先 dot-source `C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1`，不输出环境秘密。

```powershell
# PUB = C:/fdz-build/docs/verification/2026-10-03-task8-independent-r1-evidence
# RAW = C:/fdz-build/.runtime/task8-independent-final/r1
& "$PUB/Prepare-R1.ps1"
& "$PUB/Prepare-R1.ps1" -VerifyExistingExport
python "$PUB/check_all_rows.py" before
# 以下两条 cwd 为 RAW/phone，PYTHONDONTWRITEBYTECODE=1；全部输出重定向RAW对应日志
python -m pytest docs/verification/task8-service-evidence/test_boundary_fixture_isolation.py -q -p no:cacheprovider --basetemp "$RAW/pytest-guards" --junitxml="$RAW/guards.xml"
python -m pytest docs/verification/task8-service-evidence/test_live_write_boundaries.py -q -p no:cacheprovider --basetemp "$RAW/pytest-live" --junitxml="$RAW/live.xml"
python "$PUB/check_all_rows.py" after
python C:/fdz-build/docs/verification/task8-resumed-evidence/guard_facts.py independent-r1-historical-check
& "$PUB/Finalize-R1.ps1"
python "$PUB/scan_r1_public.py"
```

## 复用与未运行

依照冻结产品 blob 相同、工作产品摘要相同及环境服务未变的证据，复用 [上一轮独立综合报告](2026-10-02-task8-independent-acceptance.md) 中 Android177、Backend220、Agent380、MySQL状态机10、未受影响真实接口、最新 APK Maestro 与 Figma 逐屏结果。APK SHA256仍为 `5417EF91BC9D2544DC49B24EF35186E83448A98F56555840B4D121AD8756511C`。

本次不重跑构建、不卸载/安装 APK、不跑 Maestro/Figma、不造新券、不改变旧券日期、不追加场景。前轮10月2日/10月3日证据日期保持原样；原离线20项跳过与模拟工具证据层级沿原报告，不升级为本次实测。保留首次产品功能测试、夹具事故、修复与本次复验，不覆盖任何失败记录。

## 保密与释放

所有凭据、完整行、原始请求/pytest日志置于 `.runtime/task8-independent-final/r1/`；公开只放计数、摘要和脚本。最终保密检查结果见本目录 `secret-scan.json`，扫描两仓暂存文本与本次公开文本，不输出秘密。

本次没有重启服务、开启503/图片故障或改变网络。收尾监听归属及健康正常：8080 PID34804、8091 PID47544、8768 PID2464；Agent health ok、原 sourceRoot/入口不变，图片 blocked 不存在。设备 wifi=1、data=1、airplane=disabled、proxy=null，安装/登录状态未动。完整摘要：[final-checks.json](2026-10-03-task8-independent-r1-evidence/final-checks.json)。

保密检查已完成：两仓暂存文本109份、本轮公开文本9份，0命中，exit0。**2026-10-03 00:42（Asia/Shanghai）正式释放 emulator-5554、8080/8091/8768、构建与 Maestro 操作权给主 Agent**；本轮全部命令结束。

正式状态：**R1 技术修复复验 PASS；T8-IND-01 历史事故处置待用户确认，综合仍 FAIL。** 原事故的订单、退款、库存事实继续保留；本报告不批准例外，不关闭阻塞，不授权提交/勾选。Task9–12/ASR/核销未实施。
