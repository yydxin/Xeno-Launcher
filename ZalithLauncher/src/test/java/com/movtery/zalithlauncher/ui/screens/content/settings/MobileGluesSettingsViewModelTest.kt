package com.movtery.zalithlauncher.ui.screens.content.settings

import com.movtery.zalithlauncher.game.renderer.mobileglues.MobileGluesConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Added for the user's later validation; intentionally not executed during APK delivery. */
@OptIn(ExperimentalCoroutinesApi::class)
class MobileGluesSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeStore(var stored: MobileGluesConfig = MobileGluesConfig(maxGlslCacheSize = 512)) : MobileGluesSettingsStore {
        var writes = 0
        var readFailure = false
        var wrongReadback = false
        override fun load(): Result<MobileGluesConfig> = if (readFailure) Result.failure(Exception("bad JSON"))
            else Result.success(if (wrongReadback && writes > 0) stored.copy(maxGlslCacheSize = 0) else stored)
        override fun save(config: MobileGluesConfig): Boolean { writes++; stored = config.normalized(); return true }
    }

    @Test fun beforeAndAfterLoadNeverSaves() = runTest(dispatcher) {
        val disk = FakeStore()
        val model = MobileGluesSettingsViewModel(disk, dispatcher)
        assertNull(model.state.value.config)
        model.previewCache(0)
        model.commitCache()
        model.save()
        assertEquals(0, disk.writes)
        model.reload()
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.config)
        advanceUntilIdle()
        assertEquals(512, model.state.value.cacheValue)
        model.commitCache() // no user preview: must not save after loading
        assertEquals(0, disk.writes)
    }

    @Test fun dragPreviewAndAllFinishControlsUseOneState() = runTest(dispatcher) {
        val disk = FakeStore()
        val model = MobileGluesSettingsViewModel(disk, dispatcher)
        model.reload(); advanceUntilIdle()
        model.previewCache(608)
        assertEquals(608, model.state.value.cacheValue)
        assertEquals(512, model.state.value.config!!.maxGlslCacheSize)
        assertEquals(0, disk.writes)
        model.commitCache(); advanceUntilIdle()
        assertEquals(608, disk.stored.maxGlslCacheSize)
        assertNull(model.state.value.cacheDraft)
        // An arrow dispatches the same preview + finish contract as drag/text confirmation.
        model.previewCache(model.state.value.cacheValue!! - 1)
        model.commitCache(); advanceUntilIdle()
        assertEquals(607, model.state.value.cacheValue)
        model.reload(); advanceUntilIdle()
        assertEquals(607, model.state.value.cacheValue)
        assertEquals(2, disk.writes)
    }

    @Test fun zeroSurvivesNewViewModelAndReload() = runTest(dispatcher) {
        val disk = FakeStore()
        val model = MobileGluesSettingsViewModel(disk, dispatcher)
        model.reload(); advanceUntilIdle()
        model.previewCache(0); model.commitCache(); advanceUntilIdle()
        val reopened = MobileGluesSettingsViewModel(disk, dispatcher)
        reopened.reload(); advanceUntilIdle()
        assertEquals(0, reopened.state.value.cacheValue)
        assertEquals(1, disk.writes)
    }

    @Test fun readFailureCannotSaveDefaults() = runTest(dispatcher) {
        val disk = FakeStore().apply { readFailure = true }
        val model = MobileGluesSettingsViewModel(disk, dispatcher)
        model.reload(); advanceUntilIdle()
        assertNull(model.state.value.config)
        assertFalse(model.state.value.editable)
        model.previewCache(0); model.commitCache(); model.save()
        assertEquals(0, disk.writes)
        assertNotNull(model.state.value.error)
    }

    @Test fun mismatchedReadbackProducesFailureNotSuccess() = runTest(dispatcher) {
        val disk = FakeStore().apply { wrongReadback = true }
        val model = MobileGluesSettingsViewModel(disk, dispatcher)
        model.reload(); advanceUntilIdle()
        model.previewCache(483); model.commitCache(); advanceUntilIdle()
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.cacheDraft)
        assertFalse(model.state.value.saving)
        assertFalse(model.state.value.editable)
        assertNull(model.state.value.cacheValue) // never display the old value as if disk rolled back
        model.save(); advanceUntilIdle()
        assertEquals(1, disk.writes)
    }

    @Test fun cacheCommitDoesNotCommitOtherDraftsOrBlankMultidraw() = runTest(dispatcher) {
        val disk = FakeStore()
        val model = MobileGluesSettingsViewModel(disk, dispatcher)
        model.reload(); advanceUntilIdle()
        model.update { it.copy(enableANGLE = 3, multidrawOrder = "") }
        model.previewCache(483); model.commitCache(); advanceUntilIdle()
        assertEquals(483, disk.stored.maxGlslCacheSize)
        assertEquals(0, disk.stored.enableANGLE)
        assertEquals("native", disk.stored.multidrawOrder)
        assertEquals(3, model.state.value.config!!.enableANGLE)
        assertEquals("", model.state.value.config!!.multidrawOrder)
        assertNull(model.state.value.error)
    }

    @Test fun resumePreservesOnlyLocalEditsWhileAdoptingDiskCache() = runTest(dispatcher) {
        val disk = FakeStore()
        val model = MobileGluesSettingsViewModel(disk, dispatcher)
        model.reload(); advanceUntilIdle()
        model.update { it.copy(enableANGLE = 3, multidrawOrderArrays = "angle") }
        disk.stored = disk.stored.copy(maxGlslCacheSize = 608, fsr1Setting = 2)
        model.reload(); advanceUntilIdle() // resume/tab switch/debug toggle
        assertEquals(608, model.state.value.cacheValue)
        assertEquals(3, model.state.value.config!!.enableANGLE)
        assertEquals("angle", model.state.value.config!!.multidrawOrderArrays)
        assertEquals(2, model.state.value.config!!.fsr1Setting)
        assertEquals(0, disk.writes)
    }

    @Test fun textNormalizationIsSuccessfulAndShownFromReadback() = runTest(dispatcher) {
        val disk = FakeStore()
        val model = MobileGluesSettingsViewModel(disk, dispatcher)
        model.reload(); advanceUntilIdle()
        model.update { it.copy(multidrawOrder = " native ", multidrawOrderArrays = " ") }
        model.save(); advanceUntilIdle()
        assertNull(model.state.value.error)
        assertEquals("native", model.state.value.config!!.multidrawOrder)
        assertEquals("", model.state.value.config!!.multidrawOrderArrays)
        assertEquals(1, disk.writes)
    }
}
