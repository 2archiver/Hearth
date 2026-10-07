package com.phairplay.update

import com.phairplay.BuildConfig
import com.phairplay.util.Logger
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * UpdateChecker — asks GitHub whether a newer Hearth APK exists and downloads it.
 *
 * WHY: Hearth is sideloaded, so "update" meant opening Downloader on the TV, typing a
 * long URL and hoping the APK was signed with the same key. Doing it from the TV itself
 * removes the whole ritual — and because the app knows its own versionCode it can tell
 * the difference between "nothing new" and "new build" without parsing release titles.
 *
 * HOW: ONE plain HTTPS call — no SDK, no dependency, and deliberately no second round trip:
 *   `GET https://api.github.com/repos/{repo}/releases/latest` → the release JSON.
 * A Hearth release carries one version-named Google TV APK, so that response is the entire
 * protocol: the actual compatible asset URL, file size, versionCode and full SHA-256 are validated
 * before [download] streams that exact asset and verifies the APK size and digest.
 *
 * (Releases published before "one APK only" also carried `version.json` and `SHA256SUMS.txt`.
 * Those parsers still exist in [ReleaseParser] and `buildUpdateInfo` still prefers them when a
 * descriptor is handed in — but nothing here fetches them any more, so an old release simply
 * resolves from its notes and a new one never spends a request on a side file.)
 *
 * Every method does blocking I/O: call them from `Dispatchers.IO`, never from the main
 * thread. Nothing here touches the UI.
 *
 * @param repo GitHub repository in `owner/name` form (from BuildConfig.UPDATE_REPO).
 */
class UpdateChecker(
    repo: String = BuildConfig.UPDATE_REPO
) {
    /** Old APKs may have compiled the pre-rename slug; normalize it to the current canonical repo. */
    internal val repository: String? = GitHubRepositoryPolicy.canonicalRepository(repo)

    /** The versionCode of the APK that is currently installed. */
    fun installedVersionCode(): Int = BuildConfig.VERSION_CODE

    /** The versionName of the APK that is currently installed. */
    fun installedVersionName(): String = BuildConfig.VERSION_NAME

    /**
     * Performs the whole "is there something new?" round trip.
     *
     * Never throws: network and parse problems come back as [UpdateCheck.Failed] so the UI
     * can show something useful instead of crashing a TV screen on a flaky hotel Wi-Fi.
     */
    fun check(): UpdateCheck {
        val repo = repository
            ?: return UpdateCheck.Failed("This build has an invalid update repository setting. Contact the app maintainer.")
        return try {
            val releaseJson = getText(URL(releasesLatestUrl(repo)))
            val release = ReleaseParser.parseRelease(releaseJson)
                ?: return UpdateCheck.Failed("GitHub returned release data that Hearth could not read. Retry later or report the release.")

            // One release, one asset: everything is derived from the JSON above. The release's
            // only APK asset is what gets installed, its file name is the version, and the body
            // carries the versionCode and the checksum to verify the download against.
            val info = ReleaseParser.buildUpdateInfo(release, descriptor = null)
                ?: return UpdateCheck.Failed(
                    "The latest release does not contain exactly one compatible Google TV APK " +
                        "with matching version, file size, and SHA-256 metadata. Open the release page or try again later."
                )
            if (!GitHubRepositoryPolicy.isReleasePageUrl(info.htmlUrl, repo) ||
                !GitHubRepositoryPolicy.isReleaseAssetUrl(info.apkUrl, repo)
            ) {
                return UpdateCheck.Failed(
                    "GitHub returned a release link outside $repo. The update was blocked; contact the app maintainer."
                )
            }

            val decision = UpdateDecision.evaluate(info, installedVersionCode())
            when (decision) {
                is UpdateCheck.Available ->
                    Logger.i("Update available: ${info.versionName} (${info.versionCode}) > installed ${installedVersionCode()}")
                is UpdateCheck.UpToDate ->
                    Logger.i(
                        "No update offered: installed ${installedVersionCode()}, published " +
                            "${info.versionName} (${info.versionCode})" +
                            if (decision.publishedVersionUnknown) " [release states no versionCode]" else ""
                    )
                else -> Unit
            }
            decision
        } catch (e: InterruptedIOException) {
            UpdateCheck.Failed("The update check was interrupted. Try again.")
        } catch (e: HttpStatusException) {
            Logger.w("GitHub update check returned HTTP ${e.statusCode}")
            UpdateCheck.Failed(e.toUserMessage(repository.orEmpty()))
        } catch (e: IOException) {
            Logger.w("Update check failed (${e.javaClass.simpleName})")
            UpdateCheck.Failed(e.toUserMessage())
        } catch (e: Exception) {
            Logger.w("Update check failed unexpectedly (${e.javaClass.simpleName})")
            UpdateCheck.Failed("Update check failed unexpectedly. Check the network and try again.")
        }
    }

    /**
     * Downloads [info]'s APK to [destination], requiring a full SHA-256 digest and exact file size
     * from the validated release metadata. Missing or malformed integrity data blocks the download.

     * @param onProgress called with 0..100 (coarsely — a few times per megabyte).
     * @return the downloaded file, or null when metadata, network, size or checksum verification
     *   fails. A partial download is deleted before returning null so a later retry starts clean.
     */
    fun download(
        info: UpdateInfo,
        destination: File,
        onProgress: (percent: Int) -> Unit = {}
    ): File? {
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()

        val repo = repository ?: return null
        val expectedSha256 = info.sha256?.lowercase()
        if (info.versionCode <= 0 || info.apkSizeBytes <= 0L ||
            expectedSha256 == null || !SHA256_PATTERN.matches(expectedSha256) ||
            !GitHubRepositoryPolicy.isReleaseAssetUrl(info.apkUrl, repo)
        ) {
            Logger.e("Refusing update download: release metadata or canonical GitHub asset URL is invalid")
            return null
        }
        val digest = MessageDigest.getInstance("SHA-256")

        return try {
            val connection = open(URL(info.apkUrl), githubApi = false)
            try {
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    Logger.e("APK download failed with HTTP $responseCode")
                    return null
                }
                val total = connection.contentLengthLong
                if (total > 0L && total != info.apkSizeBytes) {
                    Logger.e("APK download size metadata mismatch (reported=$total expected=${info.apkSizeBytes})")
                    return null
                }
                var read = 0L
                var lastReported = -1

                connection.inputStream.use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            read += n
                            if (total > 0) {
                                val percent = ((read * 100L) / total).toInt()
                                if (percent != lastReported && percent % 5 == 0) {
                                    lastReported = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
                onProgress(100)
            } finally {
                connection.disconnect()
            }

            if (destination.length() != info.apkSizeBytes) {
                Logger.e("APK download size mismatch (received=${destination.length()} expected=${info.apkSizeBytes})")
                destination.delete()
                return null
            }
            val actual = digest.digest().toHex()
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                Logger.e("Update SHA-256 checksum mismatch; downloaded file was deleted")
                destination.delete()
                return null
            }
            Logger.i("Update downloaded; size and SHA-256 verified: ${destination.name}")
            destination
        } catch (e: Exception) {
            Logger.e("APK download failed (${e.javaClass.simpleName})")
            destination.delete()
            null
        }
    }

    // ─── HTTP plumbing ───────────────────────────────────────────────────────

    private fun releasesLatestUrl(repo: String): String =
        GitHubRepositoryPolicy.latestReleaseApiUrl(repo)
            ?: throw IOException("Invalid GitHub repository setting")

    private fun open(url: URL, githubApi: Boolean): HttpURLConnection {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw IOException("GitHub update endpoints must use HTTPS")
        }
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true // GitHub release assets may redirect to its CDN.
        connection.setRequestProperty("User-Agent", "Hearth/${BuildConfig.VERSION_NAME}")
        connection.setRequestProperty(
            "Accept",
            if (githubApi) "application/vnd.github+json" else "application/octet-stream"
        )
        // No credentials or custom authorization headers are sent to either endpoint. Only the
        // non-sensitive User-Agent/Accept headers can follow GitHub's asset CDN redirect.
        return connection
    }

    /** Fetches the canonical latest-release API document, following GitHub's ordinary redirects. */
    private fun getText(url: URL): String {
        val connection = open(url, githubApi = true)
        return try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) throw HttpStatusException(code)
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun IOException.toUserMessage(): String = when {
        message.orEmpty().contains("UnknownHost", ignoreCase = true) ||
            message.orEmpty().contains("ENETUNREACH", ignoreCase = true) ||
            message.orEmpty().contains("EHOSTUNREACH", ignoreCase = true) ->
            "No connection — the TV could not reach GitHub. Check Wi-Fi/Ethernet and try again."

        message.orEmpty().contains("timed out", ignoreCase = true) ||
            message.orEmpty().contains("ETIMEDOUT", ignoreCase = true) ->
            "The connection to GitHub timed out. Check the network and try again."

        else -> "Could not reach GitHub. Check the network and try again."
    }

    private fun HttpStatusException.toUserMessage(repo: String): String = when (statusCode) {
        404 -> "No latest release was found for $repo. Check back after a release is published."
        403, 429 -> "GitHub temporarily limited update checks. Wait a while, then try again."
        in 500..599 -> "GitHub is temporarily unavailable (HTTP $statusCode). Try again later."
        else -> "GitHub rejected the update check (HTTP $statusCode). Try again later."
    }

    private fun ByteArray.toHex(): String {
        val hex = CharArray(size * 2)
        val digits = HEX_DIGITS
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            hex[i * 2] = digits[v ushr 4]
            hex[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hex)
    }

    private class HttpStatusException(val statusCode: Int) : IOException("GitHub HTTP $statusCode")

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
        private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}

/**
 * UpdateDecision — the rule that decides whether a published release may be offered.
 *
 * WHY IT IS ITS OWN OBJECT: this is the piece of the updater users actually feel. Getting it
 * wrong in one direction nags about a build that is already installed; in the other it offers a
 * *downgrade*, which Android then refuses to install (`INSTALL_FAILED_VERSION_DOWNGRADE`) after
 * the download has already happened. Pulling the comparison out of the network code makes the
 * rule directly unit-testable — see UpdateDecisionTest and UpdateInfoTest.
 */
object UpdateDecision {

    /**
     * @param info                 the newest published release, as read from GitHub
     * @param installedVersionCode BuildConfig.VERSION_CODE of the running APK
     */
    fun evaluate(info: UpdateInfo, installedVersionCode: Int): UpdateCheck = when {
        // No readable versionCode — nothing can be compared, so nothing is offered, and the UI
        // says exactly that rather than claiming "up to date" about a build it cannot identify.
        info.versionCode <= 0 ->
            UpdateCheck.UpToDate(info, newerThanPublished = false, publishedVersionUnknown = true)

        info.versionCode > installedVersionCode -> UpdateCheck.Available(info)

        // Published and installed are the same build. Not an update.
        info.versionCode == installedVersionCode -> UpdateCheck.UpToDate(info, newerThanPublished = false)

        // A *downgrade*: the release is older than what is installed (a locally built APK whose
        // clock-derived code ran ahead, or an old release re-tagged `latest`). This is the case
        // that must never be presented as "update available".
        else -> UpdateCheck.UpToDate(info, newerThanPublished = true)
    }
}

/** Why an update could not be completed; the UI uses this to choose useful next steps. */
enum class UpdateFailureReason {
    /** The release lookup failed (network, GitHub, or malformed release metadata). */
    CHECK_FAILED,
    /** The APK could not be downloaded or its checksum did not match. */
    DOWNLOAD_FAILED,
    /** Android cannot replace this install because the APK uses another signing key. */
    SIGNATURE_MISMATCH,
    /** The downloaded file is not a readable APK. */
    INVALID_APK,
    /** The APK's package/version metadata disagrees with the release metadata. */
    METADATA_MISMATCH,
    /**
     * The release — or the APK inside it — is older than (or the same build as) this install.
     * Android would refuse the install as a downgrade, so it is never staged.
     */
    PUBLISHED_OLDER,
    /** The running app's signing certificate could not be read safely. */
    SIGNATURE_UNVERIFIED
}

/** Outcome of [UpdateChecker.check]. */
sealed class UpdateCheck {

    /**
     * A newer build is published. [info] describes it.
     *
     * [skipped] means the user already pressed "Skip this version" for this exact build. The
     * update still exists — a manual check keeps offering it — but nothing is announced, badged
     * or auto-downloaded until a *newer* version arrives.
     */
    data class Available(val info: UpdateInfo, val skipped: Boolean = false) : UpdateCheck()

    /**
     * Nothing to do. [info] is the newest published build (so the UI can still show
     * "1.4.0-main.131 is the current build"), and [newerThanPublished] marks the
     * development case where the installed APK has a higher versionCode than the
     * published one — usually a locally built debug APK.
     */
    data class UpToDate(
        val info: UpdateInfo,
        val newerThanPublished: Boolean,
        /**
         * True when the release notes carried no versionCode at all, so the two builds cannot be
         * compared. Distinct from [newerThanPublished] because the honest message differs: one is
         * "you are ahead", the other is "the release does not say what it is".
         */
        val publishedVersionUnknown: Boolean = false
    ) : UpdateCheck()

    /**
     * The check or install could not be completed. [message] is safe to show to the user;
     * [reason] lets the UI distinguish a transient error from a one-time key migration.
     */
    data class Failed(
        val message: String,
        val reason: UpdateFailureReason = UpdateFailureReason.CHECK_FAILED,
        val info: UpdateInfo? = null
    ) : UpdateCheck()

    /**
     * The check was deliberately not performed — usually because a successful check ran
     * less than [UpdatePreferences.CHECK_INTERVAL_MS] ago. UI shows "checked recently".
     */
    data class Skipped(val reason: String) : UpdateCheck()
}
