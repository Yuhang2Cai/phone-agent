# Android 应用

使用 Android Studio 和 JDK 17 打开并构建本目录。需要随 APK 打包的模型文件放在 `app/src/main/assets/`。

开发环境默认通过 `BuildConfig.API_BASE_URL` 访问 Android 模拟器中的本地后端
`http://10.0.2.2:8080/api/`。仅 `debug` source set 的 Manifest 开启明文 HTTP，便于连接本地开发服务；主 Manifest 未开启明文流量，release 构建保持 Android 默认的安全策略。生产环境应改用 HTTPS，并通过本地构建配置覆盖 API 地址，不提交密钥。
