# 活动 Maestro 按步骤验收

这些流程必须按独立前置逐个运行，不能直接对整个目录执行测试。活动业务数据来自本机专用后端，安装包须与冻结源码对应并内置 Hermes Bundle。设备默认复用已授权的 emulator-5580；禁止脚本创建模拟器。

运行格式：`maestro --device emulator-5580 test maestro/activity/<流程>.yaml`。使用 ADB 启动应用后运行流程，避免 launchApp 反复提取大 APK。用户名、密码、JWT、服务连接和私有日志只留在 Git 忽略的 `.runtime`；不把 Maestro 输入凭据日志提交到仓库。

| 流程 | 必须满足的前置与目的 |
|---|---|
| formal-guest | 登录页且可跳过；真实公开任务包含连续三天定义。验证游客详情只读、规则和登录入口 |
| formal-main | 新登录合成用户、活动根、每日一单未领取；原生首页可购买演示套餐。完整领取→模拟支付→手动领奖→明细，订单和账本须另回读 |
| formal-narrow | 密度480对应360dp、余额10且连续任务可读；执行后恢复440。只读尺寸兼容 |
| formal-navigation-final | 余额0、存在“双人蔬食套餐 2”订单；五入口、规则返回和根返回首页。依赖错误须先恢复原服务并显式重读 |
| formal-rotation | 活动根已打开；外部先记录旋转设置并切横屏，finally恢复。验证旋转后入口与返回 |
| formal-loading | 冷启动后停首页；代理只延迟真实tasks GET，随后返回原响应。不能伪造业务数据 |
| formal-empty | 已登录用户；专用库任务暂时停用并保留恢复清单，流程退出到游客读取空态；finally恢复原配置 |
| formal-service-unavailable | 专用依赖受控不可用，真实API503；不替换成空响应；finally恢复依赖 |
| formal-definite-failure | 活动根含已读取的可领取任务，服务端随后停用该定义，预期409明确失败；finally恢复 |
| formal-unknown / formal-claim-processing | 真实已领取且模拟支付达标、余额0。代理仅丢弃一次已提交领奖回执；后者延迟15秒并连续点击，验证提交中与未知结果，不重复领奖 |
| formal-recreated | 保留上述原请求，重建应用进程后停首页。只读恢复10金币/已领取/明细；须结合ActivityRecoveryDeviceTest前后验证和唯一账本核查 |
| formal-accept-unknown / formal-accept-recovered | 已有10金币、每日三单尚未领取；丢一次真实accept回执，强停后按原键恢复。首次写入后禁止重跑unknown流程 |
| formal-terminal | 先用显式启用的ActivityUiStateMysqlTest为本任务合成用户建立受控终态和历史夹具，余额0；核对撤回流水、奖励撤回和领奖过期。不代表真实退款渠道上线 |

非UI设备测试：`ActivityRecoveryDeviceTest`要求`expectedPending=present/absent`明确选择前置；它不发业务写入。`ActivityLifecycleDeviceTest`使用真实会话/导航/Host，验证后台焦点、页面重建、运行时数量以及受控RN异常后的原生重试。安装对应androidTest APK并通过`am instrument -w -e class ...`运行，检查`OK`和失败数，不能只相信ADB退出码。

退款/历史前置测试要求ACTIVITY_UI_FIXTURE_ENABLED=true，ACTIVITY_DB_URL限定本任务UI数据库，ACTIVITY_UI_USER_ID与ACTIVITY_UI_ORDER_ID必须对应自己的合成用户和真实模拟支付订单。历史订单是明确标记的合成数据；首次曾缺券导致原生协议拒绝，已补完整券数量及金额关系。不要在共享或生产库运行。

首次失败、驱动修正和受影响组合复验分别保留；未知写必须查原请求，不为测试通过另建替代交易。最终结果和截图对照位于文档仓活动验收报告。
