# Task 6 Report — Android 外壳、认证、OkHttp 和 Glide

## 实现内容

- 建立 Android Gradle 工程、Gradle wrapper、Manifest、Material 主题和 XML 资源。
- 建立单 `MainActivity`、Navigation `NavHostFragment`、认证 Fragment、首页 Fragment 与 ViewModel 路由。
- 使用 XML 登录/注册切换表单；字段校验、重复提交保护、认证错误提示、退出登录与首页路由均在 `AuthFormProcessor` / `SessionRepository` 中处理。
- 建立 `ApiClient`：OkHttp 连接/读取/写入超时、Bearer Token 拦截、JSON POST、服务端 HTTP/网络/超时错误映射，以及幂等 GET 一次重试。
- 建立 `SessionRepository`：`StateFlow<SessionState>`、注册/登录/退出、加密持久化和进程重建会话恢复。
- 使用 `EncryptedSharedPreferences` + `MasterKey` 保存 Token；使用 Glide 建立统一图片加载入口，配置占位/错误图、内存/磁盘缓存与圆角转换。
- 添加单元测试骨架，覆盖登录成功、凭证错误、超时、退出、进程重建、Bearer 注入、GET 重试、图片失败降级与重复提交保护。

## Task 6 修复轮次 1

- 开发 API 地址仍只用于本地模拟器；新增 `app/src/debug/AndroidManifest.xml`，仅 debug 合并 `usesCleartextTraffic="true"`，主 Manifest/release 未开启明文流量。
- `ApiClient` 现在优先读取后端实际返回的 `error` 字段，兼容 `message` 作为兜底；认证表单将 400/401 凭证失败、400/409 重复注册和会话保存失败映射为可展示文案。
- `MainActivity` 通过生命周期内收集 `SessionRepository.state` 路由；`SessionRouteCoordinator` 按当前目的地去重，进程重建时已有会话进入 Home，未登录保持 Auth。
- `AuthFormProcessor` 先原子占用提交锁再改状态，避免第二次提交重置首个请求的 `isSubmitting`；会话写入/清理改为 IO dispatcher 上检查 `commit()` 返回值。
- 新增错误字段、重复注册、存储写入失败、路由去重和 debug 网络策略测试。

## 修复轮次 1 验证

- XML 静态检查通过：`XML_STATIC_OK=8 files`。
- debug/release 明文策略静态检查通过：主 Manifest 未开启 `usesCleartextTraffic`，debug Manifest 明确开启。
- `git diff --check` 无错误。
- Kotlin 命令不可用：`KOTLIN_CHECK=UNAVAILABLE (kotlinc not on PATH)`。
- `testDebugUnitTest` 的 wrapper 入口仍因 `https://services.gradle.org/distributions/gradle-8.13-bin.zip` 下载失败而无法启动，错误为 `java.net.SocketException: Permission denied: getsockopt`。
- `assembleDebug` 已尝试但在源码编译前失败。命令：

  ```powershell
  & 'C:\\Users\\caid0\\.gradle\\wrapper\\dists\\gradle-8.13-bin\\5xuhj0ry160q40clulazy9h7d\\gradle-8.13\\bin\\gradle.bat' assembleDebug --offline --no-daemon
  ```

  错误为：`No cached version of com.android.tools.build:gradle:8.10.1 available for offline mode`，以及 `No cached version of org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20 available for offline mode`。
- Android SDK 环境变量 `ANDROID_HOME` / `ANDROID_SDK_ROOT` 未设置，且未提供 `local.properties`；由于依赖解析先失败，Gradle 未进入 SDK 诊断阶段。未生成 APK。

## 验证结果

### 通过

以下 PowerShell XML 静态检查成功解析了全部新增资源：

```powershell
$xmlFiles = Get-ChildItem -Recurse 'app\\src\\main\\res' -Filter '*.xml'
foreach ($file in $xmlFiles) { [xml](Get-Content -Raw $file.FullName) | Out-Null }
```

已通过解析的资源包括 Activity、认证/首页布局、导航图、颜色、字符串和主题。

### 未能运行

本轮 `testDebugUnitTest`、Kotlin 编译与 `assembleDebug` 没有运行到源码编译阶段，因此没有 APK。原因如下：

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

## Task 6 修复轮次 2

- 导航所有权收敛到 MainActivity：AuthFragment 与 HomeFragment 不再直接调用 Navigation，MainActivity 根据 SessionRepository.state 和当前 destination 去重路由。
- API_BASE_URL 改为 build type 配置：debug 默认 http://10.0.2.2:8080/api/，release 默认可替换的 HTTPS 占位 https://api.example.invalid/api/，均可通过 Gradle 属性覆盖。
- 认证文案按 HTTP status 和后端 error message 区分凭证错误、重复注册、参数错误、冲突、网络和超时；普通 400 不再统一显示密码错误。
- EncryptedSessionStorage 显式暴露初始化异常；写入/清理仍在 IO dispatcher 执行并检查 commit() 返回值，SessionRepository.error 映射初始化、写入和清理失败。
- 补充 build type、参数错误、存储初始化/清理失败和重复路由回归测试。

## 修复轮次 2 验证

- XML 静态检查通过：app/src/main/res 与 app/src/debug 共 8 个 XML 文件均可解析。
- 静态检查确认 AuthFragment/HomeFragment 无 navigate(，导航调用仅保留在 MainActivity；build type 地址规则与 debug 明文策略检查通过。
- git diff --check 通过。
- 实际未编译：本轮 Kotlin 单元测试、Kotlin 编译和 assembleDebug 均未进入源码编译阶段，不能宣称测试或 APK 通过。
- Gradle wrapper 下载 gradle-8.13-bin.zip 仍因失效代理/网络权限失败：java.net.SocketException: Permission denied: getsockopt。
- 本机 Gradle 离线尝试仍缺少 com.android.tools.build:gradle:8.10.1 和 org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20 缓存；Android SDK 也未配置可用的 ANDROID_HOME/ANDROID_SDK_ROOT 或 local.properties。

### 本次实际执行命令

```powershell
$env:JAVA_HOME='D:\software\AdroidStudio\jbr'; .\gradlew.bat testDebugUnitTest --no-daemon
$env:JAVA_HOME='D:\software\AdroidStudio\jbr'; & 'C:\Users\caid0\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat' testDebugUnitTest --offline --no-daemon
$env:JAVA_HOME='D:\software\AdroidStudio\jbr'; & 'C:\Users\caid0\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat' assembleDebug --offline --no-daemon
```

wrapper 命令在下载 Gradle 8.13 时失败：java.net.SocketException: Permission denied: getsockopt。本机 Gradle 的两个离线命令均在 root classpath 解析阶段失败，提示上述 AGP/Kotlin 插件无缓存；没有生成 APK，也没有运行到源码编译或测试执行。

## Task 6 修复轮次 3

- 修正 AuthFormProcessor.validate 的 Kotlin 参数调用：所有参数显式命名，消除命名参数后接位置参数的编译错误。
- HomeFragment 监听 SessionRepository.error；退出清理失败显示明确 Toast，清理失败时保持当前会话和首页，退出按钮仍可再次尝试。
- 当前环境可见 Gradle 8.13 可执行文件、AGP/Kotlin 插件缓存路径和默认 Android SDK 路径，但 ANDROID_HOME/ANDROID_SDK_ROOT 未设置，且 Gradle 离线解析仍报告插件无可用缓存。

### 修复轮次 3 实际验证

- XML 静态检查通过：app/src/main/res 与 app/src/debug 共 8 个 XML 文件解析成功。
- git diff --check 通过。
- 未进入源码编译，未生成 APK。实际命令：
  - gradle.bat testDebugUnitTest --offline --no-daemon
  - gradle.bat assembleDebug --offline --no-daemon
- 两个离线命令均在 root project classpath 配置阶段失败，原始错误为：No cached version of com.android.tools.build:gradle:8.10.1 available for offline mode；No cached version of org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20 available for offline mode。
- 因此 Kotlin 单元测试、Kotlin 编译和 assembleDebug 均未运行到源码阶段；默认 SDK 目录虽路径可见，但缺少 ANDROID_HOME/ANDROID_SDK_ROOT，不能据此宣称构建成功。

## Task 6 修复轮次 4

- 修正 app/build.gradle.kts：先通过 providers.gradleProperty(...).orElse(...).get() 得到普通字符串，再传入 buildConfigField，避免嵌套引号和美元转义语法错误。
- EncryptedSessionStorage.read() 改为返回 Result<PersistedSession?>，解密或读取异常不再静默吞掉；SessionRepository 在进程恢复时发布 StorageReadFailed，认证页提供可见提示。
- 保留登出清理失败的可见 Toast，失败时继续停留在首页并允许重试。

## 修复轮次 4 验证

- XML 静态检查通过：app/src/main/res 与 app/src/debug 共 8 个 XML 文件解析成功。
- git diff --check 通过。
- 当前可见 Gradle 8.13、插件缓存路径和默认 SDK 路径，但 ANDROID_HOME/ANDROID_SDK_ROOT 未设置。
- 实际未进入源码编译，未生成 APK。使用现有 Gradle 8.13 执行 testDebugUnitTest --offline --no-daemon 与 assembleDebug --offline --no-daemon，均原样失败于 root classpath：No cached version of com.android.tools.build:gradle:8.10.1 available for offline mode；No cached version of org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20 available for offline mode。
- 本轮实际命令：$env:JAVA_HOME='D:\\software\\AdroidStudio\\jbr'; & 'C:\\Users\\caid0\\.gradle\\wrapper\\dists\\gradle-8.13-bin\\5xuhj0ry160q40clulazy9h7d\\gradle-8.13\\bin\\gradle.bat' testDebugUnitTest --offline --no-daemon；以及同路径的 assembleDebug --offline --no-daemon。两条命令均输出 BUILD FAILED in 19s，并在 root project classpath 阶段报告上述两个 No cached version 错误。
