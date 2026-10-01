# Task3 新 Agent 服务开发记录

状态：开发自测通过，**Task3 已独立验收 PASS**。Task2/Task8 不以本报告替代它们的验收。

## 来源与实现

- 服务源码在 `D:/project/-Demo-master/service/`，入口 `service.app:create_app`，监听 `127.0.0.1:8091`。旧手机 `agent-service` 保留但不导入、不启动、不作为失败降级。
- 入口、事件、17 工具全部使用受控 1.1 契约。内部 HS256 JWT 绑定用户、有效期至多两分钟；输出标准 NDJSON，步骤开始/结束在真实 HTTP 调用前后实时发送。
- 复用三层 `IntentDecisionOrchestrator.decide`，背景 state 与 0.80 阈值不变。模型槽位不输出身份、价格、授权、URL或幂等键。订单查询与购买在同一一级意图内细分动作，不把查订单当作创建订单。
- 单目标退款选券/补原因/准备/确认、投诉补内容/准备/确认、购买报价/创建确认/独立支付确认均保存到独立 MySQL `dianping_intent.agent_service_runs`。取消不执行业务写。只有本轮可信确认可以消耗后端动作专属凭证。
- DAG 使用重型 Planner、共享 Validator/Executor 和实际团购白名单；成功节点保存检查点，恢复不重放，写节点串行且失败阻断后续写。模型不能编造凭证，四种写动作必须引用各自准备输出。公开摘要无凭证。
- 写请求发出前保存进行中标记。若进程中断后无法知道结果，停止重复提交并要求核查业务记录，不承诺未发生或已回滚。
- 启动脚本读取真实容器 DB 环境，共享内部令牌/JWT 随机生成后保存手机忽略目录 `.runtime/`，没有源码密钥。新 Agent 启动验证 MySQL，启动失败不伪造内存状态。

## 实际命令与开发结果

使用 `C:/Users/caid0/anaconda3/envs/pinnT/python.exe -X utf8 -m pytest`。

- 服务入口首次 6 项失败，原因入口尚未实现；实现后 6/6 通过。
- DAG 首次 2 项失败，原因 `service.dag_runtime` 尚不存在；实现确认与检查点后 2/2 通过。
- 服务 JDBC→PyMySQL 配置首次 1 项失败，新增实际驱动配置后通过。历史 URL 助手和历史基线没有修改。
- 新服务与原 DAG 定向组合：39/39 通过。
- 分类迁移、当前图、DAG、契约与服务组合：首轮 244 通过、16 个系统临时目录权限错误；加 `--basetemp=.scratch/task3-parent-regression-a` 后 **260/260 通过**，退出码 0。未删除断言或改变测试预期来掩盖环境错误。
- 真实进程冒烟：共享环境后设置 `RUN_AGENT_LIVE=1`，运行 `tests/test_agent_service_live.py`，**1/1 通过**。检查实际来源、无 JWT 401、跨身份 403、真实问候 NDJSON、1.1 Schema与序号，健康确认 `sourceRoot=D:/project/-Demo-master`。
- 首次真实问候实际本地未命中、快模型低置信度转重型后成功，约 20 秒；这不是全量分类准确率或性能结论。后续补明确问候审核整句再复验。

真实 Spring 双服务闭环在 `tests/test_agent_service_e2e_live.py`，**本段初次记录时未运行**；后续实测见下节，不将当时的预期写成结果。

## 22:35 最新加载与真实闭环复测

- 首次真实双服务结果为 2 PASS、2 FAIL：投诉确认遇到 Python 3.10 无法直接解析 Java 九位小数时间戳；退款/投诉最终 DTO 丢失 `inputKey/question`。保留失败，不以空字段或跳过确认放宽测试。
- 时间使用 Pydantic 标准日期解析器，仍要求时区与未过期；新增精度回归先失败后通过。Spring 最终结果保留递归脱敏后的补槽元数据；独立开发回归由 53 项增至 54 项通过。
- 工具客户端非法业务数据原始校验异常现统一转为 `ToolFailure`，读失败可重试、写失败保留结果不确定并禁止盲目重试；新增三个回归已通过。
- DAG Schema 引用在本地展开，不把整个工具契约重复塞进 17 个输出 Schema；恢复结果保留真实 FAQ/评价信息。持久化前在事件循环复制状态，避免并发只读节点在数据库线程中复制共享可变对象；运行锁使用弱引用，不累积历史运行锁。
- 重启 Spring（新 PID 43968）和新 Agent（新 PID 44156）后亲自运行：

```powershell
. 'C:/fdz-build/scripts/Initialize-FandaziEnvironment.ps1'
$env:RUN_AGENT_LIVE='1'
& 'C:/Users/caid0/anaconda3/envs/pinnT/python.exe' -X utf8 -m pytest tests/test_agent_service_live.py tests/test_agent_service_e2e_live.py -q --tb=short --basetemp=.scratch/task3-live-d
```

结果 **5/5 通过，26.26 秒，退出码 0**。真实随机注册隔离账号，经 Spring 公开消息接口检查问候、空订单、投诉补槽/明确确认、真实报价创建支付后选本人券/补原因/明确退款、复合订单+退款规则进入 Planner/DAG 与两个真实工具。退款只显示等待处理，不显示到账；公共结果不含授权凭证。

最新离线聚合覆盖 23 个当前测试文件，命令采用同 Python、`MOBILE_PROJECT_ROOT=C:/fdz-build`、`--basetemp=.scratch/task3-final-offline`；**283/283 通过，12.84 秒，退出码 0**。此聚合不包括所有历史实验与 Android，不外推全仓验收。

上述均为**开发自测通过**。新的独立验收 Agent 正在按规格亲自复验，尚未获得独立 PASS，因此本节不勾选 Task3 完成。

## 环境恢复记录

电脑重启后 Docker/MySQL/后端/Agent 均未监听。Docker 日志提示 `dockerInference` 和 `docker-secrets-engine/engine.sock` 残留 Unix 通信文件导致启动失败。
保留重命名明确的临时目录后恢复 Docker，未恢复出厂、未删除容器或卷、未触碰 MySQL 持久数据。实际 `docker ps`：`mysql-server` Up，3306 可用。
PowerShell 默认代理访问本地健康接口曾返回 502；使用 `-NoProxy` 或 Python `httpx(trust_env=False)` 后核对实际 Uvicorn 进程成功，不把代理响应当作服务结果。

## 收尾条件

还需融合 Spring 桥接开发报告、真实双服务闭环、最新配置服务重启、定向完整回归；随后另起独立验收 Agent 亲自检查。未运行或有阻塞不得勾选 Task3 完成，Task2 必须等 Task3 独立 PASS。

## 独立门禁结果

2026-10-01 22:52 新独立验收报告 PASS，Python 356、Maven 72 项由验收者亲自运行；真实双服务重启、购买两次确认、取消、身份隔离、SSE 和 MySQL 持久恢复均有证据。详见 `2026-10-01-task3-independent-acceptance.md`，最初测试脚本误判与复验均保留。此结论不包含 Android、Task2 或 Task8。
