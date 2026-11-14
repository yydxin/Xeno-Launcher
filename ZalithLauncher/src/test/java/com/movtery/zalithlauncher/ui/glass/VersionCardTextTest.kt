package com.movtery.zalithlauncher.ui.glass

import com.movtery.zalithlauncher.ui.screens.content.home.version.versionCardTitle
import org.junit.Assert.assertEquals
import org.junit.Test

class VersionCardTextTest {
    @Test fun generatedNameDoesNotRepeatLoaderDetails() {
        assertEquals("Minecraft", versionCardTitle("26.2 Fabric 0.20.1", "26.2, Fabric – 0.20.1"))
    }
    @Test fun customNameIsNotRemoved() {
        assertEquals("我的生存服", versionCardTitle("我的生存服", "26.2, Fabric – 0.20.1"))
        assertEquals("26.2 Fabric 生存", versionCardTitle("26.2 Fabric 生存", "26.2, Fabric – 0.20.1"))
    }
    @Test fun unavailableDetailsKeepOriginalName() {
        assertEquals("实例一", versionCardTitle("实例一", null))
        assertEquals("实例一", versionCardTitle("实例一", ""))
    }
}
