// 构建配置：声明模块、插件和依赖的装配方式。
pluginManagement {
    // RN 插件与前端锁文件同源，避免全局 CLI 或隐式版本影响宿主构建。
    includeBuild("../activity-rn/node_modules/@react-native/gradle-plugin")
    resolutionStrategy {
        eachPlugin {
            when (requested.id.id) {
                "com.android.application", "com.android.test" -> useModule("com.android.tools.build:gradle:${requested.version}")
                // 直接使用官方KSP插件模块，避免额外marker坐标改变解析来源。
                "com.google.devtools.ksp" -> useModule("com.google.devtools.ksp:symbol-processing-gradle-plugin:${requested.version}")
                "org.jetbrains.kotlin.android" -> useModule("org.jetbrains.kotlin:kotlin-gradle-plugin:${requested.version}")
            }
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins { id("com.facebook.react.settings") }
extensions.configure<com.facebook.react.ReactSettingsExtension> {
    autolinkLibrariesFromCommand(
        command = listOf("node", "node_modules/@react-native-community/cli/build/bin.js", "config"),
        workingDirectory = file("../activity-rn"),
        lockFiles = files("../activity-rn/package-lock.json", "../activity-rn/package.json", "../activity-rn/react-native.config.js")
    )
}

dependencyResolutionManagement {
    // RN 插件会声明相同官方仓库；仍以本设置的仓库列表为准。
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FandaziAi"
// 测量独立模块只驱动目标应用，不生成或安装Baseline/Startup Profiles。
include(":app", ":benchmark")
includeBuild("../activity-rn/node_modules/@react-native/gradle-plugin")
