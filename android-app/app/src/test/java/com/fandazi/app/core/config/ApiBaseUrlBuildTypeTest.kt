package com.fandazi.app.core.config

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiBaseUrlBuildTypeTest {
    private val buildScript = File("app/build.gradle.kts").readText()

    @Test
    fun debugAndReleaseHaveExplicitBuildTypeDefaults() {
        val debug = Regex("debug\\s*\\{([\\s\\S]*?)\\n\\s*release\\s*\\{").find(buildScript)?.groupValues?.get(1).orEmpty()
        val release = Regex("release\\s*\\{([\\s\\S]*?)\\n\\s*\\}").find(buildScript)?.groupValues?.get(1).orEmpty()

        assertTrue(debug.contains("http://10.0.2.2"))
        assertTrue(release.contains("https://api.example.invalid"))
        assertFalse(Regex("defaultConfig\\s*\\{[\\s\\S]*?http://").containsMatchIn(buildScript))
    }
}
