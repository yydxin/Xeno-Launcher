package com.movtery.zalithlauncher.security

import com.movtery.zalithlauncher.ai.tools.LauncherPathGuard
import com.movtery.zalithlauncher.game.download.modpack.meta.ModpackPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 红线回归测试：受保护路径必须被代码层拒绝，防路径穿越。
 * 不依赖 Android 运行时（JVM 单测）。
 */
class ProtectedPathTest {

    @Test
    fun modpackProtectedPaths() {
        listOf(
            "saves/world/level.dat",
            "saves/New World/region/r.0.0.mca",
            "options.txt",
            "servers.dat",
            "screenshots/2026.png",
            "logs/latest.log",
            "crash-reports/crash-1.txt",
            "world/level.dat",
            "playerdata/abc.dat"
        ).forEach { path ->
            assertTrue("应受保护: $path", ModpackPaths.isProtectedPath(path))
        }

        listOf(
            "mods/sodium.jar",
            "config/sodium-options.json",
            "resourcepacks/pack.zip",
            "shaderpacks/bsl.zip"
        ).forEach { path ->
            assertFalse("不应受保护: $path", ModpackPaths.isProtectedPath(path))
        }
    }

    @Test
    fun toolLayerPathTraversal() {
        val root = Files.createTempDirectory("xzlc-path-test").toFile()
        try {
            assertEquals(
                File(root, "mods/a.jar").canonicalFile,
                LauncherPathGuard.resolveInside(root, "mods/a.jar")
            )
            assertNull(LauncherPathGuard.resolveInside(root, "../escape.txt"))
            assertNull(LauncherPathGuard.resolveInside(root, "/etc/passwd"))
            assertNull(LauncherPathGuard.resolveInside(root, "saves/world/level.dat"))
            assertNull(LauncherPathGuard.resolveInside(root, "logs/latest.log"))
        } finally {
            root.deleteRecursively()
        }
    }
}
