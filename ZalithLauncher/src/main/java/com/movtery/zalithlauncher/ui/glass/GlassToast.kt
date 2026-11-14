package com.movtery.zalithlauncher.ui.glass

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.WeakHashMap

private data class ToastNotice(val owner: Any, val text: CharSequence?, val duration: Int)
private val toastHosts = WeakHashMap<Activity, Channel<ToastNotice>>()
private val toastMain = Handler(Looper.getMainLooper())

/** Foreground launcher notices use glass; background/game/no-host notices retain system Toast. */
class GlassToast private constructor(private val context: Context, private val text: CharSequence, private val duration: Int) {
    private var fallback: Toast? = null
    fun show() {
        toastMain.post {
            val host = context.glassActivity()?.takeIf {
                (it as? androidx.lifecycle.LifecycleOwner)?.lifecycle?.currentState
                    ?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) == true
            }?.let { toastHosts[it] }
            if (host == null || host.trySend(ToastNotice(this, text, duration)).isFailure) {
                fallback = Toast.makeText(context, text, duration).also { it.show() }
            }
        }
    }
    fun cancel() {
        toastMain.post {
            fallback?.cancel()
            context.glassActivity()?.let { toastHosts[it] }?.trySend(ToastNotice(this, null, duration))
        }
    }
    companion object {
        const val LENGTH_SHORT = Toast.LENGTH_SHORT
        const val LENGTH_LONG = Toast.LENGTH_LONG
        @JvmStatic fun makeText(context: Context, text: CharSequence?, duration: Int): GlassToast =
            GlassToast(context, text ?: "", duration)
        @JvmStatic fun makeText(context: Context, resId: Int, duration: Int): GlassToast =
            makeText(context, context.getText(resId), duration)
    }
}

@Composable
internal fun GlassToastHost() {
    val activity = LocalContext.current.glassActivity()
    val active = isGlassActive(GlassScope.Cards)
    val channel = remember { Channel<ToastNotice>(Channel.CONFLATED) }
    var notice by remember { mutableStateOf<ToastNotice?>(null) }
    DisposableEffect(activity, active, channel) {
        if (activity != null && active) toastHosts[activity] = channel
        onDispose { if (toastHosts[activity] === channel) toastHosts.remove(activity) }
    }
    LaunchedEffect(channel, active) {
        notice = null
        if (active) channel.receiveAsFlow().collectLatest { next ->
            if (next.text == null) {
                if (notice?.owner === next.owner) notice = null
            } else {
                notice = next
                delay(if (next.duration == Toast.LENGTH_LONG) 3500L else 2000L)
                notice = null
            }
        }
    }
    val current = notice
    if (active && current?.text != null) {
        val offset = with(LocalDensity.current) { 48.dp.roundToPx() }
        Popup(
            alignment = Alignment.BottomCenter, offset = IntOffset(0, -offset),
            properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            GlassWindowBackdropHost {
                GlassSurface(
                    modifier = Modifier.widthIn(max = 360.dp).padding(horizontal = 24.dp),
                    shape = MaterialTheme.shapes.large, scope = GlassScope.Cards
                ) {
                    Text(
                        text = current.text.toString(), color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite }
                    )
                }
            }
        }
    }
}
