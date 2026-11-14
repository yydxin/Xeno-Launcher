package com.movtery.zalithlauncher.ui.glass

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer

/** Pure wallpaper only: no account, editor, password, dialog or foreground pixels. */
data class GlassWallpaperSnapshot(val image: ImageBitmap, val origin: Offset)

@Stable
class GlassWallpaper {
    internal var layer: GraphicsLayer? = null
    internal var geometry by mutableStateOf(Rect.Zero)
    internal val origin: Offset get() = geometry.topLeft

    /** One readback per opened window, not per glass surface or scrolling frame. */
    suspend fun snapshot(): GlassWallpaperSnapshot? {
        // Layout can change before that frame's draw; allow the pure wallpaper to record
        // at its new size before readback. No polling/timers or continuous capture loop.
        withFrameNanos { }
        withFrameNanos { }
        val source = layer ?: return null
        if (source.size.width <= 0 || source.size.height <= 0) return null
        val snapshotOrigin = origin
        return try {
            GlassWallpaperSnapshot(source.toImageBitmap(), snapshotOrigin)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
}

val LocalGlassWallpaper = staticCompositionLocalOf<GlassWallpaper?> { null }
