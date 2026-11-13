package com.movtery.zalithlauncher.ui.screens.content.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.movtery.zalithlauncher.game.renderer.mobileglues.MobileGluesConfig
import com.movtery.zalithlauncher.game.renderer.mobileglues.MobileGluesConfigDebugLog
import com.movtery.zalithlauncher.game.renderer.mobileglues.MobileGluesGlslCachePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Valid/missing loads never save; only a reader's explicit out-of-range repair may write. */
interface MobileGluesSettingsStore {
    fun load(): Result<MobileGluesConfig>
    fun save(config: MobileGluesConfig): Boolean
}

private object DiskMobileGluesSettingsStore : MobileGluesSettingsStore {
    override fun load() = MobileGluesConfig.loadStrict().onSuccess { MobileGluesConfigDebugLog.auditRendererSnapshot(it) }
    override fun save(config: MobileGluesConfig) = MobileGluesConfig.save(config)
}

data class MobileGluesSettingsState(
    val config: MobileGluesConfig? = null,
    val persistedConfig: MobileGluesConfig? = null,
    val cacheDraft: Int? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null
) {
    val cacheValue: Int? get() = cacheDraft ?: config?.maxGlslCacheSize
    val editable: Boolean get() = config != null && !loading && !saving
}

/** A single flow owns the track, MB label, arrow controls and unfinished user edits. */
class MobileGluesSettingsViewModel(
    private val store: MobileGluesSettingsStore = DiskMobileGluesSettingsStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val mutableState = MutableStateFlow(MobileGluesSettingsState())
    val state = mutableState.asStateFlow()
    private val notifications = Channel<Boolean>(Channel.BUFFERED)
    val saveResults = notifications.receiveAsFlow()
    private var reading = false

    fun reload() {
        if (reading || mutableState.value.saving) return
        reading = true
        val before = mutableState.value
        // Never render a default while inspecting disk. Preserve non-cache edits on resume.
        mutableState.value = before.copy(loading = true, cacheDraft = null, error = null)
        viewModelScope.launch {
            try {
                val result = withContext(ioDispatcher) { store.load() }
                mutableState.value = result.fold(
                    onSuccess = { loaded -> MobileGluesSettingsState(
                        config = mergeEdits(before.persistedConfig, before.config, loaded),
                        persistedConfig = loaded, loading = false
                    ) },
                    onFailure = { MobileGluesSettingsState(loading = false, error = it.message ?: "Configuration read failed") }
                )
            } finally { reading = false }
        }
    }

    fun update(transform: (MobileGluesConfig) -> MobileGluesConfig) {
        val current = mutableState.value
        if (!current.editable) return
        mutableState.value = current.copy(config = transform(current.config!!), error = null)
    }

    fun previewCache(value: Int) {
        val current = mutableState.value
        if (!current.editable) return
        mutableState.value = current.copy(cacheDraft = value.coerceIn(
            MobileGluesGlslCachePolicy.MIN_MB, MobileGluesGlslCachePolicy.MAX_MB
        ))
    }

    fun commitCache() {
        val current = mutableState.value
        val draft = current.cacheDraft ?: return // programmatic assignments cannot save
        if (!current.editable) return
        persist(current, cacheOnly = true) { disk -> disk.copy(maxGlslCacheSize = draft) }
    }

    fun save() {
        val current = mutableState.value
        if (!current.editable || current.config!!.multidrawOrder.isBlank()) return
        // Normalize once; the verified result, not the raw text draft, becomes visible.
        val target = current.config.copy(maxGlslCacheSize = current.cacheValue!!).normalized()
        persist(current, cacheOnly = false) { target }
    }

    fun reset() {
        val current = mutableState.value
        if (!current.editable) return
        persist(current, cacheOnly = false) { MobileGluesConfig() }
    }

    private fun persist(
        before: MobileGluesSettingsState,
        cacheOnly: Boolean,
        target: (MobileGluesConfig) -> MobileGluesConfig
    ) {
        mutableState.value = before.copy(saving = true, error = null)
        viewModelScope.launch {
            val verified = withContext(ioDispatcher) {
                runCatching {
                    // Cache finish only overlays its field on the latest disk snapshot. It never
                    // silently commits unrelated unsaved options or an invalid multidraw draft.
                    val pending = target(store.load().getOrThrow())
                    check(store.save(pending)) { "Atomic write or read-back verification failed; reload required" }
                    val actual = store.load().getOrThrow()
                    check(actual == pending) { "Read-back differs from requested configuration; reload required" }
                    actual
                }
            }
            mutableState.value = verified.fold(
                onSuccess = { actual -> MobileGluesSettingsState(
                    config = if (cacheOnly) mergeEdits(before.persistedConfig, before.config, actual) else actual,
                    persistedConfig = actual, loading = false
                ) },
                onFailure = { failure ->
                    // Atomic replacement may already have happened. Never pretend disk rolled
                    // back, show an old value, or allow another overwrite until a strict reload.
                    MobileGluesSettingsState(loading = false, error = failure.message ?: "Save failed; reload required")
                }
            )
            notifications.send(verified.isSuccess)
        }
    }
}

/** Preserve only locally edited non-cache fields; adopt the latest committed cache unconditionally. */
private fun mergeEdits(
    baseline: MobileGluesConfig?, edited: MobileGluesConfig?, disk: MobileGluesConfig
): MobileGluesConfig {
    if (baseline == null || edited == null) return disk
    return disk.copy(
        enableANGLE = if (edited.enableANGLE != baseline.enableANGLE) edited.enableANGLE else disk.enableANGLE,
        enableNoError = if (edited.enableNoError != baseline.enableNoError) edited.enableNoError else disk.enableNoError,
        enableExtComputeShader = if (edited.enableExtComputeShader != baseline.enableExtComputeShader) edited.enableExtComputeShader else disk.enableExtComputeShader,
        enableExtTimerQuery = if (edited.enableExtTimerQuery != baseline.enableExtTimerQuery) edited.enableExtTimerQuery else disk.enableExtTimerQuery,
        enableExtDirectStateAccess = if (edited.enableExtDirectStateAccess != baseline.enableExtDirectStateAccess) edited.enableExtDirectStateAccess else disk.enableExtDirectStateAccess,
        angleDepthClearFixMode = if (edited.angleDepthClearFixMode != baseline.angleDepthClearFixMode) edited.angleDepthClearFixMode else disk.angleDepthClearFixMode,
        customGLVersion = if (edited.customGLVersion != baseline.customGLVersion) edited.customGLVersion else disk.customGLVersion,
        fsr1Setting = if (edited.fsr1Setting != baseline.fsr1Setting) edited.fsr1Setting else disk.fsr1Setting,
        hideMGEnvLevel = if (edited.hideMGEnvLevel != baseline.hideMGEnvLevel) edited.hideMGEnvLevel else disk.hideMGEnvLevel,
        multidrawOrder = if (edited.multidrawOrder != baseline.multidrawOrder) edited.multidrawOrder else disk.multidrawOrder,
        multidrawOrderArrays = if (edited.multidrawOrderArrays != baseline.multidrawOrderArrays) edited.multidrawOrderArrays else disk.multidrawOrderArrays,
        multidrawOrderElements = if (edited.multidrawOrderElements != baseline.multidrawOrderElements) edited.multidrawOrderElements else disk.multidrawOrderElements,
        multidrawOrderElementsBaseVertex = if (edited.multidrawOrderElementsBaseVertex != baseline.multidrawOrderElementsBaseVertex) edited.multidrawOrderElementsBaseVertex else disk.multidrawOrderElementsBaseVertex,
        multidrawOrderArraysIndirect = if (edited.multidrawOrderArraysIndirect != baseline.multidrawOrderArraysIndirect) edited.multidrawOrderArraysIndirect else disk.multidrawOrderArraysIndirect,
        multidrawOrderElementsIndirect = if (edited.multidrawOrderElementsIndirect != baseline.multidrawOrderElementsIndirect) edited.multidrawOrderElementsIndirect else disk.multidrawOrderElementsIndirect
    )
}
