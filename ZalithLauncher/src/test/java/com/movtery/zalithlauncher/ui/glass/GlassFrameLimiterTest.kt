/*
 * XenoLauncher - unofficial community fork of ZalithLauncher2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.movtery.zalithlauncher.ui.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassFrameLimiterTest {
    @Test fun `scrolling records about thirty frames per second across refresh rates`() {
        for (hz in listOf(60, 90, 120)) {
            val limiter = GlassFrameLimiter()
            val recorded = (0 until hz).count { limiter.shouldRecord(it * 1_000_000_000L / hz, true) }
            assertTrue("$hz Hz recorded $recorded frames", recorded in 29..31)
        }
    }

    @Test fun `stop scrolling and disabled throttle restore every frame immediately`() {
        val limiter = GlassFrameLimiter()
        assertTrue(limiter.shouldRecord(0, true))
        assertFalse(limiter.shouldRecord(8_000_000, true))
        assertTrue(limiter.shouldRecord(9_000_000, false))
        assertTrue(limiter.shouldRecord(10_000_000, false))
    }

    @Test fun `throttle boundary does not lose a pending frame`() {
        val limiter = GlassFrameLimiter()
        assertTrue(limiter.shouldRecord(1_000_000, true))
        assertFalse(limiter.shouldRecord(33_999_999, true))
        assertTrue(limiter.shouldRecord(34_000_000, true))
        assertEquals(120, (1..120).count { limiter.shouldRecord(34_000_000L + it, false) })
    }
}
