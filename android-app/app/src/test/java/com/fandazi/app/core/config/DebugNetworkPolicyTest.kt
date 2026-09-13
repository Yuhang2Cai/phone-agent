package com.fandazi.app.core.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DebugNetworkPolicyTest {
    @Test
    fun cleartextIsEnabledOnlyByDebugManifest() {
        val appRoot = findAppRoot()
        val mainManifest = File(appRoot, "src/main/AndroidManifest.xml").readText()
        val debugManifest = File(appRoot, "src/debug/AndroidManifest.xml").readText()

        assertFalse(mainManifest.contains("usesCleartextTraffic=\"true\""))
        assertTrue(debugManifest.contains("usesCleartextTraffic=\"true\""))
    }

    private fun findAppRoot(): File {
        var directory = File(
            DebugNetworkPolicyTest::class.java.protectionDomain.codeSource.location.toURI()
        ).absoluteFile
        if (directory.isFile) directory = directory.parentFile
        while (true) {
            val candidate = File(directory, "app/src/main/AndroidManifest.xml")
            if (candidate.isFile) return File(directory, "app")
            directory = directory.parentFile ?: break
        }
        error("Unable to locate Android app root from test classes")
    }
}
