package com.movtery.zalithlauncher.game.renderer.mobileglues

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Added for later execution by the maintainer; not run as part of this change. */
class MobileGluesGlslCachePolicyTest {
    @Test fun `fixed MB limits preserve zero and clamp both ends`() {
        assertEquals(0, MobileGluesGlslCachePolicy.MIN_MB)
        assertEquals(1024, MobileGluesGlslCachePolicy.MAX_MB)
        listOf(Int.MIN_VALUE to 0, -1 to 0, 0 to 0, 512 to 512, 1024 to 1024,
            1025 to 1024, Int.MAX_VALUE to 1024).forEach { (raw, expected) ->
            assertEquals(expected, MobileGluesGlslCachePolicy.normalize(raw))
            assertEquals(expected, MobileGluesConfig(maxGlslCacheSize = raw).normalized().maxGlslCacheSize)
        }
    }

    @Test fun `large JSON integer cannot overflow before clamping`() {
        listOf("1025", "2147483648", "999999999999999999999999", "1e10000").forEach {
            assertEquals(1024, MobileGluesGlslCachePolicy.parse(it))
            assertEquals(1024, MobileGluesConfig.fromJson("{\"maxGlslCacheSize\":$it}").maxGlslCacheSize)
        }
        assertEquals(0, MobileGluesGlslCachePolicy.parse("-99999999999999999999999"))
        assertEquals(0, MobileGluesConfig.fromJson("{\"maxGlslCacheSize\":0}").maxGlslCacheSize)
        assertEquals(1024, MobileGluesConfig.fromJson("{\"maxGlslCacheSize\":\"4096\"}").maxGlslCacheSize)
        assertThrows(IllegalArgumentException::class.java) { MobileGluesGlslCachePolicy.parse("0.5") }
    }

    @Test fun `every supported MB value round trips through the same slider coordinate`() {
        for (value in 0..1024) {
            val slider = MobileGluesGlslCachePolicy.valueToSlider(value)
            assertEquals(value.toFloat(), slider, 0f)
            assertEquals(value, MobileGluesGlslCachePolicy.sliderToValue(slider))
        }
        assertEquals(0, MobileGluesGlslCachePolicy.sliderToValue(-100f))
        assertEquals(1024, MobileGluesGlslCachePolicy.sliderToValue(100000f))
        assertThrows(IllegalArgumentException::class.java) { MobileGluesGlslCachePolicy.sliderToValue(Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { MobileGluesGlslCachePolicy.sliderToValue(Float.POSITIVE_INFINITY) }
    }
}
