// 构建配置：声明模块、插件和依赖的装配方式。
pluginManagement {
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FandaziAi"
// 测量独立模块只驱动目标应用，不生成或安装Baseline/Startup Profiles。
include(":app", ":benchmark")
