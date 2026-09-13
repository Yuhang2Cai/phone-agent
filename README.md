# 饭搭子 AI

饭搭子 AI 是一个 Android 外卖 App + Spring Boot 后端 + Python LangChain/LangGraph 单 Agent 的作品集项目。Android 负责用户交互和本地 ASR 输入；Spring Boot 负责认证、菜单、地址、购物车、订单、模拟支付、配送时间线、SSE 和工具授权；Python Agent Service 只做受约束点餐编排。

## 工作区

- `android-app/`：Android 客户端，XML + MVVM + OkHttp + Glide。
- `backend/`：Spring Boot 服务，公开 `/api` 和内部 `/internal/tools`。
- `agent-service/`：Python Agent Service，LangChain + LangGraph。
- `ui-prototype/`：可编辑 HTML/CSS/JS 原型。
- `data/seed/`：可重复导入的合成数据和 Agent 场景。
- `docs/`：架构、API/Agent、面试说明和验证报告。

## 本地启动顺序

1. 准备 MySQL 和 JDK 17，设置后端环境变量。
2. 启动 Spring Boot backend，默认 API 根路径为 `http://localhost:8080/api`。
3. 启动 Agent Service，默认内部地址为 `http://localhost:8090`。
4. 用 Android Studio 打开 `android-app/`，debug 默认连接模拟器中的 `http://10.0.2.2:8080/api/`。
5. 直接用浏览器打开 `ui-prototype/index.html` 可查看无后端原型。

## 脱敏配置示例

不要提交真实 `.env`。本地可用未跟踪 `.env` 或 shell 环境变量：

```powershell
$env:DB_URL = "jdbc:mysql://localhost:3306/fandazi_ai?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
$env:DB_USERNAME = "fandazi"
$env:DB_PASSWORD = "<local-db-password>"
$env:JWT_SECRET = "<64+ chars local secret>"
$env:AGENT_SERVICE_URL = "http://127.0.0.1:8090"
$env:AGENT_INTERNAL_TOKEN = "<shared internal token>"
$env:LLM_BASE_URL = "https://api.deepseek.com/v1"
$env:LLM_MODEL = "deepseek-chat"
$env:LLM_API_KEY = "<local-only key>"
$env:LANGCHAIN_TRACING_V2 = "false"
$env:LANGSMITH_API_KEY = "<optional local-only key>"
```

`LLM_API_KEY`、`JWT_SECRET`、`AGENT_INTERNAL_TOKEN`、数据库密码、日志、APK、模型压缩包和 `.env` 都禁止提交。

## 验证入口

- Backend: `mvn -f backend/pom.xml test`
- Agent Service: `.\agent-service\.venv\Scripts\python.exe -m pytest agent-service/tests -q`
- Android: `cd android-app; .\gradlew.bat testDebugUnitTest lintDebug assembleDebug`
- 真实环境限制和本机执行结果见 `docs/verification-report.md`。
