# Android 应用

使用 Android Studio 和 JDK 17 打开并构建本目录。需要随 APK 打包的模型文件放在 `app/src/main/assets/`。

BuildConfig.API_BASE_URL 按 build type 配置：debug 默认访问 Android 模拟器中的本地后端
http://10.0.2.2:8080/api/，release 默认是可替换的 HTTPS 占位
https://api.example.invalid/api/。可用 -PdebugApiBaseUrl=... 或 -PreleaseApiBaseUrl=...
覆盖地址；仅 debug Manifest 开启明文 HTTP，release 保持安全默认值。生产环境必须提供 HTTPS 地址，不提交密钥。

## 构建和测试

```powershell
cd android-app
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Task 12 新增 `FakeApiOrderingFlowTest`，使用 Fake API Client 覆盖登录、搜索、规格、购物车、地址、结算、支付、订单时间线和 Agent 确认，不依赖真实后端或真实 LLM。若本机 Android SDK、Gradle 插件缓存或网络不可用，请记录实际失败，不要伪造 APK 或设备截图。
