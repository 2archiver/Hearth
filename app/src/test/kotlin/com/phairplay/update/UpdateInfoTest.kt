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
          "apk": "PhairPlay-googletv.apk",
          "sha256": "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
          "size": 12345678 }
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
        assertEquals(
            "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789",
            descriptor.sha256
        )
    }

    @Test
    fun `descriptor versionCode wins over the release notes`() {
        val release = requireNotNull(ReleaseParser.parseRelease(releaseJson))
        val descriptor = requireNotNull(ReleaseParser.parseDescriptor(descriptorJson))
        val info = requireNotNull(ReleaseParser.buildUpdateInfo(release, descriptor))

        assertEquals(20432100, info.versionCode)
        assertEquals("1.4.0-main.131", info.versionName)
        // Lowercased by buildUpdateInfo, which only trusts a full 64-hex digest.
        assertEquals(
            "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789".lowercase(),
            info.sha256
        )
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
                "cafebabe1234  Hearth-googletv.apk\n"
        )
        val info = requireNotNull(ReleaseParser.buildUpdateInfo(release, null, sums))
        // "cafebabe1234" is not 64 hex chars, so it is rejected as a hash.
        assertNull(info.sha256)

        val goodSums = ReleaseParser.parseSha256Sums(
            "0123456789012345678901234567890123456789012345678901234567890123 *Hearth-googletv.apk"
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
        val json = releaseJson.replace("Hearth-googletv.apk", "notes.txt")
        val release = requireNotNull(ReleaseParser.parseRelease(json))
        assertNull(ReleaseParser.buildUpdateInfo(release, null))
    }

    /**
     * The protocol a release follows now: one asset, named after its version, with the versionCode
     * and the digest written into the release body. If a new-style release ever needed a side file
     * before this test fails loudly — and a TV would silently lose the ability to update itself.
     */
    @Test
    fun `a single version-named APK is enough to describe an update`() {
        val json = """
        {
          "tag_name": "latest",
          "name": "Hearth 1.6.1-main.44 for Google TV",
          "html_url": "https://github.com/2archiver/phairplay-archiver-fork-/releases/tag/latest",
          "body": "## Download and update\n\n**Download the APK**: [Hearth-1.6.1-main.44-googletv.apk](https://x)\n\n- **Version:** `1.6.1-main.44` · `versionCode 21021542`\n- **Size:** 24.9 MB · **SHA-256:** `9f2c1b64b17a4f3c0e7d2a55c8f61d4e0b3a77c2e1f4d8a6c5b3e1f0d9c8b7a6`\n",
          "assets": [
            { "name": "Hearth-1.6.1-main.44-googletv.apk",
              "browser_download_url": "https://github.com/x/y/releases/download/latest/Hearth-1.6.1-main.44-googletv.apk",
              "size": 26112000 }
          ]
        }
        """.trimIndent()
        val release = requireNotNull(ReleaseParser.parseRelease(json))
        val info = requireNotNull(ReleaseParser.buildUpdateInfo(release, descriptor = null))

        // Version name from the file that is actually installed, not from the release title.
        assertEquals("1.6.1-main.44", info.versionName)
        assertEquals(21021542, info.versionCode)
        assertEquals(26112000L, info.apkSizeBytes)
        assertTrue(info.apkUrl.endsWith("Hearth-1.6.1-main.44-googletv.apk"))
        assertEquals(
            "9f2c1b64b17a4f3c0e7d2a55c8f61d4e0b3a77c2e1f4d8a6c5b3e1f0d9c8b7a6",
            info.sha256
        )
    }

    /** A note that says "SHA-256" without a full digest must not be mistaken for one. */
    @Test
    fun `a truncated or prose digest in the notes is not used`() {
        assertNull(ReleaseParser.scrapeSha256("Verify with SHA-256: `abc123` before installing."))
        assertNull(ReleaseParser.scrapeSha256(null))
        assertEquals(
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            ReleaseParser.scrapeSha256(
                "**SHA-256:** `0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF`"
            )
        )
    }

    @Test
    fun `the version is read out of the asset file name`() {
        assertEquals("1.6.1-main.44", ReleaseParser.versionNameFromAssetName("Hearth-1.6.1-main.44-googletv.apk"))
        assertEquals("1.5.0", ReleaseParser.versionNameFromAssetName("Hearth-1.5.0.apk"))
        assertNull(ReleaseParser.versionNameFromAssetName("app-release.apk"))
        assertNull(ReleaseParser.versionNameFromAssetName(null))
    }

    /**
     * The pre-rename name must keep parsing: every release already published is `PhairPlay-…`,
     * and a TV that has not updated yet is still offered whatever the `latest` release says.
     */
    @Test
    fun `legacy PhairPlay asset names still yield their version`() {
        assertEquals("1.6.0-main.42", ReleaseParser.versionNameFromAssetName("PhairPlay-1.6.0-main.42-googletv.apk"))
        assertEquals("1.4.0", ReleaseParser.versionNameFromAssetName("PhairPlay-1.4.0.apk"))

        val legacyJson = """
        {
          "tag_name": "latest",
          "name": "PhairPlay latest (Google TV)",
          "html_url": "",
          "body": "**Version:** `1.4.0` · `versionCode 20000000`",
          "assets": [
            { "name": "PhairPlay-googletv.apk",
              "browser_download_url": "https://github.com/x/y/releases/download/latest/PhairPlay-googletv.apk",
              "size": 20000000 }
          ]
        }
        """.trimIndent()
        val release = requireNotNull(ReleaseParser.parseRelease(legacyJson))
        val info = requireNotNull(ReleaseParser.buildUpdateInfo(release, descriptor = null))
        assertEquals("PhairPlay-googletv.apk", info.apkName)
        assertEquals(20000000, info.versionCode)
        // The fixed file name carries no version, so "googletv" must not be mistaken for one.
        assertEquals("PhairPlay latest (Google TV)", info.versionName)
    }

    @Test
    fun `shortLabel shows version and code for support`() {
        val info = UpdateInfo(
            versionName = "1.4.0-main.131",
            versionCode = 20432100,
            tagName = "latest",
            htmlUrl = "",
            apkName = "Hearth-googletv.apk",
            apkUrl = "",
            apkSizeBytes = 0,
            sha256 = null,
            notes = null
        )
        assertEquals("1.4.0-main.131 (20432100)", info.shortLabel())
    }
}
