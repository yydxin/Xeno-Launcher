package com.movtery.zalithlauncher.game.renderer.mobileglues

import java.math.BigDecimal
import kotlin.math.roundToInt

/** Fixed MB policy, independent of RAM size. Zero disables the MG cache. */
object MobileGluesGlslCachePolicy {
    const val MIN_MB = 0
    const val MAX_MB = 1024

    fun normalize(value: Int): Int = value.coerceIn(MIN_MB, MAX_MB)

    /** Parses before narrowing to Int, so huge JSON numbers cannot wrap around. */
    fun parse(raw: String): Int {
        val number = raw.trim().toBigDecimal()
        require(number.stripTrailingZeros().scale() <= 0) { "GLSL cache size must be an integer MB value" }
        return when {
            number < BigDecimal.valueOf(MIN_MB.toLong()) -> MIN_MB
            number > BigDecimal.valueOf(MAX_MB.toLong()) -> MAX_MB
            else -> number.intValueExact()
        }
    }

    /** Slider coordinates are MB, not a RAM-dependent percentage or tier index. */
    fun valueToSlider(value: Int): Float = normalize(value).toFloat()

    fun sliderToValue(slider: Float): Int {
        require(slider.isFinite()) { "GLSL cache slider must be finite" }
        return normalize(slider.coerceIn(MIN_MB.toFloat(), MAX_MB.toFloat()).roundToInt())
    }
}
