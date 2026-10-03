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
 * UpdateChecker — asks GitHub whether a newer PhairPlay APK exists and downloads it.
 *
 * WHY: PhairPlay is sideloaded, so "update" meant opening Downloader on the TV, typing a
 * long URL and hoping the APK was signed with the same key. Doing it from the TV itself
 * removes the whole ritual — and because the app knows its own versionCode it can tell
 * the difference between "nothing new" and "new build" without parsing release titles.
 *
 * HOW: ONE plain HTTPS call — no SDK, no dependency, and deliberately no second round trip:
 *   `GET https://api.github.com/repos/{repo}/releases/latest` → the release JSON.
 * A PhairPlay release carries exactly one asset, the version-named APK, so that response is the
 * entire protocol: the version name comes from the asset's file name, the versionCode and the
 * APK's SHA-256 are scraped out of the release body the workflow writes. [download] then streams
 * that one asset to a file, checking its SHA-256 on the way.
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
    private val repo: String = BuildConfig.UPDATE_REPO
) {

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
        if (repo.isBlank()) {
            return UpdateCheck.Failed("No update repository configured for this build.")
        }
        return try {
            val releaseJson = getText(URL(releasesLatestUrl()))
                ?: return UpdateCheck.Failed("GitHub has no releases for $repo yet.")
            val release = ReleaseParser.parseRelease(releaseJson)
                ?: return UpdateCheck.Failed("Could not read the release published by $repo.")

            // One release, one asset: everything is derived from the JSON above. The release's
            // only APK asset is what gets installed, its file name is the version, and the body
            // carries the versionCode and the checksum to verify the download against.
            val info = ReleaseParser.buildUpdateInfo(release, descriptor = null)
                ?: return UpdateCheck.Failed("That release has no APK to install.")

            val installed = installedVersionCode()
            if (info.versionCode > 0 && info.versionCode > installed) {
                Logger.i("Update available: ${info.versionName} (${info.versionCode}) > installed $installed")
                UpdateCheck.Available(info)
            } else if (info.versionCode > 0 && info.versionCode < installed) {
                // Local/development builds get a clock-derived code that can run ahead of
                // the published one. Say so instead of silently claiming "up to date".
                Logger.i("Installed $installed is newer than published ${info.versionCode}")
                UpdateCheck.UpToDate(info, newerThanPublished = true)
            } else {
                Logger.i("Up to date: installed $installed, latest ${info.versionName}")
                UpdateCheck.UpToDate(info, newerThanPublished = false)
            }
        } catch (e: InterruptedIOException) {
            UpdateCheck.Failed("The update check was interrupted.")
        } catch (e: IOException) {
            Logger.w("Update check failed: ${e.message}")
            UpdateCheck.Failed(e.toUserMessage())
        } catch (e: Exception) {
            Logger.w("Update check failed unexpectedly: ${e.message}")
            UpdateCheck.Failed("Update check failed: ${e.message ?: "unknown error"}")
        }
    }

    /**
     * Downloads [info]'s APK to [destination], verifying it against the SHA-256 written in the
     * release body. A release whose notes carry no digest cannot be verified — [UpdateInfo.sha256]
     * is null there and the install proceeds after a length check only.
     *
     * @param onProgress called with 0..100 (coarsely — a few times per megabyte).
     * @return the downloaded file, or null when the download or the checksum failed. A
     *   partial download is deleted before returning null so a later retry starts clean.
     */
    fun download(
        info: UpdateInfo,
        destination: File,
        onProgress: (percent: Int) -> Unit = {}
    ): File? {
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.delete()

        // val, not var: it is captured by the streaming loop below and Kotlin can only
        // smart-cast a captured val.
        val digest: MessageDigest? = if (info.sha256 != null) MessageDigest.getInstance("SHA-256") else null

        return try {
            val connection = open(URL(info.apkUrl))
            try {
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    Logger.e("APK download failed with HTTP $responseCode")
                    return null
                }
                val total = connection.contentLengthLong
                var read = 0L
                var lastReported = -1

                connection.inputStream.use { input ->
                    destination.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest?.update(buffer, 0, n)
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

            val expected = info.sha256
            if (expected != null && digest != null) {
                val actual = digest.digest().toHex()
                if (!actual.equals(expected, ignoreCase = true)) {
                    Logger.e("Update checksum mismatch: expected $expected got $actual")
                    destination.delete()
                    return null
                }
                Logger.i("Update downloaded and checksum verified: ${destination.name}")
            } else {
                Logger.i("Update downloaded (${destination.length()} bytes, no checksum published)")
            }
            destination
        } catch (e: Exception) {
            Logger.e("APK download failed", e)
            destination.delete()
            null
        }
    }

    // ─── HTTP plumbing ───────────────────────────────────────────────────────

    private fun releasesLatestUrl(): String =
        "https://api.github.com/repos/$repo/releases/latest"

    private fun open(url: URL): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true   // release assets redirect to a CDN
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "PhairPlay/${BuildConfig.VERSION_NAME}")
        return connection
    }

    /** Fetches a small text document, following redirects. Returns null on any non-2xx. */
    private fun getText(url: URL): String? {
        val connection = open(url)
        return try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                Logger.w("GET $url → HTTP $code")
                null
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun IOException.toUserMessage(): String {
        val message = message ?: ""
        return when {
            message.contains("UnknownHost", ignoreCase = true) ||
                message.contains("ENETUNREACH", ignoreCase = true) ||
                message.contains("EHOSTUNREACH", ignoreCase = true) ->
                "No connection — the TV could not reach github.com."

            message.contains("timed out", ignoreCase = true) ||
                message.contains("ETIMEDOUT", ignoreCase = true) ->
                "The connection to github.com timed out."

            else -> "Update check failed: $message"
        }
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

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private val HEX_DIGITS = "0123456789abcdef".toCharArray()
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
    /** The running app's signing certificate could not be read safely. */
    SIGNATURE_UNVERIFIED
}

/** Outcome of [UpdateChecker.check]. */
sealed class UpdateCheck {

    /** A newer build is published. [info] describes it. */
    data class Available(val info: UpdateInfo) : UpdateCheck()

    /**
     * Nothing to do. [info] is the newest published build (so the UI can still show
     * "1.4.0-main.131 is the current build"), and [newerThanPublished] marks the
     * development case where the installed APK has a higher versionCode than the
     * published one — usually a locally built debug APK.
     */
    data class UpToDate(val info: UpdateInfo, val newerThanPublished: Boolean) : UpdateCheck()

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
