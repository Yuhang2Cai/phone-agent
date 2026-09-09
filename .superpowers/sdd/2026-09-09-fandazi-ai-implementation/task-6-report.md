# Task 6 Report — Android 外壳、认证、OkHttp 和 Glide

## 实现内容

- 建立 Android Gradle 工程、Gradle wrapper、Manifest、Material 主题和 XML 资源。
- 建立单 `MainActivity`、Navigation `NavHostFragment`、认证 Fragment、首页 Fragment 与 ViewModel 路由。
- 使用 XML 登录/注册切换表单；字段校验、重复提交保护、认证错误提示、退出登录与首页路由均在 `AuthFormProcessor` / `SessionRepository` 中处理。
- 建立 `ApiClient`：OkHttp 连接/读取/写入超时、Bearer Token 拦截、JSON POST、服务端 HTTP/网络/超时错误映射，以及幂等 GET 一次重试。
- 建立 `SessionRepository`：`StateFlow<SessionState>`、注册/登录/退出、加密持久化和进程重建会话恢复。
- 使用 `EncryptedSharedPreferences` + `MasterKey` 保存 Token；使用 Glide 建立统一图片加载入口，配置占位/错误图、内存/磁盘缓存与圆角转换。
- 添加单元测试骨架，覆盖登录成功、凭证错误、超时、退出、进程重建、Bearer 注入、GET 重试、图片失败降级与重复提交保护。

## 验证结果

### 通过

以下 PowerShell XML 静态检查成功解析了全部新增资源：

```powershell
$xmlFiles = Get-ChildItem -Recurse 'app\\src\\main\\res' -Filter '*.xml'
foreach ($file in $xmlFiles) { [xml](Get-Content -Raw $file.FullName) | Out-Null }
```

已通过解析的资源包括 Activity、认证/首页布局、导航图、颜色、字符串和主题。

### 未能运行

`testDebugUnitTest`、Kotlin 编译与 `assembleDebug` 没有运行到源码编译阶段，因此没有 APK。原因如下：

1. Gradle wrapper 尝试下载 `https://services.gradle.org/distributions/gradle-8.13-bin.zip` 时失败，错误为 `java.net.SocketException: Permission denied: getsockopt`。
2. 使用机器已有 Gradle 8.13 执行离线命令时，构建配置在依赖解析阶段停止：

```powershell
$env:JAVA_HOME='D:\\software\\AdroidStudio\\jbr'
& 'C:\\Users\\caid0\\.gradle\\wrapper\\dists\\gradle-8.13-bin\\5xuhj0ry160q40clulazy9h7d\\gradle-8.13\\bin\\gradle.bat' testDebugUnitTest --offline --no-daemon --stacktrace
```

```text
Could not resolve com.android.tools.build:gradle:8.10.1.
No cached version of com.android.tools.build:gradle:8.10.1 available for offline mode.
Could not resolve org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20.
No cached version of org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20 available for offline mode.
```

3. Android SDK 未配置：未设置 `ANDROID_HOME` / `ANDROID_SDK_ROOT`，且没有 `local.properties`。默认 SDK 目录在当前沙箱中不可读取，依赖问题也先于 SDK 检查发生；在依赖可解析前，Gradle 无法进一步报告 SDK 诊断。

## 清理

已删除本任务产生的临时目录和构建产物：

- `android-app/.gradle-user-home/`
- `android-app/.gradle/`
- `android-app/build/`

未删除任何 Android 源码、测试、资源或 Gradle wrapper 文件。
