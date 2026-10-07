package com.phairplay.update

import android.app.PendingIntent
import android.content.pm.PackageInstaller

/**
 * Visible states of a self-update install. Persisted by [UpdatePreferences.recordInstallState]
 * so Settings can say what actually happened instead of a permanent "Installing…".
 */
enum class InstallState {
    /** The session was committed; Android is installing (or about to ask for confirmation). */
    INSTALLING,
    /** Android is showing its confirmation screen. */
    AWAITING_CONFIRMATION,
    /** "Install unknown apps" is off for Hearth; the user has to allow it first. */
    PERMISSION_REQUIRED,
    /** The user backed out of Android's confirmation. */
    CANCELLED,
    /** Android rejected the install; [UpdatePreferences.installMessage] says why. */
    FAILED,
    /** Android reported success. Normally the process is replaced before anyone sees this. */
    SUCCEEDED
}

/** Outcome of asking the installer to start. */
enum class InstallStart {
    /** A PackageInstaller session was committed; the result arrives in [UpdateInstallReceiver]. */
    QUEUED,
    /** Android 8+ "Install unknown apps" is not granted — open its settings page first. */
    PERMISSION_REQUIRED,
    /** A verified update is ready, but playback is active; wait until casting has stopped. */
    PLAYBACK_ACTIVE,
    /** Nothing was staged (or the staged file was obsolete) — nothing to install. */
    NOTHING_STAGED,
    /** The staged file failed verification and was deleted. */
    VERIFICATION_FAILED,
    /** The platform refused to open or commit the session. */
    FAILED
}

/**
 * Pure decisions behind the updater, kept free of Context so the JVM test-runner covers them.
 *
 * Each of these is a bug that shipped in 1.8.1:
 *  - the install-result PendingIntent was IMMUTABLE, so PackageInstaller could not attach
 *    EXTRA_STATUS / EXTRA_INTENT; the receiver saw a bare intent, read the default
 *    STATUS_FAILURE and never launched Android's confirmation screen;
 *  - a downloaded APK whose build was already installed stayed "ready to install" forever;
 *  - a release whose notes over-stated its versionCode was downloaded and offered on every check.
 */
object InstallPolicy {

    /**
     * Flags for the PendingIntent handed to `PackageInstaller.Session.commit`.
     *
     * It MUST be mutable on Android 12+: the platform fills in the status extras. Below API 31
     * mutability is the default and FLAG_MUTABLE does not exist.
     */
    @android.annotation.SuppressLint("InlinedApi") // guarded by sdkInt; the constant is inlined
    fun installResultPendingIntentFlags(sdkInt: Int): Int =
        if (sdkInt >= 31) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    /** A staged APK is obsolete once its build is installed (or something newer is). */
    fun isStagedObsolete(stagedVersionCode: Int, installedVersionCode: Int): Boolean =
        stagedVersionCode in 1..installedVersionCode

    /**
     * True when a published release must not be offered again: its downloaded APK has already
     * been shown to be no newer than this install. A *different* published code clears it.
     */
    fun isKnownStaleRelease(publishedVersionCode: Int, rejectedPublishedVersionCode: Int): Boolean =
        rejectedPublishedVersionCode != 0 && publishedVersionCode == rejectedPublishedVersionCode

    /** Maps a PackageInstaller status to the visible state Settings shows. */
    fun stateFor(status: Int): InstallState = when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> InstallState.AWAITING_CONFIRMATION
        PackageInstaller.STATUS_SUCCESS -> InstallState.SUCCEEDED
        PackageInstaller.STATUS_FAILURE_ABORTED -> InstallState.CANCELLED
        else -> InstallState.FAILED
    }

    /** A short, actionable explanation for a failed install status. */
    fun failureMessage(status: Int, platformMessage: String?): String {
        val base = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was cancelled."
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "The TV's policy blocked the install."
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "Android reports a conflict with the installed Hearth (usually a different signing key)."
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "This APK is not compatible with this TV."
            PackageInstaller.STATUS_FAILURE_INVALID -> "The downloaded APK is invalid. Download it again."
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage on the TV. Free some space and retry."
            else -> "Android could not install the update."
        }
        val detail = platformMessage?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        return "$base ($detail)"
    }
}
