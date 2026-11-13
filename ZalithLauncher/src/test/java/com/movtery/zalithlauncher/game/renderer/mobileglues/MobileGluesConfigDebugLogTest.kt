package com.movtery.zalithlauncher.game.renderer.mobileglues

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** These tests are provided, not executed. */
class MobileGluesConfigDebugLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `debug switch is solely a persistent marker and disabling keeps the log`() {
        val directory = temporary.newFolder()
        assertFalse(MobileGluesConfigDebugLog.isEnabled(directory))
        assertTrue(MobileGluesConfigDebugLog.setEnabled(true, directory))
        assertTrue(File(directory, MobileGluesConfigDebugLog.FILE_NAME_MARKER).isFile)
        assertTrue(MobileGluesConfigDebugLog.isEnabled(File(directory.absolutePath)))
        assertEquals(512f, MobileGluesConfigDebugLog.valueToSlider(512, directory), 0f)
        assertEquals(512, MobileGluesConfigDebugLog.sliderToValue(512f, directory))
        MobileGluesConfigDebugLog.flushPendingMappings()
        val logFile = File(directory, MobileGluesConfigDebugLog.FILE_NAME_LOG)
        val logged = logFile.readText()
        assertTrue(logged.contains("value->slider raw=512 normalized=512 slider=512.0"))
        assertTrue(logged.contains("slider->value slider=512.0 normalized=512"))
        assertTrue(MobileGluesConfigDebugLog.setEnabled(false, directory))
        assertFalse(MobileGluesConfigDebugLog.isEnabled(directory))
        assertTrue(logFile.isFile)
        MobileGluesConfigDebugLog.debug(directory, "must-not-be-written")
        assertFalse(logFile.readText().contains("must-not-be-written"))
    }

    @Test fun `exceptions are written even while verbose diagnostics are disabled`() {
        val directory = temporary.newFolder()
        assertFalse(MobileGluesConfigDebugLog.isEnabled(directory))
        MobileGluesConfigDebugLog.error(directory, "injected failure", IOException("test-only reason"))
        val log = File(directory, MobileGluesConfigDebugLog.FILE_NAME_LOG).readText()
        assertTrue(log.contains("ERROR injected failure"))
        assertTrue(log.contains("java.io.IOException: test-only reason"))
    }
}
