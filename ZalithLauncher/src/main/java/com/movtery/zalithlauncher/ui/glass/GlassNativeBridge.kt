package com.movtery.zalithlauncher.ui.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionContext
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import java.util.WeakHashMap

internal tailrec fun Context.glassActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.glassActivity()
    else -> null
}

private class NativeGlassEntry(val parent: CompositionContext, var enabled: Boolean)
private val nativeGlassHosts = WeakHashMap<Activity, NativeGlassEntry>()

/** Legacy View dialogs inherit live theme/config locals, but create their own window backdrop. */
@Composable
internal fun RegisterNativeGlassBridge() {
    val activity = LocalContext.current.glassActivity()
    val parent = rememberCompositionContext()
    val enabled = isGlassActive(GlassScope.Dialogs)
    val entry = remember(parent) { NativeGlassEntry(parent, enabled) }
    SideEffect { entry.enabled = enabled }
    DisposableEffect(activity, entry) {
        if (activity != null) nativeGlassHosts[activity] = entry
        onDispose { if (nativeGlassHosts[activity] === entry) nativeGlassHosts.remove(activity) }
    }
}

/** Main-thread onShow hook. No active launcher composition means the original native theme wins. */
fun installNativeGlass(dialog: AlertDialog) {
    val activity = dialog.context.glassActivity() ?: dialog.ownerActivity ?: return
    val entry = nativeGlassHosts[activity]?.takeIf { it.enabled } ?: return
    val content = dialog.window?.decorView?.findViewById<FrameLayout>(android.R.id.content) ?: return
    if (content.findViewWithTag<android.view.View>("xeno-native-glass") != null) return
    val backing = ComposeView(dialog.context).apply {
        tag = "xeno-native-glass"
        isClickable = false
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
        setParentCompositionContext(entry.parent)
        setContent {
            GlassWindowBackdropHost {
                GlassSurface(
                    modifier = Modifier.fillMaxSize(), shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    scope = GlassScope.Dialogs
                ) {}
            }
        }
    }
    content.addView(backing, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    dialog.window?.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
}
