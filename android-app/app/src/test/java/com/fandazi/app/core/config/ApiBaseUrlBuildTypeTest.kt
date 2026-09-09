package com.fandazi.app.core.config

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiBaseUrlBuildTypeTest {
    private val buildScript = findBuildScript().readText()

    @Test
    fun debugAndReleaseHaveExplicitBuildTypeDefaults() {
        val debug = Regex("debug\\s*\\{([\\s\\S]*?)\\n\\s*release\\s*\\{").find(buildScript)?.groupValues?.get(1).orEmpty()
        val release = Regex("release\\s*\\{([\\s\\S]*?)\\n\\s*\\}").find(buildScript)?.groupValues?.get(1).orEmpty()

        assertTrue(debug.contains("http://10.0.2.2"))
        assertTrue(release.contains("https://api.example.invalid"))
        assertFalse(Regex("defaultConfig\\s*\\{[\\s\\S]*?http://").containsMatchIn(buildScript))
    }

    private fun findBuildScript(): File {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (true) {
            val candidates = listOf(
                File(directory, "android-app/app/build.gradle.kts"),
                File(directory, "app/build.gradle.kts")
            )
            candidates.firstOrNull { it.isFile }?.let { return it }
            directory = directory.parentFile ?: break
        }
        error("Unable to locate android-app/app/build.gradle.kts from \${System.getProperty("user.dir")}")
    }
}
