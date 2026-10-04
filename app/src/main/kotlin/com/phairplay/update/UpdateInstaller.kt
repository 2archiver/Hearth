package com.phairplay.update

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.phairplay.util.Logger
import java.io.File
import java.security.MessageDigest

/**
 * UpdateInstaller — installs a downloaded Hearth APK and, crucially, refuses one that
 * would not install.
 *
 * WHY: "App not installed as package conflicts with an existing package" is Android's way
 * of saying *the APK I was handed is signed with a different key than the installed app*.
 * A generic updater would happily download a build signed with a throw-away CI debug key,
 * hand it to the system and show the user that error — which is the exact problem this
 * class exists to remove. So before anything is installed we compare the downloaded APK's
 * signing certificate with our own ([signatureMatches]); if they differ we stop and say
 * why, instead of reproducing the failure.
 *
 * HOW: [android.content.pm.PackageInstaller] (API 21+, the supported replacement for
 * `ACTION_INSTALL_PACKAGE`). On Android 12+ we ask for
 * [android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED], which is
 * permitted for a self-update — the app replacing itself — so the update usually needs no
 * confirmation dialog at all. Where the platform refuses, we fall back to a session that
 * shows the standard confirmation.
 */
class UpdateInstaller(private val context: Context) {

    /**
     * Compares the signing certificate of [apk] with the one the running app is signed with.
     *
     * Returns a [SignatureCheck] so the caller can distinguish the three cases that matter:
     * match (install away), mismatch (do NOT install — explain instead), and unreadable
     * (the APK is truncated/corrupt — also do not install).
     */
    @Suppress("DEPRECATION")
    fun verifySignature(apk: File): SignatureCheck {
        if (!apk.isFile || apk.length() == 0L) return SignatureCheck.Unreadable
        val theirs = readCertificates(apk) ?: return SignatureCheck.Unreadable
        val ours = ownCertificates()
        if (ours.isEmpty()) return SignatureCheck.Unknown
        return if (theirs.any { it in ours }) SignatureCheck.Match else SignatureCheck.Mismatch
    }

    /**
     * True when Android will let Hearth start a package install. On Android 8+ this is the
     * per-app "Install unknown apps" special access; without it PackageInstaller sessions are
     * rejected (on some TV builds silently), which is how 1.8.1 ended up "installing" forever.
     */
    fun canRequestInstalls(): Boolean =
        runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)

    /**
     * The Settings screen where the user grants "Install unknown apps" to Hearth. minSdk is 29,
     * so the action always exists, but some TV builds lack an Activity for it — callers catch.
     */
    fun installPermissionSettingsIntent(): android.content.Intent =
        android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}")
        )

    /**
     * Installs [apk] over this app.
     *
     * Runs on the calling thread; call from `Dispatchers.IO`. [InstallStart.QUEUED] means the
     * session was committed — the result (including a confirmation request) then arrives in
     * [UpdateInstallReceiver], and Android restarts Hearth when the install completes.
     */
    fun install(apk: File): InstallStart {
        if (!apk.isFile) {
            Logger.e("Update install: file does not exist — ${apk.absolutePath}")
            return InstallStart.NOTHING_STAGED
        }
        if (!canRequestInstalls()) {
            Logger.w("Update install: 'Install unknown apps' is not granted to Hearth")
            return InstallStart.PERMISSION_REQUIRED
        }
        return try {
            if (installWithUserAction(apk, requireUserAction = false) ||
                installWithUserAction(apk, requireUserAction = true)
            ) InstallStart.QUEUED else InstallStart.FAILED
        } catch (e: Exception) {
            Logger.e("Update install failed", e)
            InstallStart.FAILED
        }
    }

    // ─── Private ─────────────────────────────────────────────────────────────

    /** One install attempt. `false` means "the platform rejected this mode" (not fatal). */
    private fun installWithUserAction(apk: File, requireUserAction: Boolean): Boolean {
        val packageInstaller = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(context.packageName)
        if (!requireUserAction && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ allows a package to replace itself without a confirmation dialog.
            // If the platform disagrees it throws SecurityException → we retry with the
            // dialog the user already expects.
            params.setRequireUserAction(
                android.content.pm.PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
            )
        }

        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)
        try {
            session.openWrite(SESSION_NAME, 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // MUTABLE, not IMMUTABLE: PackageInstaller adds EXTRA_STATUS and, for a confirmation,
            // EXTRA_INTENT to this intent. An immutable PendingIntent drops them, so the receiver
            // saw "failure" with no confirmation to show (the 1.8.1 install that never finished).
            // Safe here because the intent is explicit and the receiver is not exported.
            val flags = InstallPolicy.installResultPendingIntentFlags(Build.VERSION.SDK_INT)
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                UpdateInstallReceiver.intent(context, sessionId),
                flags
            )
            session.commit(pendingIntent.intentSender)
            Logger.i("Update install committed (session=$sessionId, userAction=$requireUserAction)")
            return true
        } catch (e: SecurityException) {
            Logger.w("Silent self-update refused by the platform — retrying with confirmation: ${e.message}")
            abandonQuietly(packageInstaller, sessionId)
            return false
        } catch (e: Exception) {
            Logger.e("Update install session failed", e)
            abandonQuietly(packageInstaller, sessionId)
            return false
        } finally {
            runCatching { session.close() }
        }
    }

    private fun abandonQuietly(
        packageInstaller: android.content.pm.PackageInstaller,
        sessionId: Int
    ) {
        try {
            packageInstaller.abandonSession(sessionId)
        } catch (e: Exception) {
            Logger.d("Could not abandon install session $sessionId (non-fatal)")
        }
    }

    /** Lowercase hex SHA-256 fingerprints of every certificate [apk] is signed with. */
    @Suppress("DEPRECATION")
    private fun readCertificates(apk: File): Set<String>? {
        val info = try {
            context.packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } catch (e: Exception) {
            Logger.w("Could not read the downloaded APK's signature: ${e.message}")
            null
        } ?: return null

        val signatures = try {
            val signingInfo = info.signingInfo ?: return null
            signingInfo.signingCertificateHistory?.toList()
                ?: signingInfo.apkContentsSigners?.toList()
                ?: return null
        } catch (e: Exception) {
            info.signatures?.toList() ?: return null
        }
        val fingerprints = signatures.mapNotNull { it.fingerprint() }.toSet()
        return fingerprints.takeIf { it.isNotEmpty() }
    }

    /** Lowercase hex SHA-256 fingerprints of this app's own signing certificates. */
    @Suppress("DEPRECATION")
    private fun ownCertificates(): Set<String> {
        val info = try {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
        } catch (e: Exception) {
            Logger.w("Could not read our own signature: ${e.message}")
            null
        } ?: return emptySet()

        return try {
            val signingInfo = info.signingInfo
            val signatures = signingInfo?.signingCertificateHistory?.toList()
                ?: signingInfo?.apkContentsSigners?.toList()
                ?: info.signatures?.toList()
                ?: emptyList()
            signatures.mapNotNull { it.fingerprint() }.toSet()
        } catch (e: Exception) {
            info.signatures?.mapNotNull { it.fingerprint() }?.toSet() ?: emptySet()
        }
    }

    private fun Signature.fingerprint(): String? = try {
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            // and 0xFF: a Byte formats as a *signed* value, so -1 would print "ffffffff".
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val SESSION_NAME = "phairplay-update.apk"
    }
}

/** Result of [UpdateInstaller.verifySignature]. */
sealed class SignatureCheck {
    /** The APK is signed with the same key as the running app — safe to install. */
    object Match : SignatureCheck()

    /**
     * Signed with a *different* key. Installing it is what produces
     * "App not installed as package conflicts with an existing package", so we refuse and
     * tell the user to uninstall once (or to install a build signed with this app's key).
     */
    object Mismatch : SignatureCheck()

    /** Our own signature could not be read (shouldn't happen); be conservative. */
    object Unknown : SignatureCheck()

    /** The downloaded file is not a readable APK — usually a truncated or failed download. */
    object Unreadable : SignatureCheck()
}
