package com.phairplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the repository rename boundary used by old installed APKs and by release-asset validation.
 *
 * WHY: a new updater must migrate the former compiled-in API target to `2archiver/Hearth`, while
 * accepting only the actual compatible assets returned by that canonical repository.
 */
class GitHubRepositoryPolicyTest {

    @Test
    fun `old compiled repository slug migrates directly to the canonical release API`() {
        val oldSlug = "2archiver/phairplay-archiver-fork-"

        assertEquals("2archiver/Hearth", UpdateChecker(repo = oldSlug).repository)
        assertEquals(
            "https://api.github.com/repos/2archiver/Hearth/releases/latest",
            GitHubRepositoryPolicy.latestReleaseApiUrl(oldSlug),
        )
    }

    @Test
    fun `only actual canonical release page and asset paths are trusted`() {
        val repository = GitHubRepositoryPolicy.CANONICAL_REPOSITORY
        assertTrue(
            GitHubRepositoryPolicy.isReleasePageUrl(
                "https://github.com/2archiver/Hearth/releases/tag/latest",
                repository,
            )
        )
        assertTrue(
            GitHubRepositoryPolicy.isReleaseAssetUrl(
                "https://github.com/2archiver/Hearth/releases/download/latest/Hearth-1.9.2-googletv.apk",
                repository,
            )
        )
        assertFalse(
            GitHubRepositoryPolicy.isReleaseAssetUrl(
                "https://github.com/attacker/Hearth/releases/download/latest/Hearth.apk",
                repository,
            )
        )
        assertFalse(
            GitHubRepositoryPolicy.isReleaseAssetUrl(
                "https://github.com/2archiver/Hearth/releases/download/latest/Hearth.apk?token=secret",
                repository,
            )
        )
        assertFalse(
            GitHubRepositoryPolicy.isReleaseAssetUrl(
                "http://github.com/2archiver/Hearth/releases/download/latest/Hearth.apk",
                repository,
            )
        )
    }

    @Test
    fun `malformed repository names are rejected`() {
        assertNull(GitHubRepositoryPolicy.canonicalRepository("https://github.com/2archiver/Hearth"))
        assertNull(GitHubRepositoryPolicy.canonicalRepository("2archiver/Hearth/extra"))
        assertNull(GitHubRepositoryPolicy.latestReleaseApiUrl("../attacker"))
    }
}
