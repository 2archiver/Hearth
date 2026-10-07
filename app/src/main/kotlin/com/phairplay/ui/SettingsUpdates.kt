package com.phairplay.ui

import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phairplay.BuildConfig
import com.phairplay.update.GitHubRepositoryPolicy
import com.phairplay.R
import com.phairplay.update.InstallStart
import com.phairplay.update.InstallState
import com.phairplay.update.StageResult
import com.phairplay.update.UpdateCheck
import com.phairplay.update.UpdateFlow
import com.phairplay.update.UpdateInfo
import com.phairplay.util.Logger
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.phairplay.settings.SettingsRepository

/**
 * Owns the update card, download progress and install dialogs for one Settings view.
 * Keeping it separate lets preferences stay readable and bounds references to the view lifecycle.
 * Create with a SettingsFragment and its root, then refresh on resume.
 */
internal class SettingsUpdates(
    private val fragment: Fragment, root: View, private val settingsRepository: SettingsRepository
) {
    private val rowCheckUpdates: LinearLayout = root.findViewById(R.id.row_check_updates)
    private val textCheckUpdatesValue: TextView = root.findViewById(R.id.text_check_updates_value)
    private val textUpdateBadge: TextView = root.findViewById(R.id.text_update_badge)
    private val textCheckUpdatesAction: TextView = root.findViewById(R.id.text_check_updates_action)
    private val progressUpdate: ProgressBar = root.findViewById(R.id.progress_update)
    private var updateCheckRunning = false
    private var pendingAvailableUpdate: UpdateInfo? = null
    private var pendingKeyMismatchUpdate: UpdateInfo? = null
    private val viewLifecycleOwner get() = fragment.viewLifecycleOwner
    private val isAdded get() = fragment.isAdded
    private fun requireContext() = fragment.requireContext()
    private fun getString(id: Int, vararg args: Any) = fragment.getString(id, *args)
    private fun startActivity(intent: Intent) = fragment.startActivity(intent)

    init {
        rowCheckUpdates.setOnClickListener {
            if (updateCheckRunning) return@setOnClickListener
            val manager = com.phairplay.update.UpdateManager.get(requireContext())
            val staged = manager.stagedUpdate()
            when {
                staged != null && manager.installState()?.first == InstallState.PERMISSION_REQUIRED &&
                    !manager.canRequestInstalls() -> openInstallPermissionSettings()
                staged != null -> showInstallDialog(staged.info)
                pendingKeyMismatchUpdate != null -> showSigningKeyMismatchDialog(pendingKeyMismatchUpdate)
                pendingAvailableUpdate != null -> showAvailableDialog(pendingAvailableUpdate!!)
                else -> runUpdateCheck()
            }
        }
    }

    /** Refresh a staged download when returning from Android's installer. */
    fun refresh() {
        if (!updateCheckRunning) refreshStagedUpdateRow()
    }

    private fun refreshStagedUpdateRow() {
        val manager = com.phairplay.update.UpdateManager.get(requireContext())
        val staged = manager.stagedUpdate()
        if (staged != null && showInstallState(manager, staged.info)) {
            pendingAvailableUpdate = null
            pendingKeyMismatchUpdate = null
        } else if (staged != null) {
            pendingAvailableUpdate = null
            pendingKeyMismatchUpdate = null
            setUpdateCardState(
                message = getString(R.string.update_ready_message_short, staged.info.versionName),
                badge = R.string.update_status_ready,
                action = R.string.update_action_install,
                badgeColor = R.color.status_running
            )
        } else {
            val migration = pendingKeyMismatchUpdate
            val available = pendingAvailableUpdate
            when {
                migration != null -> setUpdateCardState(
                    message = getString(R.string.update_key_mismatch_card),
                    badge = R.string.update_status_action_needed,
                    action = R.string.update_action_view_steps,
                    badgeColor = R.color.status_transitioning
                )
                available != null -> setUpdateCardState(
                    message = getString(
                        R.string.update_available_message,
                        available.shortLabel(),
                        available.versionCode,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE
                    ),
                    badge = R.string.update_status_available,
                    action = R.string.update_action_download
                )
                else -> setUpdateCardState(
                    message = getString(
                        R.string.update_current_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
                    ),
                    badge = null,
                    action = R.string.update_action_check_now
                )
            }
        }
    }

    private fun setUpdateCardState(
        message: CharSequence,
        badge: Int?,
        action: Int,
        badgeColor: Int = R.color.accent_blue
    ) {
        textCheckUpdatesValue.text = message
        textCheckUpdatesAction.setText(action)
        if (badge == null) {
            textUpdateBadge.visibility = View.GONE
        } else {
            textUpdateBadge.setText(badge)
            textUpdateBadge.setTextColor(requireContext().getColor(badgeColor))
            textUpdateBadge.visibility = View.VISIBLE
        }
    }

    private fun runUpdateCheck() {
        if (updateCheckRunning) return
        updateCheckRunning = true
        rowCheckUpdates.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val settings = settingsRepository.settingsFlow.first()
                val staged = com.phairplay.update.UpdateManager.get(requireContext()).stagedUpdate()
                if (staged != null) {
                    showInstallDialog(staged.info)
                    return@launch
                }

                pendingAvailableUpdate = null
                pendingKeyMismatchUpdate = null
                progressUpdate.visibility = View.GONE
                setUpdateCardState(
                    message = getString(R.string.update_checking),
                    badge = R.string.update_status_checking,
                    action = R.string.update_action_check_now
                )
                val result = UpdateFlow.run(
                    context = requireContext(),
                    autoDownload = false,      // the card/dialog drives the download from here
                    autoInstall = settings.autoInstallUpdates,
                    forceCheck = true
                )

                when (result) {
                    is UpdateCheck.Available -> {
                        pendingAvailableUpdate = result.info
                        val installed = com.phairplay.update.UpdateManager.get(requireContext())
                            .installedVersionName()
                        setUpdateCardState(
                            message = if (result.skipped) {
                                getString(R.string.update_skipped_message, result.info.shortLabel())
                            } else {
                                getString(
                                    R.string.update_available_message,
                                    result.info.shortLabel(),
                                    result.info.versionCode,
                                    installed,
                                    BuildConfig.VERSION_CODE
                                )
                            },
                            badge = R.string.update_status_available,
                            action = R.string.update_action_download
                        )
                        showAvailableDialog(result.info)
                    }

                    is UpdateCheck.UpToDate -> {
                        val message = when {
                            result.publishedVersionUnknown -> getString(
                                R.string.update_up_to_date_unknown,
                                result.info.versionName,
                                BuildConfig.VERSION_NAME,
                                BuildConfig.VERSION_CODE
                            )
                            result.newerThanPublished -> getString(
                                R.string.update_up_to_date_ahead,
                                BuildConfig.VERSION_NAME,
                                result.info.shortLabel()
                            )
                            else -> getString(R.string.update_up_to_date, result.info.shortLabel())
                        }
                        setUpdateCardState(
                            message = message,
                            badge = R.string.update_status_current,
                            action = R.string.update_action_check_again,
                            badgeColor = R.color.status_running
                        )
                    }

                    is UpdateCheck.Failed -> {
                        if (result.reason == com.phairplay.update.UpdateFailureReason.SIGNATURE_MISMATCH) {
                            pendingKeyMismatchUpdate = result.info
                            setUpdateCardState(
                                message = getString(R.string.update_key_mismatch_card),
                                badge = R.string.update_status_action_needed,
                                action = R.string.update_action_view_steps,
                                badgeColor = R.color.status_transitioning
                            )
                            showSigningKeyMismatchDialog(result.info)
                        } else {
                            setUpdateCardState(
                                message = result.message,
                                badge = R.string.update_status_error,
                                action = R.string.update_action_retry,
                                badgeColor = R.color.status_stopped
                            )
                            showMessageDialog(R.string.update_error_title, result.message)
                        }
                    }

                    is UpdateCheck.Skipped -> refreshStagedUpdateRow()
                }
            } finally {
                updateCheckRunning = false
                rowCheckUpdates.isEnabled = true
            }
        }
    }

    private fun showAvailableDialog(info: UpdateInfo) {
        val installed = com.phairplay.update.UpdateManager.get(requireContext()).installedVersionName()
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.update_dialog_title)
            .setMessage(
                getString(
                    R.string.update_available_message,
                    info.shortLabel(),
                    info.versionCode,
                    installed,
                    BuildConfig.VERSION_CODE
                )
            )
            .setPositiveButton(R.string.update_action_download) { _, _ -> downloadAndOfferInstall(info) }
            .setNeutralButton(R.string.update_action_skip) { _, _ ->
                com.phairplay.update.UpdateManager.get(requireContext()).skipVersion(info.versionCode)
                pendingAvailableUpdate = info
                setUpdateCardState(
                    message = getString(R.string.update_skipped_message, info.shortLabel()),
                    badge = R.string.update_status_available,
                    action = R.string.update_action_download
                )
                Logger.i("User skipped update ${info.shortLabel()}")
            }
            .setNegativeButton(R.string.update_action_later, null)
            .show()
    }

    private fun downloadAndOfferInstall(info: UpdateInfo) {
        if (updateCheckRunning) return
        updateCheckRunning = true
        rowCheckUpdates.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            pendingAvailableUpdate = null
            progressUpdate.isIndeterminate = false
            progressUpdate.progress = 0
            progressUpdate.visibility = View.VISIBLE
            setUpdateCardState(
                message = getString(R.string.update_downloading),
                badge = R.string.update_status_downloading,
                action = R.string.update_action_check_now
            )

            val result = try {
                com.phairplay.update.UpdateManager.get(requireContext())
                    .downloadAndStage(info) { percent ->
                        rowCheckUpdates.post {
                            if (isAdded) {
                                progressUpdate.progress = percent
                                textCheckUpdatesValue.text = getString(R.string.update_download_progress, percent)
                            }
                        }
                    }
            } finally {
                updateCheckRunning = false
                rowCheckUpdates.isEnabled = true
                progressUpdate.visibility = View.GONE
            }

            when (result) {
                is StageResult.Staged -> {
                    pendingKeyMismatchUpdate = null
                    refreshStagedUpdateRow()
                    showInstallDialog(result.update.info)
                }

                is StageResult.Failed -> {
                    if (result.reason == com.phairplay.update.UpdateFailureReason.SIGNATURE_MISMATCH) {
                        pendingKeyMismatchUpdate = info
                        setUpdateCardState(
                            message = getString(R.string.update_key_mismatch_card),
                            badge = R.string.update_status_action_needed,
                            action = R.string.update_action_view_steps,
                            badgeColor = R.color.status_transitioning
                        )
                        showSigningKeyMismatchDialog(info)
                    } else {
                        setUpdateCardState(
                            message = result.message,
                            badge = R.string.update_status_error,
                            action = R.string.update_action_retry,
                            badgeColor = R.color.status_stopped
                        )
                        showMessageDialog(R.string.update_download_failed_title, result.message)
                    }
                }
            }
        }
    }

    private fun showInstallDialog(info: UpdateInfo) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.update_ready_title)
            .setMessage(getString(R.string.update_ready_message, info.shortLabel()))
            .setPositiveButton(R.string.update_action_install) { _, _ -> startInstall() }
            .setNegativeButton(R.string.update_action_later, null)
            .show()
    }

    /**
     * Renders the last install state on the card. Returns false when there is nothing to show
     * (so the caller falls back to "ready to install").
     */
    private fun showInstallState(
        manager: com.phairplay.update.UpdateManager,
        info: UpdateInfo
    ): Boolean {
        val (state, message) = manager.installState() ?: return false
        val label = info.shortLabel()
        when (state) {
            InstallState.INSTALLING -> setUpdateCardState(
                getString(R.string.update_installing_card, label),
                R.string.update_status_installing, R.string.update_action_install,
                R.color.status_transitioning
            )
            InstallState.AWAITING_CONFIRMATION -> setUpdateCardState(
                getString(R.string.update_confirm_card, label),
                R.string.update_status_confirm, R.string.update_action_install,
                R.color.status_transitioning
            )
            InstallState.PERMISSION_REQUIRED -> {
                // Returning from Android's permission page: if it is granted now, say so by
                // offering the install again instead of repeating the request.
                if (manager.canRequestInstalls()) {
                    manager.clearInstallState()
                    return false
                }
                setUpdateCardState(
                    getString(R.string.update_permission_card),
                    R.string.update_status_permission, R.string.update_action_open_settings,
                    R.color.status_transitioning
                )
            }
            InstallState.CANCELLED -> setUpdateCardState(
                getString(R.string.update_cancelled_card, label),
                R.string.update_status_cancelled, R.string.update_action_install,
                R.color.status_transitioning
            )
            InstallState.FAILED -> setUpdateCardState(
                message ?: getString(R.string.update_install_failed),
                R.string.update_status_install_failed, R.string.update_action_retry,
                R.color.status_stopped
            )
            InstallState.SUCCEEDED -> return false
        }
        return true
    }

    /** Starts the install and turns every outcome into a visible state or a next step. */
    private fun startInstall() {
        viewLifecycleOwner.lifecycleScope.launch {
            val manager = com.phairplay.update.UpdateManager.get(requireContext())
            val staged = manager.stagedUpdate()
            setUpdateCardState(
                getString(R.string.update_installing_card, staged?.info?.shortLabel() ?: ""),
                R.string.update_status_installing, R.string.update_action_install,
                R.color.status_transitioning
            )
            when (manager.installStaged()) {
                InstallStart.QUEUED -> Logger.i("Update install queued — waiting for Android")
                InstallStart.PERMISSION_REQUIRED -> showInstallPermissionDialog()
                InstallStart.NOTHING_STAGED ->
                    showMessageDialog(R.string.update_dialog_title, getString(R.string.update_install_nothing_staged))
                InstallStart.VERIFICATION_FAILED ->
                    showMessageDialog(R.string.update_install_failed_title, getString(R.string.update_install_verification_failed))
                InstallStart.FAILED ->
                    showMessageDialog(R.string.update_install_failed_title, getString(R.string.update_install_failed))
            }
            if (isAdded) refreshStagedUpdateRow()
        }
    }

    private fun showInstallPermissionDialog() {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.update_permission_title)
            .setMessage(R.string.update_permission_message)
            .setPositiveButton(R.string.update_action_open_settings) { _, _ -> openInstallPermissionSettings() }
            .setNegativeButton(R.string.update_action_later, null)
            .show()
    }

    private fun openInstallPermissionSettings() {
        val intent = com.phairplay.update.UpdateManager.get(requireContext()).installPermissionSettingsIntent()
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Several Android TV builds ship without this Settings activity.
            Logger.w("Could not open the install-permission screen: ${e.message}")
            showMessageDialog(R.string.update_permission_title, getString(R.string.update_permission_unavailable))
        }
    }

    private fun showSigningKeyMismatchDialog(info: UpdateInfo?) {
        val message = if (info != null) {
            getString(R.string.update_key_mismatch_message, info.shortLabel())
        } else {
            getString(R.string.update_key_mismatch_message_unknown)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.update_key_mismatch_title)
            .setMessage(message)
            .setPositiveButton(R.string.update_action_open_release) { _, _ -> openUpdateReleasePage() }
            .setNegativeButton(R.string.update_action_close, null)
            .show()
    }

    private fun openUpdateReleasePage() {
        val repo = GitHubRepositoryPolicy.canonicalRepository(BuildConfig.UPDATE_REPO)
            ?: GitHubRepositoryPolicy.CANONICAL_REPOSITORY
        val releaseUrl = "https://github.com/$repo/releases/latest"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl)))
        } catch (e: Exception) {
            Logger.w("Could not open the Hearth release page: ${e.message}")
            showMessageDialog(
                R.string.update_open_release_title,
                getString(R.string.update_open_release_failed, releaseUrl)
            )
        }
    }

    private fun showMessageDialog(titleRes: Int, message: String) {
        AlertDialog.Builder(requireContext())
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.update_action_close, null)
            .show()
    }
}
