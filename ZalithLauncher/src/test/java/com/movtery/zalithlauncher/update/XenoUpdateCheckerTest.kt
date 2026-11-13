package com.movtery.zalithlauncher.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XenoUpdateCheckerTest {

    @Test
    fun `semver comparison including prerelease`() {
        assertTrue(XenoUpdateChecker.semverCompare("1.0.0", "1.0.1") < 0)
        assertTrue(XenoUpdateChecker.semverCompare("1.0.0-beta", "1.0.0") < 0)
        assertTrue(XenoUpdateChecker.semverCompare("v1.2.0", "1.1.9") > 0)
        assertEquals(0, XenoUpdateChecker.semverCompare("v1.0.0", "1.0.0"))
        assertTrue(XenoUpdateChecker.semverCompare("1.0.0-beta.2", "1.0.0-beta.1") > 0)
    }

    @Test
    fun `release json parse skips draft and missing fields`() {
        val json = """
            [
              {"tag_name":"v1.0.1","name":"1.0.1","body":"notes","prerelease":false,"assets":[
                 {"name":"XenoLauncher-1.0.1-arm64-v8a.apk","browser_download_url":"https://example/a.apk","size":10},
                 {"name":"SHA256SUMS.txt","browser_download_url":"https://example/s.txt","size":5}
              ]},
              {"tag_name":"v1.0.0-rc1","prerelease":true,"assets":[]},
              {"tag_name":"v9.9.9","draft":true,"assets":[]},
              {"name":"broken"}
            ]
        """.trimIndent()
        val releases = XenoUpdateChecker.parseReleasesJson(json, includePrerelease = false)
        assertEquals(1, releases.size)
        assertEquals("1.0.1", releases[0].versionName)
        assertEquals(2, releases[0].assets.size)
        val pre = XenoUpdateChecker.parseReleasesJson(json, includePrerelease = true)
        assertEquals(2, pre.size)
    }

    @Test
    fun `checksums parse ignores comments and bad lines`() {
        val text = """
            # comment
            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  XenoLauncher-1.0.1-arm64-v8a.apk
            bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb *SHA256SUMS.txt
            bad line
        """.trimIndent()
        val list = XenoUpdateChecker.parseChecksums(text)
        assertEquals(2, list.size)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", list[0].sha256)
    }

    @Test
    fun `redirect location parses tag`() {
        assertEquals("v1.0.1", XenoUpdateChecker.parseRedirectTag("https://github.com/yydxin/Xeno-Launcher/releases/tag/v1.0.1"))
        assertNull(XenoUpdateChecker.parseRedirectTag(""))
    }

    @Test
    fun `asset selection matches abi and rejects non apk`() {
        val assets = listOf(
            XenoAsset("XenoLauncher-1.0.1-armeabi-v7a.apk", "u1"),
            XenoAsset("XenoLauncher-1.0.1-arm64-v8a.apk", "u2"),
            XenoAsset("SHA256SUMS.txt", "u3")
        )
        assertEquals("u2", XenoUpdateChecker.chooseAsset(assets, "arm64-v8a")?.url)
        assertEquals("u1", XenoUpdateChecker.chooseAsset(assets, "armeabi-v7a")?.url)
        val universal = listOf(XenoAsset("XenoLauncher-1.0.1-universal.apk", "u4"))
        assertEquals("u4", XenoUpdateChecker.chooseAsset(universal, "x86_64")?.url)
        assertNull(XenoUpdateChecker.chooseAsset(listOf(XenoAsset("SHA256SUMS.txt", "u")), "arm64-v8a"))
    }
}
