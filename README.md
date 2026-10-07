# 饭搭子团购App

Android XML客户端 → Spring团购业务服务 → 独立新Agent。Android只访问Spring；业务事实来自后端，Agent负责三层意图、DAG编排和授权长期记忆。文字和卡片留在聊天内，不自动跳转。

## 运行入口

- android-app：首页、AI助手、订单、我的四Tab；ASR和核销延期。
- backend：真实门店/套餐/报价/团购订单/模拟支付/券/售后及Agent代理。
- 独立Agent仓库：service.app:create_app，端口8091，内部协议1.1；agent-service仅历史参考，见其DEPRECATED.md。
- 工具唯一活动入口/internal/v1/tools，旧/internal/tools拒绝；新Agent不可用时明确失败，不调用8090。

## 本地启动

准备MySQL、JDK17、Python及私有环境，分别执行scripts/Start-Services.ps1 -Service Spring和-Service Agent。本机启动器核验真实进程和健康，读取Docker/忽略.runtime凭据；新Agent读自己忽略.env的模型和独立dianping_intent库。普通启动不开种子或夹具、不重置事实。Android debug连接http://10.0.2.2:8080/api/。

可移植启动：配置DB_URL/DB_USERNAME/DB_PASSWORD/JWT_SECRET/AGENT_INTERNAL_TOKEN/AGENT_SERVICE_URL后运行mvn -f backend/pom.xml spring-boot:run；Agent仓库配置独立数据库和模型后运行python -m uvicorn service.app:create_app --factory --host 127.0.0.1 --port 8091。不要使用旧Agent安装/启动命令。

## 验证与数据安全

后端mvn -f backend/pom.xml test，新Agent仓库python -m pytest tests -q，Android wrapper运行testDebugUnitTest lintDebug assembleDebug。最新APK Maestro和独立验收不能由单测替代。

旧交易测试保留源码但退出默认后端套件，新团购/鉴权/公开事件/记忆测试仍执行。历史表和实体保留，旧地址/购物车/配送/自动下单不注册默认组件或导航。V14只登记停用审计，不清除旧事实；scripts/verify_retirement_backup.py只向随机隔离库恢复。

真实密钥、密码、JWT、数据库备份、日志、APK和真实用户记录禁止提交。Task9真人文案生产审批未完成；Task12须保持启用门槛。实际结果以独立报告为准，运行说明不是验收声明。
