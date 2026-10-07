# Task2 独立验收报告

**结论：PASS（仅 Task2）。阻塞项 0；完整后端回归并非全绿。**

本 Agent 是独立验收者，依据真实运行和需求作判断，不沿用实现者或父侧 PASS。验收从 2026-10-01 深夜运行至 2026-10-02 凌晨，文件日期沿用任务编号。Task1→Task3→Task2→Task8 顺序保持，本报告不勾选主计划、不提交，也不代替 Task8 验收。

## 范围与环境

依据两仓 AGENTS.md、实施计划 Task2、spec 6.1/6.2 及货币/时间/ID 约束。开发两份报告仅用于定位已有实现与环境。

- 手机：`C:/fdz-build`，用户指定手机工作区的同一 junction；HEAD `f4902b8`。
- 新 Agent：`D:/project/-Demo-master`；HEAD `fad28f8`。
- MySQL 8.0.44：真实主库 `fandazi_groupbuy_dev` 与既有隔离副本 `task2_upgrade_b7b37c05148a` 均 V13。
- 真实 Spring 8080 与新 Agent 8091，实际来源 `D:/project/-Demo-master`、`service.app:create_app`；本 Agent 未重启、未终止服务。
- 本次无 Android 实现改动验收，不宣称 APK、Maestro、Figma 或 UI 通过；核销 UI13/14 延期。

## 逐项判定

| 需求 | 判定 | 本次实际证据 |
|---|---|---|
| 订单/券/退款页面完整摘要，合法 null 图片和未付金额 | PASS | 四计划类 17/17；真实在线首轮业务 8 项与整组复验 9/9；独立 HTTP 核对首返/回读 |
| 支付快照、稳定商加余数、退款不随套餐改价 | PASS | 10001 分三券真实接口 3334/3334/3333；RefundEligibilityTest 实际修改套餐现价后准备/申请/清缓存回读仍 3334；独立快照改写抛异常；MySQL 实付分摊差异 0 |
| 未付金额 null、不可退款；已申请/核销/过期/取消拒绝 | PASS | 七夹具真实负面 prepare；独立有实付且券未来到期的 CANCELLED/EXPIRED 也拒绝；申请后重准备拒绝 |
| 本人订单/券/退款隔离；canReview 按实际核销事实且唯一 | PASS | 七账号他人真实订单拒绝；独立他人券/退款拒绝；ReviewEligibilityTest 4/4 含无核销时间、越权、未消费、重复；真实可评价/已评价状态正确；MySQL 唯一索引有效 |
| 七个隔离开发账号；production/prod/disabled/seed 守卫；无造状态 HTTP | PASS | 七夹具 7/7；真实 Spring 条件探针五种组合不注册夹具；代码只有受条件保护的 ApplicationRunner，无 Controller；凭据仅忽略清单 |
| 实际图片字节与缺图路径 | PASS，有稳定性风险 | 真实 GET HTTP 200、image/jpeg、41914 bytes、合法 JPEG 首尾；原测试单项与整组复验通过；缺图 imageUrl 为 null，未冒用固定餐图 |
| UTC 时区真实转换、微秒精度、首次返回与读回一致 | PASS | BusinessTimeTest 3/3；独立订单/券/退款对 MySQL 本地时间作上海→UTC 精确比较；新订单 UTC 位于创建前后真实时钟区间；订单和退款首返回读完全相同 |
| 真实 MySQL DECIMAL 存储且实体实际绑定，协议整数分 | PASS | 13 个金额权威列均 DECIMAL(12,2)；独立新单付款/券/退款实际值与整数分精确换算一致；新记录旧 cents 为空；历史转换差异 0 |
| 随机 v4 UUID、稳定 legacy 映射、内部 FK/历史行保留、旧授权失效 | PASS | 实际资源及别名非 v4 数量 0；代码使用 UUID.randomUUID 而非 nameUUID；主/副本旧 25 单/29 券/6 退款与内部引用一致；215 副本映射；旧 ACTIVE quote/未消费 grant 为 0；独立 Flyway V11→V13 及重跑通过 |
| 旧 alias 不作为公开目录编号，V1–V11 不改 | PASS | 真实旧目录查询明确 DOMAIN_ERROR；11 个原迁移逐字节等于 HEAD |
| 双仓契约原始字节与摘要严格同步 | PASS | 两个 SHA256 相等、manifest/source.sha256 一致；完整 D 离线回归包含跨仓与新增字段规则 |
| 新 Agent 真实来源与跨层兼容 | PASS | Task3 两个真实在线文件 5/5，JWT 拒绝、NDJSON、输入/确认恢复、退款与复合读取通过 |

## 运行数量与命令

完整参数、每类数量与退出码见 [test-results.md](task2-independent-20261001-evidence/test-results.md)，安全 SQL 与结果见 [mysql-and-repository-results.md](task2-independent-20261001-evidence/mysql-and-repository-results.md)。

| 实际命令/范围 | 结果 |
|---|---|
| `mvn.cmd -f backend/pom.xml -Dtest=<四计划类及全部指定扩展的21类> test` | 70/70，0 failures/errors/skipped，27.967 秒，exit 0 |
| `mvn.cmd -f backend/pom.xml test` | 195 项：194 PASS、1 failure、0 errors/skipped，1:18，exit 1 |
| pinnT `python.exe -X utf8 -m pytest -q`，明确排除四份 live 文件，唯一 D/.scratch basetemp | 352/352，15.79 秒，exit 0 |
| `RUN_TASK2_LIVE=1` 两份 Task2 live 文件 | 首轮 8 PASS/1 图片超时；原样整组复验 9/9，5.68 秒，exit 0 |
| `RUN_AGENT_LIVE=1` 两份 Task3 live 文件，共享环境只加载不打印 | 5/5，31.04 秒，exit 0 |
| `Task2IndependentProbe.java` 加载已编译生产类 | 10 个独立检查，exit 0 |
| `inspect_task2.py` 真实 MySQL/HTTP 核心探针 | 40 个断言，exit 0；七夹具状态写入 0 |
| `mysql-readonly.sql` Docker 执行 | exit 0；旧金额、行数、归属/引用、分摊差异均为 0 |

计数包含重复覆盖，不将它们相加冒称独立场景数量。Maven 全量结果明确保持失败状态，不能写全量绿。

## 非阻塞问题与依据

1. **完整后端唯一文案基线失败。** `AgentCardEnrichmentTest.unknownCardTypeIsRejectedWithoutFallbackData` 期望“不支持”，实际为 `VALIDATION_ERROR:卡片缺少资源或动作`。生产类与测试类逐字节等于当前已提交 HEAD，本次调用在 required/enrich 输入校验阶段结束，不经过 Task2 金额、时间或 ID 代码。独立探针证实未知类型缺资源和带资源均抛 DomainException、不会产生 fallback 数据；带资源时正确返回“不支持”。Task2 定向、真实数据链路与 Task3 兼容全部通过，因此判定为 Task2 非阻塞。残余风险是完整后端 CI 仍为红，应由后续任务明确错误优先级与断言，一并处理；本 Agent 没有放宽断言或修改生产顺序。
2. **外部餐图源偶发失败。** 首轮原在线测试响应体读取超时；补充探针取得真实 JPEG 后，同连接第二次 RemoteProtocolError。原样单项复测和整组复验均通过。实际图片路径存在与缺图 null 满足 Task2 要求，但不代表图源稳定；下一 Task8 必须真实验证加载失败占位与重试，不用假餐图遮盖失败。

## 操作影响与限制

七个账号只登录、读取和执行必定拒绝的负面 prepare；未支付、退款、评价或核销其资源，复跑仍 7/7 保持原场景。正向在线流程仅新注册随机隔离账号，会留下测试订单/申请并消耗少量真实开发库存，没有删除数据。

本次亲自验证真实 V13 主库和隔离副本保留结果；没有重新读取敏感 dump、恢复数据库或重启副本，故不冒称本 Agent 实施了 MySQL 恢复/升级或重启演练。独立实际执行的 V11→V13 和重跑为 H2 的 Flyway/JDBC 测试，真实 MySQL 的类型、实体写入、历史数据与引用另有本次独立证据。

凭据、密钥、JWT、二维码令牌及备份正文未写报告或证据；夹具/测试夹具/备份均被 Git 忽略且未跟踪。原始 Maven 本地日志只保留 D/.scratch；安全证据不复制原业务请求或私密清单。保留两仓原有 dirty，不改实现/既有测试源、不勾主计划、不提交。服务保持可供下一 Task8 使用。
