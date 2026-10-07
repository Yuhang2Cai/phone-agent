# 独立集成部署与回归

本期仅启用团购业务、新Agent、意图审计、长期记忆；旧8090服务不启动。ASR与UI-13/14核销延期。

## 源码与环境

两仓暂存范围经独立验收后精确提交。`scripts/Export-DeliverySnapshot.ps1`用`git write-tree`导出隔离拟提交源，保存tree编号，不包含未暂存的本地文件。Agent快照最后一级目录使用`-Demo-master`，与既有健康标识测试一致。

集成Compose需要管理员在当前进程注入以下环境，不把值写入本文件、源码或Git：`INTEGRATION_DB_ROOT_PASSWORD`、`INTEGRATION_DB_PASSWORD`、`INTEGRATION_JWT_SECRET`、`AGENT_INTERNAL_TOKEN`、`QWEN_KEY`、`QWEN_NAME`、`QWEN_URL`、`DASHSCOPE_API_KEY`、`EMBEDDING_KEY`、`EMBEDDING_MODEL`。`NEW_AGENT_SOURCE`指定Agent隔离源码绝对目录；`DELIVERY_VERSION`记录实际交付tree/提交版本。

```powershell
docker compose -p fandazi-t12 -f docker-compose.integration.yml config -q
docker compose -p fandazi-t12 -f docker-compose.integration.yml up --build -d
```

独立业务MySQL端口13307、新Agent库13308、Chroma18000、Spring18080、新Agent18091；均绑定宿主回环，不复用3306。卷保留，不使用`down -v`或清库。Spring禁用种子，故障注入默认关闭。

新Agent库空卷首次启动执行既有001/002审计SQL；已有卷需显式运行`scripts/Initialize-IntegrationAudit.ps1`幂等升级。运行状态和记忆由服务建表，审计不能因漏部署长期失败；升级失败保留卷并报错。业务Flyway按V1–V15前向执行，历史checksum不改；V15仅追加可空注册昵称，旧用户不回填，账号密码及历史交易不改。

Agent健康`/internal/v1/health`包含协议、源码根与版本；Spring实际读取`/api/stores/cities`验证目录依赖。故障演练停止新Agent，普通目录继续200，AI明确失败；恢复后重新请求成功，禁止回旧8090。

## 数据与设备回归

`prepare_integration_catalog.py`对3306来源执行只读一致快照，仅INSERT IGNORE到13307固定集成库，不覆盖已有事实。拷贝的私有行只留本机库，不提交。`prepare_full_regression.py`新建三个合成账号、独占套餐和评价开发资格；购买、支付、退款、投诉、评价通过真实接口及页面执行。核销事实只作为评价前置夹具，不开放核销能力。

```powershell
python scripts/prepare_full_regression.py --secret-file <忽略目录私有清单> --output <新的.runtime夹具清单>
# 在隔离手机源码android-app中构建，API只切换验证环境，不嵌入任何业务样例。
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug -PdebugApiBaseUrl=http://10.0.2.2:18080/api/
python scripts/Run-FullRegression.py --fixtures <私有清单> --apk <最新隔离APK> --evidence <新的.runtime证据目录>
```

运行器读取实时目录ID/金额与本人记忆API，安装指定APK后运行99主链。原始Maestro命令/层次树含合成凭据，必须保留忽略目录；公开仅选取无秘密截图、计数、退出码、hash。失败立即非零，不自动重购原夹具。再次主链必须另建测试批次。

网络、503、Agent停机/恢复、敏感确认属于受控副链：必须独立实际运行并单列结果，主链exit0不能替代。503仅在独立集成Spring明确development profile与task8.failures.enabled=true时启用，用完恢复默认配置；网络演练在finally中恢复模拟器网络。

```powershell
# 仅隔离验收副链允许开启；重新up后核对实际Spring配置，再运行副链。
$env:INTEGRATION_PROFILE='development'
$env:INTEGRATION_FAILURES_ENABLED='true'
docker compose -p fandazi-t12 -f docker-compose.integration.yml up -d
python scripts/Run-IntegrationSideChains.py --fixtures <本轮私有清单> --apk <同一最新APK> --evidence <新的.runtime副链目录>
# 无论副链是否通过，均须在控制端finally中恢复默认并重新up；不清除数据库卷。
Remove-Item Env:INTEGRATION_PROFILE,Env:INTEGRATION_FAILURES_ENABLED
docker compose -p fandazi-t12 -f docker-compose.integration.yml up -d
```

自动化受控副链只允许停启固定`fandazi-t12-agent-1`和操作`emulator-5554`，不能用通配符停止正式服务。失败退出和系统网络事实单列，恢复命令成功仍需再次核对HTTP健康与设备实际连通性。

## 规则门槛与评测

1120条规则尚未获业务真人审批。生产默认影子，未审批命中也进入模型；不得为了降低延迟伪造批准。审批JSON由管理员受控文件提供，包含schemaVersion=1、decision=approved、reviewedBy、reviewedAt（非未来日期）、normalizationVersion及实际YAML字节ruleSha256。用`LOCAL_RULE_APPROVAL_PATH`显式指定；任何不匹配保持影子。规则改变必须重新审批。

评测契约为`evaluation.models`，`--scorer module:class`可替换，不改变Agent执行。账号文件通过`EVALUATION_LOGIN_FILE`读取；真实公开登录→可信会话→工具授权→SSE首事件。DAG的可选只读核对用`EVALUATION_AGENT_DB_URL`，只返回是否存在plan，不泄露快照。公开卡片兼容字段type与内部cardType分别解析；等待确认核对真实摘要。

```powershell
python -m evaluation.runner --backend http://127.0.0.1:18080 --cases evaluation/scenarios.json --output tests/results/task12-evaluation.json
```

`gateway-goal-v2`将流程终结和目标成功分开；错分、缺工具/卡片、未观测都不能计目标成功。统计每维真实分母和未测数；P50/P95用线性分位数。小规模实测用于验收观测，不替代EXP020均衡分类集；模型错误与未达性能目标须保留，不能改答案使结果变绿。
