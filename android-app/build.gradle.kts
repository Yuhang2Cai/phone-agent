// 构建配置：声明模块、插件和依赖的装配方式。
plugins {
    id("com.android.test") version "8.12.0" apply false
    id("com.android.application") version "8.12.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    // KSP固定匹配现有Kotlin，不升级整个构建工具链。
    id("com.google.devtools.ksp") version "2.1.20-2.0.1" apply false
    // 将 RN 插件置于根工程类路径，自动链接的 SVG 子工程才能用 apply plugin 找到同一实现。
    id("com.facebook.react") apply false
}
// SVG 原生库与宿主共用 SDK；目标行为版本仍保持 35，NDK 固定 RN 0.86 的官方版本。
extra["compileSdkVersion"] = 36
extra["minSdkVersion"] = 26
extra["targetSdkVersion"] = 35
extra["ndkVersion"] = "27.1.12297006"
// 本仓库的 RN 与 Android 是并列目录，SVG 自动链接不能按标准根目录 node_modules 猜路径。
extra["REACT_NATIVE_NODE_MODULES_DIR"] = file("../activity-rn/node_modules/react-native").absolutePath
