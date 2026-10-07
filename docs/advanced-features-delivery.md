# 进阶功能集成与本地验收

本轮包含真实菜品照片、聊天四类 RecyclerView、九项实现优化、权威套餐对比、公开目录弱网离线浏览，以及从 redBook 固定来源迁移的本地 ASR 实现。手机与 Agent 两仓的精确提交、实际证据和未验收项目以 Agent 仓原 `2026-10-03-fandazi-advanced-features-implementation.md` 及各步骤报告为准。

## 构建与隔离服务

Android 使用现有 JDK17、Kotlin2.1.20、AGP8.10.1、compileSdk35；公开 Room2.7.2 与 KSP2.1.20-2.0.1 是本轮必要依赖。按 `scripts/Import-RedBookAsr.ps1 -IncludeLocalModel` 从已授权本机 redBook 来源导入固定构建输入；脚本校验来源摘要，模型/token 和私有配置不提交 Git。模型再分发许可仍待核实，不把本机构建当成资产发布授权。

隔离环境为 Spring18080、当前 Agent18091、业务 MySQL13307、意图 MySQL13308和 Chroma18000。验收使用独立数据库与专属合成账户；现有3306数据库及历史订单不作为写入夹具。密钥通过既有私有配置或环境注入，禁止写进源码、截图交付或提交。

常用构建命令（在对应模块执行）：

```powershell
# Android适用回归必须使用报告中的ASR排除初始化脚本；不执行ASR单测或录音。
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --max-workers=1 -PdebugApiBaseUrl=http://10.0.2.2:18080/api/ -I ..\scripts\Exclude-AdvancedAsrTests.init.gradle
# Spring与Agent按报告对应的精确源码快照运行全量。
mvn test
python -m pytest tests -q
```

## 组合入口

`maestro/advanced-18-full.yaml` 通过显式 `-e ADVANCED18_PHASE=阶段名` 选择在线对比/文字编辑/真实图文卡片/200条历史/20轮Tab/离线快照/恢复新报价/显式确认，以及真实Agent故障/恢复阶段。历史参数同样使用 `-e`；Maestro默认只自动导入 `MAESTRO_` 前缀环境。选择辅助流程等待完整列表，保留两个原公开ID，不反复切换同一选择。城市和套餐ID来自本轮真实后端，账号和密码仅从忽略目录注入。

运行前固定最新APK、设备、服务镜像、来源合同和计划；阶段之间只能由主验收控制器改变指定网络或隔离故障条件。每段记录实际安装APK摘要、命令退出码、真实截图与读/写计数。报价和订单只能由显式UI点击产生；模拟支付页只查看，不付款、不核销。网络/服务故障在 finally 恢复，并从设备实际请求HTTP证明连通；不能用缓存响应或网络图标代替该证据。

公开缓存只包含目录DTO白名单，不保存账户、订单、券、聊天、记忆、凭据、精确坐标或音频。15分钟内为新鲜快照、15分钟至24小时为过期只读，超过24小时隐藏；筛选条件精确隔离，不补齐缺失成员。缓存图片只有真实下载过才可看，缺图明确提示。恢复不重放消息或交易，购买仍取得当前事实、新报价并显式确认。

## 尚需人工与真机验收

用户要求 ASR 本轮只实施/编译、不验证：现场录音、语音回填/编辑、权限/取消/后台/账号场景、离线推理和音频指标仍待人工。文字编辑阶段不能冒充语音验收。

当前固定设备是 x86_64 模拟器；三批模拟器参考数据不代表 arm64 真机性能通过，不能宣称整体达到20%提升或5%退化门槛。Task14/Task15以及Task18涉及的人工/真机步骤保持未完成；完成其它适用回归后仍应按报告列明这些限制。
