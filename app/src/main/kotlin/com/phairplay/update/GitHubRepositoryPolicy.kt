package com.phairplay.update

import java.net.URI

/** Canonical GitHub endpoints used by the updater. The old repository name is a rename alias. */
internal object GitHubRepositoryPolicy {
    const val CANONICAL_REPOSITORY = "2archiver/Hearth"
    const val RENAMED_FROM_REPOSITORY = "2archiver/phairplay-archiver-fork-"

    private val repositoryPattern = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    /** Normalize the GitHub rename before constructing requests; reject anything but owner/name. */
    fun canonicalRepository(repository: String): String? {
        val candidate = repository.trim().trimEnd('/')
        if (candidate.equals(RENAMED_FROM_REPOSITORY, ignoreCase = true)) {
            return CANONICAL_REPOSITORY
        }
        return candidate.takeIf(repositoryPattern::matches)
    }

    fun latestReleaseApiUrl(repository: String): String? = canonicalRepository(repository)?.let {
        "https://api.github.com/repos/$it/releases/latest"
    }

    fun latestReleasePageUrl(repository: String): String? = canonicalRepository(repository)?.let {
        "https://github.com/$it/releases/latest"
    }

    /**
     * A release asset URL must be the actual asset URL returned by the configured repository's
     * GitHub Release API. The APK download may subsequently redirect to GitHub's asset CDN.
     */
    fun isReleaseAssetUrl(url: String, repository: String): Boolean =
        isGitHubUrl(url, repository, "/releases/download/")

    fun isReleasePageUrl(url: String, repository: String): Boolean =
        isGitHubUrl(url, repository, "/releases/")

    private fun isGitHubUrl(url: String, repository: String, suffix: String): Boolean {
        val canonical = canonicalRepository(repository) ?: return false
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("github.com", ignoreCase = true) ||
            uri.port != -1 && uri.port != 443 ||
            uri.rawUserInfo != null ||
            uri.rawQuery != null ||
            uri.rawFragment != null
        ) return false

        val path = uri.path ?: return false
        val rawPath = uri.rawPath ?: return false
        if (rawPath.contains('%')) return false
        val expectedPrefix = "/$canonical$suffix"
        if (!path.startsWith(expectedPrefix)) return false
        // GitHub's release asset paths have a tag and a filename. Reject empty/dot segments.
        val tail = path.removePrefix(expectedPrefix).split('/')
        if (tail.size < 2 || tail.any { it.isBlank() || it == "." || it == ".." }) return false
        val normalized = runCatching { URI(null, null, path, null).normalize().path }.getOrNull()
            ?: return false
        return normalized == path
    }
}
