// 构建配置：声明模块、插件和依赖的装配方式。
import java.security.MessageDigest as AsrMessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val debugApiBaseUrl = providers.gradleProperty("debugApiBaseUrl")
    .orElse("http://10.0.2.2:8080/api/")
    .get()
val releaseApiBaseUrl = providers.gradleProperty("releaseApiBaseUrl")
    .orElse("https://api.example.invalid/api/")
    .get()

android {
    namespace = "com.fandazi.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fandazi.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "API_BASE_URL", "\"https://api.example.invalid/api/\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"$debugApiBaseUrl\""
            )
        }
        release {
            isMinifyEnabled = false
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"$releaseApiBaseUrl\""
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // 基线与增量使用release相同压缩策略，只增加本机测量签名、18080入口及可分析Manifest。
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            buildConfigField("String", "API_BASE_URL", "\"$debugApiBaseUrl\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
    // 固定redBook模型沿用未压缩资产，避免读取ONNX时重复解压；不改变既有ABI范围。
    androidResources { noCompress += listOf("onnx", "txt") }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    // 固定来源AAR仅负责本地推理，不迁移Hilt、网络音频或redBook消息依赖。
    implementation(files("libs/sherpa-onnx-1.12.39.aar"))
    // 设备测试依赖版本固定；ASR入口本轮只构建，普通聊天状态测试可实际执行。
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // 只在benchmark提供Macrobenchmark清理编译/着色器所需的接收器，不改生产启动装配。
    "benchmarkImplementation"("androidx.profileinstaller:profileinstaller:1.3.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.fragment:fragment-ktx:1.8.6")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.5")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.github.bumptech.glide:glide:4.16.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// 固定ASR资源必须由受控来源导入，防止缺模型时静默产出只有按钮的不可用APK。
// 模型许可未闭合，文件不进入公开提交；每个隔离构建先运行Import-RedBookAsr.ps1 -IncludeLocalModel。
val asrResourceHashes = mapOf(
    "libs/sherpa-onnx-1.12.39.aar" to "D228A731EC3125170C962E2D52AD3FE012F3616E5047A87E2743D851D69C176D",
    "src/main/assets/sherpa-onnx/sherpa-onnx-paraformer-zh-small-2024-03-09/sherpa-onnx-paraformer-zh-small-2024-03-09/model.int8.onnx" to "3EF6C19369B912F7CAF3CEF8E545C5CCD1A33D9D7EC792A46668DC41C4B229EC",
    "src/main/assets/sherpa-onnx/sherpa-onnx-paraformer-zh-small-2024-03-09/sherpa-onnx-paraformer-zh-small-2024-03-09/tokens.txt" to "4B2D964E18B9CF139B473003B6698FB2ED9A2A5EC55B93DAA677B28F578897AA"
)
val verifyLocalAsrAssets = tasks.register("verifyLocalAsrAssets") {
    doLast {
        // 先检查存在性，再逐块计算摘要；缺失/替换时明确失败，不下载其他模型或忽略检查。
        asrResourceHashes.forEach { (relativePath, expectedHash) ->
            val resource = file(relativePath)
            check(resource.isFile) { "缺少固定ASR资源，请运行 scripts/Import-RedBookAsr.ps1 -IncludeLocalModel：$relativePath" }
            val digest = AsrMessageDigest.getInstance("SHA-256")
            resource.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02X".format(it) }
            check(actualHash == expectedHash) { "固定ASR资源摘要不符：$relativePath" }
        }
    }
}
tasks.named("preBuild") { dependsOn(verifyLocalAsrAssets) }
