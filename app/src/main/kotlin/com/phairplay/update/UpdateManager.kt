package com.phairplay.update

import android.content.Context
import com.phairplay.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * UpdateManager — the updater's front door: check, download, verify, install.
 *
 * WHY one object: the three callers (Settings screen, Home screen badge, background
 * service) all need the same sequence and the same bookkeeping, and all of them must run
 * it off the main thread. Putting the sequence here keeps the network/IO rules in one
 * place and lets the UI stay declarative.
 *
 * Threading: every public method that touches the network or the disk is `suspend` and
 * switches to [Dispatchers.IO] itself, so callers can invoke them from the main thread.
 *
 * Deliberately dependency-free (no AndroidX): this class is also exercised by the plain
 * JVM test-runner module, which has no AndroidX on its classpath.
 */
class UpdateManager(private val context: Context) {

    private val checker = UpdateChecker()
    private val installer = UpdateInstaller(context.applicationContext)
    private val prefs = UpdatePreferences(context.applicationContext)

    /** versionCode of the APK currently installed. */
    fun installedVersionCode(): Int = checker.installedVersionCode()

    /** versionName of the APK currently installed. */
    fun installedVersionName(): String = checker.installedVersionName()

    /**
     * True when an automatic check is due — no successful check in the last
     * [UpdatePreferences.CHECK_INTERVAL_MS], and no failure in the retry window.
     */
    fun isCheckDue(): Boolean {
        val last = prefs.lastCheckMillis
        if (last == 0L) return true
        return System.currentTimeMillis() - last >= UpdatePreferences.CHECK_INTERVAL_MS
    }

    /**
     * Checks GitHub for a newer build.
     *
     * @param force ignore the throttle (used by the "Check now" button).
     */
    suspend fun check(force: Boolean = false): UpdateCheck = withContext(Dispatchers.IO) {
        if (!force && !isCheckDue()) {
            Logger.d("Update check skipped — last check was less than 6h ago")
            return@withContext UpdateCheck.Skipped("Checked recently.")
        }
        var result = checker.check()
        // "Skip this version" is an instruction about *that* build, not about updating: a manual
        // check still shows it (so the decision can be reversed), but the background loop must
        // stop mentioning it. Marked here — the one place both the service and the UI pass
        // through — so neither can forget.
        if (result is UpdateCheck.Available &&
            prefs.skippedVersionCode != 0 && prefs.skippedVersionCode == result.info.versionCode
        ) {
            result = result.copy(skipped = true)
        }
        when (result) {
            is UpdateCheck.Available -> {
                prefs.lastCheckMillis = System.currentTimeMillis()
                prefs.latestSeenVersionName = result.info.versionName
                prefs.latestSeenVersionCode = result.info.versionCode
                // A staged download for an older build is stale once a newer one exists.
                if (prefs.stagedVersionCode != 0 && prefs.stagedVersionCode < result.info.versionCode) {
                    clearDownload()
                }
            }

            is UpdateCheck.UpToDate -> {
                prefs.lastCheckMillis = System.currentTimeMillis()
                prefs.latestSeenVersionName = result.info.versionName
                prefs.latestSeenVersionCode = result.info.versionCode
            }

            is UpdateCheck.Failed -> {
                // Record the attempt so a flaky network doesn't trigger a check on every
                // single app launch, but retry sooner than a successful check would.
                prefs.lastCheckMillis =
                    System.currentTimeMillis() - UpdatePreferences.CHECK_INTERVAL_MS +
                        UpdatePreferences.RETRY_INTERVAL_MS
            }

            is UpdateCheck.Skipped -> Unit
        }
        result
    }

    /**
     * Downloads [info] and, if the APK is signed like the running app, stages it for install.
     *
     * @return the staged update, or a [StageResult] explaining why nothing was staged.
     */
    suspend fun downloadAndStage(
        info: UpdateInfo,
        onProgress: (percent: Int) -> Unit = {}
    ): StageResult = withContext(Dispatchers.IO) {
        // Refuse before spending the user's bandwidth: the release is not newer than this install.
        val installed = installedVersionCode()
        if (info.versionCode in 1..installed) {
            Logger.w("Refusing ${info.versionName} (${info.versionCode}): not newer than installed $installed")
            return@withContext StageResult.Failed(
                "The published build (${info.versionName}, build ${info.versionCode}) is not newer " +
                    "than the one already on this TV (build $installed), so there is nothing to install.",
                UpdateFailureReason.PUBLISHED_OLDER
            )
        }

        val file = destinationFor(info)
        val downloaded = checker.download(info, file, onProgress)
            ?: return@withContext StageResult.Failed(
                "Download failed. Check the TV's connection and try again.",
                UpdateFailureReason.DOWNLOAD_FAILED
            )

        // The release notes are scraped text; the APK is the truth. Reading the versionCode out
        // of the downloaded package catches the case the notes got wrong (or a release that was
        // published from an older commit) *before* it becomes an install Android will reject.
        val realVersionCode = apkVersionCode(downloaded)
        if (realVersionCode != null && realVersionCode <= installed) {
            Logger.w(
                "Downloaded APK is build $realVersionCode, installed is $installed — " +
                    "that is a downgrade; deleting it rather than offering an install"
            )
            downloaded.delete()
            return@withContext StageResult.Failed(
                "The download turned out to be older than the build on this TV " +
                    "(build $realVersionCode vs $installed). It was deleted — nothing to install.",
                UpdateFailureReason.PUBLISHED_OLDER
            )
        }

        when (installer.verifySignature(downloaded)) {
            SignatureCheck.Match -> {
                prefs.stage(info, downloaded.absolutePath)
                Logger.i("Update staged: ${downloaded.absolutePath}")
                StageResult.Staged(StagedUpdate(info, downloaded))
            }

            SignatureCheck.Mismatch -> {
                // The whole point of the shared signing key: never hand the TV an APK that
                // would be rejected with "package conflicts with an existing package".
                downloaded.delete()
                StageResult.Failed(
                    "Android cannot install this update over the current Hearth because the " +
                        "signing keys differ. Use an APK signed with this install's key, or make " +
                        "a one-time manual switch to the new source.",
                    UpdateFailureReason.SIGNATURE_MISMATCH
                )
            }

            SignatureCheck.Unreadable -> {
                downloaded.delete()
                StageResult.Failed(
                    "The downloaded file is not a readable APK. Try again.",
                    UpdateFailureReason.INVALID_APK
                )
            }

            SignatureCheck.Unknown -> {
                downloaded.delete()
                StageResult.Failed(
                    "Could not verify the download. Try again.",
                    UpdateFailureReason.SIGNATURE_UNVERIFIED
                )
            }
        }
    }

    /** A previously staged download, if the file is still there. */
    fun stagedUpdate(): StagedUpdate? {
        val code = prefs.stagedVersionCode
        val path = prefs.stagedApkPath ?: return null
        if (code == 0) return null
        val file = File(path)
        if (!file.isFile) {
            prefs.clearStaged()
            return null
        }
        return StagedUpdate(
            info = UpdateInfo(
                versionName = prefs.latestSeenVersionName ?: "update",
                versionCode = code,
                tagName = "",
                htmlUrl = "",
                apkName = file.name,
                apkUrl = "",
                apkSizeBytes = file.length(),
                sha256 = null,
                notes = null
            ),
            file = file
        )
    }

    /** Installs the staged APK. Returns false when nothing is staged or the install failed. */
    suspend fun installStaged(): Boolean = withContext(Dispatchers.IO) {
        val staged = stagedUpdate() ?: run {
            Logger.w("installStaged() called with nothing staged")
            return@withContext false
        }
        when (installer.verifySignature(staged.file)) {
            SignatureCheck.Match -> Unit
            else -> {
                clearDownload()
                return@withContext false
            }
        }
        installer.install(staged.file)
    }

    /** Deletes any staged download (after a successful install, or when the user declines). */
    fun clearDownload() = prefs.clearStaged()

    /**
     * The versionCode stored inside [apk], or null when it cannot be read.
     *
     * WHY: the update check reads a versionCode out of the release *notes* (the release workflow
     * writes it there), and notes can be wrong — an old release re-tagged `latest`, a manual
     * upload, a copy-paste in the release body. The APK's own manifest is what Android compares
     * against to decide whether an install is a downgrade, so that is what the updater must
     * compare too.
     */
    fun apkVersionCode(apk: File): Int? = runCatching {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        info?.let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode }
    }.onFailure { Logger.w("Could not read the downloaded APK's versionCode: ${it.message}") }
        .getOrNull()

    /** Remembers that the user does not want to be nagged about [versionCode] again. */
    fun skipVersion(versionCode: Int) {
        prefs.skippedVersionCode = versionCode
    }

    /** Forgets a previous "not now" so the update is offered again. */
    fun unskipVersion() {
        prefs.skippedVersionCode = 0
    }

    private fun destinationFor(info: UpdateInfo): File = File(
        File(context.applicationContext.cacheDir, UPDATE_DIR),
        "Hearth-${info.versionCode}.apk"
    )

    companion object {
        private const val UPDATE_DIR = "updates"

        /**
         * Entry point for the callers that have no dependency graph — the service, the
         * install-result receiver and the UI fragments — and which all hold an application
         * Context anyway.
         *
         * Deliberately NOT cached in a static field. A static reference to an object that
         * holds a Context is a lint `StaticFieldLeak` error, and there would be nothing to
         * gain from caching: every piece of updater state lives in SharedPreferences or on
         * disk, so a fresh instance is two small allocations.
         */
        fun get(context: Context): UpdateManager = UpdateManager(context.applicationContext)
    }
}

/** An APK that has been downloaded, verified and is waiting to be installed. */
data class StagedUpdate(val info: UpdateInfo, val file: File)

/** Outcome of [UpdateManager.downloadAndStage]. */
sealed class StageResult {
    data class Staged(val update: StagedUpdate) : StageResult()
    data class Failed(
        val message: String,
        val reason: UpdateFailureReason = UpdateFailureReason.DOWNLOAD_FAILED
    ) : StageResult()
}
