package com.movtery.zalithlauncher.game.renderer.mobileglues

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Added only; user explicitly requested no test execution. */
class MobileGluesConfigTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun configFile(directory: File) = File(directory, MobileGluesConfig.FILE_NAME_CONFIG)

    @Test fun `missing strict load returns defaults without creating config`() {
        val directory = temporary.newFolder()
        assertEquals(MobileGluesConfig(), MobileGluesConfig.loadStrict(directory).getOrThrow())
        assertFalse(configFile(directory).exists())
    }

    @Test fun `strict load rejects broken JSON and malformed known fields without modifying it`() {
        val directory = temporary.newFolder()
        val file = configFile(directory)
        listOf("", "{broken", "[]", "{} trailing", "{\"maxGlslCacheSize\":null}",
            "{\"maxGlslCacheSize\":false}", "{\"maxGlslCacheSize\":1.5}",
            "{\"maxGlslCacheSize\":\"oops\"}", "{\"enableANGLE\":{}}", "{\"multidrawOrder\":5}").forEach { broken ->
            file.writeText(broken)
            assertTrue("must fail: $broken", MobileGluesConfig.loadStrict(directory).isFailure)
            assertEquals(broken, file.readText())
            assertFalse(MobileGluesConfig.save(MobileGluesConfig(maxGlslCacheSize = 64), directory))
            assertEquals(broken, file.readText())
        }
    }

    @Test fun `strict read clamps cache and save preserves other fields and unknown nested keys`() {
        val directory = temporary.newFolder()
        val file = configFile(directory)
        file.writeText("""{"enableANGLE":3,"fsr1Setting":2,"maxGlslCacheSize":99999999999999999,"multidrawOrder":"native,angle","multidrawOrderArrays":"angle","future":{"enabled":true,"items":[1,2]},"futureNull":null}""")
        val loaded = MobileGluesConfig.loadStrict(directory).getOrThrow()
        assertEquals(1024, loaded.maxGlslCacheSize)
        assertEquals(3, loaded.enableANGLE)
        assertEquals(2, loaded.fsr1Setting)
        assertTrue(MobileGluesConfig.save(loaded.copy(maxGlslCacheSize = 0), directory))
        val saved = MobileGluesConfig.loadStrict(directory).getOrThrow()
        assertEquals(loaded.copy(maxGlslCacheSize = 0), saved)
        val root = JsonParser.parseString(file.readText()).asJsonObject
        assertTrue(root.getAsJsonObject("future").get("enabled").asBoolean)
        assertEquals(2, root.getAsJsonObject("future").getAsJsonArray("items").size())
        assertTrue(root.get("futureNull").isJsonNull)
        assertTrue(MobileGluesConfig.save(saved.copy(multidrawOrderArrays = ""), directory))
        assertFalse(JsonParser.parseString(file.readText()).asJsonObject.has("multidrawOrderArrays"))
    }

    @Test fun `valid load never writes and range repair changes exactly cache field`() {
        val directory = temporary.newFolder()
        val file = configFile(directory)
        val valid = """{"maxGlslCacheSize":512,"enableANGLE":3,"future":{"a":[1,2]}}"""
        file.writeText(valid)
        assertEquals(512, MobileGluesConfig.loadStrict(directory).getOrThrow().maxGlslCacheSize)
        assertEquals(valid, file.readText()) // even whitespace/key layout stays untouched
        listOf(-9, 4096).forEach { bad ->
            val original = JsonParser.parseString(valid).asJsonObject.apply { addProperty("maxGlslCacheSize", bad) }
            file.writeText(original.toString())
            val expected = original.deepCopy().apply { addProperty("maxGlslCacheSize", bad.coerceIn(0, 1024)) }
            assertEquals(bad.coerceIn(0, 1024), MobileGluesConfig.loadStrict(directory).getOrThrow().maxGlslCacheSize)
            assertEquals(expected, JsonParser.parseString(file.readText()))
        }
        val zero = """{"maxGlslCacheSize":0,"future":true}"""
        file.writeText(zero)
        assertEquals(0, MobileGluesConfig.loadStrict(directory).getOrThrow().maxGlslCacheSize)
        assertEquals(zero, file.readText())
        assertFalse(MobileGluesConfig.save(MobileGluesConfig(maxGlslCacheSize = -1), directory))
        assertEquals(zero, file.readText())
    }

    @Test fun `legacy numeric strings or decimal spellings become integer JSON on read`() {
        val directory = temporary.newFolder()
        val file = configFile(directory)
        listOf("\"512\"", "512.0", "5.12e2", "\"0\"").forEach { value ->
            file.writeText("{\"maxGlslCacheSize\":$value,\"future\":42}")
            val result = MobileGluesConfig.loadStrict(directory).getOrThrow()
            val root = JsonParser.parseString(file.readText()).asJsonObject
            assertTrue(root.get("maxGlslCacheSize").asJsonPrimitive.isNumber)
            assertEquals(result.maxGlslCacheSize.toString(), root.get("maxGlslCacheSize").asString)
            assertEquals(42, root.get("future").asInt)
        }
    }

    @Test fun `unsupported atomic replacement leaves the original intact and removes only temporary file`() {
        val directory = temporary.newFolder()
        val file = configFile(directory)
        val original = "{\"enableANGLE\":3,\"maxGlslCacheSize\":512,\"unknown\":7}"
        file.writeText(original)
        val config = MobileGluesConfig.loadStrict(directory).getOrThrow().copy(maxGlslCacheSize = 1024)
        assertFalse(MobileGluesConfig.save(config, directory) { source, target ->
            assertTrue(source.isFile)
            assertEquals(original, target.readText())
            throw AtomicMoveNotSupportedException(source.path, target.path, "injected")
        })
        assertEquals(original, file.readText())
        assertTrue(directory.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    @Test fun `save immediately notices a post-replace readback mismatch`() {
        val directory = temporary.newFolder()
        assertFalse(MobileGluesConfig.save(MobileGluesConfig(maxGlslCacheSize = 512), directory) { source, target ->
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            target.writeText("{}") // Simulate external mutation before the mandatory readback.
        })
        assertTrue(File(directory, MobileGluesConfigDebugLog.FILE_NAME_LOG).readText().contains("readback mismatch"))
    }

    @Test fun `launch normalization preserves unknown keys and valid non-cache settings`() {
        val directory = temporary.newFolder()
        val file = configFile(directory)
        MobileGluesConfigDebugLog.setEnabled(true, directory)
        file.writeText("{\"enableANGLE\":3,\"maxGlslCacheSize\":4096,\"unknown\":{\"x\":1}}")
        val prepared = MobileGluesConfig.ensureValid(directory)
        assertEquals(1024, prepared.maxGlslCacheSize)
        assertEquals(3, prepared.enableANGLE)
        assertEquals(prepared, MobileGluesConfig.loadStrict(directory).getOrThrow())
        assertEquals(1, JsonParser.parseString(file.readText()).asJsonObject.getAsJsonObject("unknown").get("x").asInt)
        val log = File(directory, MobileGluesConfigDebugLog.FILE_NAME_LOG).readText()
        assertTrue(log.contains("MG launch read config MG_DIR_PATH=${directory.absolutePath}"))
        assertTrue(log.contains("raw="))
        assertTrue(log.contains("normalized="))
        assertTrue(log.contains("write readback"))
    }

    @Test fun `launch salvage backs up original without resetting valid unrelated fields`() {
        val directory = temporary.newFolder()
        val file = configFile(directory)
        val original = "{\"enableANGLE\":3,\"maxGlslCacheSize\":\"broken\",\"future\":42}"
        file.writeText(original)
        assertEquals(3, MobileGluesConfig.ensureValid(directory).enableANGLE)
        assertEquals(original, File(directory, "config.json.broken").readText())
        val root = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals(3, root.get("enableANGLE").asInt)
        assertEquals(42, root.get("future").asInt)
    }
}
