package com.phairplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UpdateInfoTest — the parsing that decides whether the TV is offered an update.
 *
 * These run on the plain JVM (test-runner module): [ReleaseParser] and the data classes
 * deliberately do not touch Android, because getting the versionCode wrong is exactly how
 * an updater ends up nagging about a build that is already installed.
 */
class UpdateInfoTest {

    private val releaseJson = """
        {
          "url": "https://api.github.com/repos/2archiver/phairplay-archiver-fork-/releases/1",
          "html_url": "https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest",
          "id": 1,
          "tag_name": "latest",
          "name": "PhairPlay latest (Google TV)",
          "body": "| Version | 1.4.0-main.131 · versionCode 20432100 |\n| Commit | abc1234 |",
          "assets": [
            { "name": "PhairPlay-googletv.apk",
              "browser_download_url": "https://github.com/x/y/releases/download/latest/PhairPlay-googletv.apk",
              "size": 12345678 },
            { "name": "SHA256SUMS.txt",
              "browser_download_url": "https://github.com/x/y/releases/download/latest/SHA256SUMS.txt",
              "size": 210 },
            { "name": "version.json",
              "browser_download_url": "https://github.com/x/y/releases/download/latest/version.json",
              "size": 220 }
          ]
        }
    """.trimIndent()

    private val descriptorJson = """
        { "versionName": "1.4.0-main.131", "versionCode": 20432100,
          "apk": "PhairPlay-googletv.apk", "sha256": "ABCDEF0123456789", "size": 12345678 }
    """.trimIndent()

    @Test
    fun `parses a GitHub release and finds the APK asset`() {
        val release = ReleaseParser.parseRelease(releaseJson)
        requireNotNull(release)
        assertEquals("latest", release.tagName)
        assertEquals("PhairPlay latest (Google TV)", release.name)
        assertEquals(3, release.assets.size)

        val apk = release.apkAsset()
        requireNotNull(apk)
        assertEquals("PhairPlay-googletv.apk", apk.name)
        assertEquals(12345678L, apk.sizeBytes)
        assertTrue(apk.url.endsWith("/PhairPlay-googletv.apk"))
    }

    @Test
    fun `parses the version descriptor published next to the APK`() {
        val descriptor = ReleaseParser.parseDescriptor(descriptorJson)
        requireNotNull(descriptor)
        assertEquals("1.4.0-main.131", descriptor.versionName)
        assertEquals(20432100, descriptor.versionCode)
        assertEquals("PhairPlay-googletv.apk", descriptor.apk)
        assertEquals("ABCDEF0123456789", descriptor.sha256)
    }

    @Test
    fun `descriptor versionCode wins over the release notes`() {
        val release = requireNotNull(ReleaseParser.parseRelease(releaseJson))
        val descriptor = requireNotNull(ReleaseParser.parseDescriptor(descriptorJson))
        val info = requireNotNull(ReleaseParser.buildUpdateInfo(release, descriptor))

        assertEquals(20432100, info.versionCode)
        assertEquals("1.4.0-main.131", info.versionName)
        assertEquals("ABCDEF0123456789".lowercase(), info.sha256)
        assertTrue(info.isNewerThan(20432100 - 1))
        assertTrue(!info.isNewerThan(20432100))
    }

    @Test
    fun `falls back to scraping versionCode out of the release notes`() {
        val release = requireNotNull(ReleaseParser.parseRelease(releaseJson))
        val info = requireNotNull(ReleaseParser.buildUpdateInfo(release, descriptor = null))
        assertEquals(20432100, info.versionCode)
    }

    @Test
    fun `reads the APK checksum out of SHA256SUMS when there is no descriptor`() {
        val release = requireNotNull(ReleaseParser.parseRelease(releaseJson))
        val sums = ReleaseParser.parseSha256Sums(
            "deadbeef  PhairPlay-v1.3.0-googletv.apk\n" +
                "cafebabe1234  PhairPlay-googletv.apk\n"
        )
        val info = requireNotNull(ReleaseParser.buildUpdateInfo(release, null, sums))
        // "cafebabe1234" is not 64 hex chars, so it is rejected as a hash.
        assertNull(info.sha256)

        val goodSums = ReleaseParser.parseSha256Sums(
            "0123456789012345678901234567890123456789012345678901234567890123 *PhairPlay-googletv.apk"
        )
        val goodInfo = requireNotNull(ReleaseParser.buildUpdateInfo(release, null, goodSums))
        assertEquals("0123456789012345678901234567890123456789012345678901234567890123", goodInfo.sha256)
    }

    @Test
    fun `garbage input yields null instead of throwing`() {
        assertNull(ReleaseParser.parseRelease("not json"))
        assertNull(ReleaseParser.parseRelease("{}"))
        assertNull(ReleaseParser.parseDescriptor("<html>502 Bad Gateway</html>"))
        assertNull(ReleaseParser.scrapeVersionCode(null))
        assertNull(ReleaseParser.scrapeVersionCode("no version here"))
    }

    @Test
    fun `a release without an APK cannot produce an update`() {
        val json = releaseJson.replace("PhairPlay-googletv.apk", "notes.txt")
        val release = requireNotNull(ReleaseParser.parseRelease(json))
        assertNull(ReleaseParser.buildUpdateInfo(release, null))
    }

    @Test
    fun `shortLabel shows version and code for support`() {
        val info = UpdateInfo(
            versionName = "1.4.0-main.131",
            versionCode = 20432100,
            tagName = "latest",
            htmlUrl = "",
            apkName = "PhairPlay-googletv.apk",
            apkUrl = "",
            apkSizeBytes = 0,
            sha256 = null,
            notes = null
        )
        assertEquals("1.4.0-main.131 (20432100)", info.shortLabel())
    }
}
