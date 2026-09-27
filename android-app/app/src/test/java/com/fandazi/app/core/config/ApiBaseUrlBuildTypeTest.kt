// 构建配置模块：验证关键行为与边界条件。
package com.fandazi.app.core.config

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiBaseUrlBuildTypeTest {
    private val buildScript = findBuildScript().readText()

    @Test
    fun debugAndReleaseHaveExplicitBuildTypeDefaults() {
        val debugDefault = Regex(
            """val debugApiBaseUrl = providers\.gradleProperty\("debugApiBaseUrl"\)\s*\.orElse\("([^"]+)"\)"""
        ).find(buildScript)?.groupValues?.get(1)
        val releaseDefault = Regex(
            """val releaseApiBaseUrl = providers\.gradleProperty\("releaseApiBaseUrl"\)\s*\.orElse\("([^"]+)"\)"""
        ).find(buildScript)?.groupValues?.get(1)
        val defaultConfig = Regex("defaultConfig\\s*\\{([\\s\\S]*?)\\n\\s*\\}").find(buildScript)?.groupValues?.get(1).orEmpty()
        val buildTypes = Regex("buildTypes\\s*\\{([\\s\\S]*)").find(buildScript)?.groupValues?.get(1).orEmpty()

        assertEquals("http://10.0.2.2:8080/api/", debugDefault)
        assertEquals("https://api.example.invalid/api/", releaseDefault)
        assertFalse(defaultConfig.contains("http://"))
        assertTrue(buildTypes.contains("debugApiBaseUrl"))
        assertTrue(buildTypes.contains("releaseApiBaseUrl"))
    }

    private fun findBuildScript(): File {
        var directory = File(
            ApiBaseUrlBuildTypeTest::class.java.protectionDomain.codeSource.location.toURI()
        ).absoluteFile
        if (directory.isFile) directory = directory.parentFile
        while (true) {
            val candidates = listOf(
                File(directory, "android-app/app/build.gradle.kts"),
                File(directory, "app/build.gradle.kts")
            )
            candidates.firstOrNull { it.isFile }?.let { return it }
            directory = directory.parentFile ?: break
        }
        error("Unable to locate android-app/app/build.gradle.kts from test classes")
    }
}
