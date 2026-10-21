package com.phairplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Immutable-release contract tests for the 2.0 release hotfix.
 *
 * Publishing changed from one mutable `latest` tag (force-pushed on every build, its assets
 * deleted and re-uploaded) to one immutable, versioned GitHub Release per successful build:
 *
 *   tag/release:  v2.0.0-main.23
 *   asset:        Hearth-2.0.0-main.23-googletv.apk
 *   download URL: /2archiver/Hearth/releases/download/v2.0.0-main.23/Hearth-2.0.0-main.23-googletv.apk
 *
 * The in-app updater still makes its single request to `releases/latest`, which GitHub now
 * resolves to the release carrying the "Latest" designation. These tests pin that the parser,
 * URL policy and update decision all work against the immutable tags, that the historical
 * `latest`-tagged 1.9.3 release still resolves, and that the newest *valid* release wins.
 *
 * Runs in the plain-JVM test-runner module: nothing here opens a socket or touches Android.
 */
class ImmutableReleaseTest {

    private val repo = GitHubRepositoryPolicy.CANONICAL_REPOSITORY
    private val sha256 = "9f2c1b64b17a4f3c0e7d2a55c8f61d4e0b3a77c2e1f4d8a6c5b3e1f0d9c8b7a6"
    private val previousSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    /**
     * The exact release JSON shape the new release workflow publishes: an immutable tag, one
     * version-named Google TV asset under that tag, and versionCode/SHA-256 in the body in the
     * format [ReleaseParser] scrapes.
     */
    private fun immutableReleaseJson(
        tag: String,
        assetName: String,
        versionCode: Int,
        digest: String,
        sizeBytes: Long,
    ): String = """
        {
          "tag_name": "$tag",
          "name": "Hearth ${assetName.removeSuffix(".apk").removePrefix("Hearth-")} for Google TV",
          "html_url": "https://github.com/$repo/releases/tag/$tag",
          "body": "## Download and update\n\n**Download the APK**: [$assetName](https://github.com/$repo/releases/download/$tag/$assetName)\n\n- **Version:** `${assetName.removePrefix("Hearth-").removeSuffix("-googletv.apk")}` · `versionCode $versionCode`\n- **Size:** 13.2 MB · **SHA-256:** `$digest`\n",
          "assets": [
            {
              "name": "$assetName",
              "browser_download_url": "https://github.com/$repo/releases/download/$tag/$assetName",
              "size": $sizeBytes
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `an immutable 2_0 release parses end to end`() {
        val json = immutableReleaseJson(
            tag = "v2.0.0-main.23",
            assetName = "Hearth-2.0.0-main.23-googletv.apk",
            versionCode = 21_021_542,
            digest = sha256,
            sizeBytes = 13_829_357,
        )

        val release = requireNotNull(ReleaseParser.parseRelease(json))
        assertEquals("v2.0.0-main.23", release.tagName)

        val info = requireNotNull(ReleaseParser.buildUpdateInfo(release, descriptor = null))
        assertEquals("2.0.0-main.23", info.versionName)
        assertEquals(21_021_542, info.versionCode)
        assertEquals("v2.0.0-main.23", info.tagName)
        assertEquals("Hearth-2.0.0-main.23-googletv.apk", info.apkName)
        assertEquals(
            "https://github.com/$repo/releases/download/v2.0.0-main.23/Hearth-2.0.0-main.23-googletv.apk",
            info.apkUrl,
        )
        assertEquals(13_829_357L, info.apkSizeBytes)
        assertEquals(sha256, info.sha256)
        assertEquals(
            "https://github.com/$repo/releases/tag/v2.0.0-main.23",
            info.htmlUrl,
        )
    }

    @Test
    fun `immutable tag URLs pass the canonical repository policy`() {
        val assetUrl =
            "https://github.com/$repo/releases/download/v2.0.0-main.23/Hearth-2.0.0-main.23-googletv.apk"
        val pageUrl = "https://github.com/$repo/releases/tag/v2.0.0-main.23"

        assertTrue(GitHubRepositoryPolicy.isReleaseAssetUrl(assetUrl, repo))
        assertTrue(GitHubRepositoryPolicy.isReleasePageUrl(pageUrl, repo))
        // The API the updater actually calls still resolves to the designated newest release.
        assertEquals(
            "https://api.github.com/repos/$repo/releases/latest",
            GitHubRepositoryPolicy.latestReleaseApiUrl(repo),
        )

        // The boundary checks do not loosen for versioned tags.
        assertFalse(
            GitHubRepositoryPolicy.isReleaseAssetUrl(
                "https://github.com/attacker/Hearth/releases/download/v2.0.0-main.23/Hearth.apk",
                repo,
            )
        )
        assertFalse(
            GitHubRepositoryPolicy.isReleaseAssetUrl(
                "$assetUrl?token=secret",
                repo,
            )
        )
        assertFalse(
            GitHubRepositoryPolicy.isReleaseAssetUrl(
                assetUrl.replace("https://", "http://"),
                repo,
            )
        )
    }

    @Test
    fun `the newest valid immutable release is selected and offered`() {
        val older = ReleaseParser.parseRelease(
            immutableReleaseJson(
                tag = "v1.9.3-main.22",
                assetName = "Hearth-1.9.3-main.22-googletv.apk",
                versionCode = 20_900_022,
                digest = previousSha256,
                sizeBytes = 13_829_000,
            )
        )
        val newest = ReleaseParser.parseRelease(
            immutableReleaseJson(
                tag = "v2.0.0-main.23",
                assetName = "Hearth-2.0.0-main.23-googletv.apk",
                versionCode = 21_021_542,
                digest = sha256,
                sizeBytes = 13_829_357,
            )
        )
        requireNotNull(older)
        requireNotNull(newest)

        // The selection rule the app relies on (GitHub's make_latest designation performs the
        // same choice server-side on /releases/latest): describe every release, drop invalid
        // ones, and take the highest versionCode — never a lexical version-string sort.
        val candidates = listOf(older, newest)
            .mapNotNull { ReleaseParser.buildUpdateInfo(it, descriptor = null) }
        val selected = candidates.maxByOrNull { it.versionCode }

        requireNotNull(selected)
        assertEquals("v2.0.0-main.23", selected.tagName)
        assertEquals(21_021_542, selected.versionCode)

        // A TV on the previous build is offered the selected immutable release.
        val decision = UpdateDecision.evaluate(selected, installedVersionCode = 20_900_022)
        assertTrue(decision is UpdateCheck.Available)
        assertEquals(selected, (decision as UpdateCheck.Available).info)

        // A TV already on the selected build is not nagged.
        assertTrue(
            UpdateDecision.evaluate(selected, installedVersionCode = 21_021_542)
                is UpdateCheck.UpToDate
        )
    }

    @Test
    fun `an invalid newest release is skipped while the older valid release remains`() {
        val malformed = ReleaseParser.parseRelease(
            immutableReleaseJson(
                tag = "v2.0.1-main.24",
                assetName = "Hearth-2.0.1-main.24-googletv.apk",
                versionCode = 21_100_000,
                digest = sha256,
                sizeBytes = 13_829_357,
            ).replace("""`versionCode 21100000`""", "versionCode unknown")
        )
        val older = ReleaseParser.parseRelease(
            immutableReleaseJson(
                tag = "v2.0.0-main.23",
                assetName = "Hearth-2.0.0-main.23-googletv.apk",
                versionCode = 21_021_542,
                digest = sha256,
                sizeBytes = 13_829_357,
            )
        )
        requireNotNull(malformed)
        requireNotNull(older)

        // No readable versionCode: the parser refuses to describe the malformed release...
        assertNull(ReleaseParser.buildUpdateInfo(malformed, descriptor = null))

        // ...and the older, fully verifiable release is still selectable.
        val selected = listOf(malformed, older)
            .mapNotNull { ReleaseParser.buildUpdateInfo(it, descriptor = null) }
            .maxByOrNull { it.versionCode }
        requireNotNull(selected)
        assertEquals("v2.0.0-main.23", selected.tagName)
    }

    /**
     * The 1.9.3 release tagged literally `latest` is retained as history and must keep
     * resolving, because installed 1.9.x builds still read it during the transition. The tag
     * is simply data now; the workflow never publishes to it again.
     */
    @Test
    fun `the historical latest-tagged release still resolves`() {
        val json = """
        {
          "tag_name": "latest",
          "name": "Hearth 1.9.3-main.22-googletv for Google TV",
          "html_url": "https://github.com/$repo/releases/tag/latest",
          "body": "## Download and update\n\n- **Version:** `1.9.3-main.22-googletv` · `versionCode 20900022`\n- **Size:** 13.2 MB · **SHA-256:** `$previousSha256`\n",
          "assets": [
            {
              "name": "Hearth-1.9.3-main.22-googletv.apk",
              "browser_download_url": "https://github.com/$repo/releases/download/latest/Hearth-1.9.3-main.22-googletv.apk",
              "size": 13829000
            }
          ]
        }
        """.trimIndent()

        val release = requireNotNull(ReleaseParser.parseRelease(json))
        val info = requireNotNull(ReleaseParser.buildUpdateInfo(release, descriptor = null))
        assertEquals("latest", info.tagName)
        assertEquals("1.9.3-main.22", info.versionName)
        assertEquals(20_900_022, info.versionCode)
        assertEquals(previousSha256, info.sha256)
        assertTrue(
            GitHubRepositoryPolicy.isReleaseAssetUrl(info.apkUrl, repo)
        )
    }

    @Test
    fun `the asset file name carries the same version as the immutable tag`() {
        val tag = "v2.0.0-main.23"
        val asset = "Hearth-2.0.0-main.23-googletv.apk"
        assertEquals(
            tag.removePrefix("v"),
            requireNotNull(ReleaseParser.versionNameFromAssetName(asset)),
        )
        // A mismatched asset/tag pair would surface as a version-name disagreement.
        val info = requireNotNull(
            ReleaseParser.buildUpdateInfo(
                requireNotNull(
                    ReleaseParser.parseRelease(
                        immutableReleaseJson(tag, asset, 21_021_542, sha256, 13_829_357)
                    )
                ),
                descriptor = null,
            )
        )
        assertEquals("2.0.0-main.23", info.versionName)
    }
}
