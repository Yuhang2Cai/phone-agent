package com.fandazi.app.core.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DebugNetworkPolicyTest {
    @Test
    fun cleartextIsEnabledOnlyByDebugManifest() {
        val projectRoot = File(System.getProperty("user.dir"))
        val appRoot = if (File(projectRoot, "app/src/main/AndroidManifest.xml").exists()) {
            File(projectRoot, "app")
        } else {
            projectRoot
        }
        val mainManifest = File(appRoot, "src/main/AndroidManifest.xml").readText()
        val debugManifest = File(appRoot, "src/debug/AndroidManifest.xml").readText()

        assertFalse(mainManifest.contains("usesCleartextTraffic=\"true\""))
        assertTrue(debugManifest.contains("usesCleartextTraffic=\"true\""))
    }
}
