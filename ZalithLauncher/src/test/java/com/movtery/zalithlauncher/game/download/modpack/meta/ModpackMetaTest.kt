package com.movtery.zalithlauncher.game.download.modpack.meta

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModpackMetaTest {
    private fun tempDir(): File = Files.createTempDirectory("zl2-meta-test").toFile()

    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    @Test
    fun `fileScope excludes version metadata, protected paths and user data`() {
        val dir = tempDir()
        write(File(dir, "1.20.1.json"), "{}")
        write(File(dir, "1.20.1.jar"), "jar")
        write(File(dir, "modpack_meta.json"), "{}")
        write(File(dir, ".zalith/VersionIcon.png"), "icon")
        write(File(dir, "mods/a.jar"), "a")
        write(File(dir, "config/b.toml"), "b")
        write(File(dir, "resourcepacks/c.zip"), "c")
        write(File(dir, "saves/world/level.dat"), "w")
        write(File(dir, "options.txt"), "fov:70")
        write(File(dir, "servers.dat"), "srv")
        write(File(dir, "journeymap/data/x"), "jm")

        val scope = ModpackPaths.buildFileScope(dir, "1.20.1")
        assertTrue(scope.contains("mods/a.jar"))
        assertTrue(scope.contains("config/b.toml"))
        assertTrue(scope.contains("resourcepacks/c.zip"))
        assertFalse(scope.any { it.startsWith("saves/") })
        assertFalse(scope.contains("options.txt"))
        assertFalse(scope.contains("servers.dat"))
        assertFalse(scope.any { it.startsWith(".zalith/") })
        assertFalse(scope.contains("1.20.1.json"))
        assertFalse(scope.contains("modpack_meta.json"))
    }

    @Test
    fun `loadValid accepts known platform and rejects missing or invalid meta`() {
        val dir = tempDir()
        assertNull(ModpackMeta.loadValid(dir))

        val valid = ModpackMeta(
            platform = "modrinth",
            projectId = "1KVo5zza",
            versionId = "abc",
            versionName = "1.0.0",
            gameVersion = "1.20.1",
            loader = "fabric",
            fileScope = listOf("mods/a.jar")
        )
        ModpackMeta.save(dir, valid)
        val loaded = ModpackMeta.loadValid(dir)
        assertEquals("modrinth", loaded?.platform)
        assertEquals(listOf("mods/a.jar"), loaded?.fileScope)

        ModpackMeta.save(dir, valid.copy(platform = "unknown-platform"))
        assertNull(ModpackMeta.loadValid(dir))
    }

    @Test
    fun `malformed meta is rejected without throwing`() {
        val dir = tempDir()
        write(ModpackMeta.metaFile(dir), "{ not valid json")
        assertNull(ModpackMeta.loadValid(dir))
        assertNull(ModpackMeta.load(dir))
    }
}
