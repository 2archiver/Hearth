package com.phairplay.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import com.phairplay.util.Logger

/**
 * UpdateInstallReceiver — receives the result of the [PackageInstaller] session started by
 * [UpdateInstaller] and turns the platform's status codes into something a human can act on.
 *
 * WHY: `session.commit()` returns as soon as the install is *queued*. Without a receiver the
 * app would tell the user "installed!" when the install had, in fact, been rejected for
 * insufficient storage — leaving them staring at the old version and an unexplained lie.
 *
 * Registered in AndroidManifest.xml with an explicit Intent (see [intent]), so only
 * PackageInstaller can reach it.
 */
class UpdateInstallReceiver : BroadcastReceiver() {

    // getParcelableExtra(String) is deprecated from Android 13 on; the Tiramisu overload is
    // used above that and this suppression covers the legacy branch.
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        val prefs = UpdatePreferences(context)
        val state = InstallPolicy.stateFor(status)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // The platform wants the user to confirm. Relaunch the confirmation as a
                // fresh task: the confirmation Activity cannot start from a broadcast.
                // (This extra only arrives because the commit PendingIntent is MUTABLE.)
                val confirm: Intent? =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    } else {
                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    }
                Logger.i("Update install needs confirmation (session=$sessionId)")
                if (confirm == null) {
                    Logger.e("Install confirmation requested but no confirmation intent was supplied")
                    prefs.recordInstallState(
                        InstallState.FAILED,
                        "Android asked for confirmation but did not supply the screen. Try Install again."
                    )
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(confirm)
                    prefs.recordInstallState(InstallState.AWAITING_CONFIRMATION)
                } catch (e: Exception) {
                    // Android 10+ blocks background activity starts; Hearth is normally in the
                    // foreground here, but when it is not, Settings shows "Confirm install".
                    Logger.e("Could not open the install confirmation", e)
                    prefs.recordInstallState(
                        InstallState.FAILED,
                        "Android's install confirmation could not be opened. Open Hearth and choose Install again."
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Logger.i("Update installed successfully (session=$sessionId)")
                // Android has already replaced us and will restart the app; just tidy up.
                UpdateManager.get(context).clearDownload()
                prefs.recordInstallState(InstallState.SUCCEEDED)
            }

            else -> {
                val text = InstallPolicy.failureMessage(status, message)
                if (state == InstallState.CANCELLED) {
                    Logger.w("Update install aborted (session=$sessionId): ${message ?: "no reason given"}")
                } else {
                    Logger.e("Update install failed (session=$sessionId, status=$status): ${message ?: "unknown"}")
                }
                prefs.recordInstallState(state, text)
            }
        }
    }

    companion object {
        const val ACTION = "com.phairplay.update.INSTALL_RESULT"

        /** Explicit intent for install session [sessionId] — never a broadcast to everyone. */
        fun intent(context: Context, sessionId: Int): Intent =
            Intent(context, UpdateInstallReceiver::class.java)
                .setAction(ACTION)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
    }
}
