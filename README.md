# 饭搭子 AI

## 工作区

- `android-app/`：Android 客户端
- `backend/`：后端服务
- `ui-prototype/`：HTML 原型
- `data/seed/`：种子数据

## 运行约定

开发环境使用 Android Studio、JDK 17 和 MySQL。后端启动前请准备数据库并设置环境变量；在 `backend/` 中按后续任务提供的启动命令运行。Android 项目使用 Android Studio 打开 `android-app/`，或使用 Gradle 构建。HTML 原型可直接用浏览器打开 `ui-prototype/` 下的 HTML 文件预览。

配置项含义：

- `DB_URL`：MySQL JDBC 连接地址
- `DB_USERNAME` / `DB_PASSWORD`：数据库账号与密码
- `LLM_BASE_URL`：大模型服务接口根地址
- `LLM_API_KEY`：大模型服务密钥
- `LLM_MODEL`：使用的大模型名称
- `JWT_SECRET`：JWT 签名密钥

密钥、密码和真实配置禁止提交到 Git。请使用本地环境变量或未跟踪的 `.env` 文件。

## 目录文档

各子目录的 README 记录该模块的后续约定。
