package com.phairplay.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phairplay.BuildConfig
import com.phairplay.R
import com.phairplay.service.ServiceController
import com.phairplay.settings.AppSettings
import com.phairplay.settings.SettingsRepository
import com.phairplay.util.Logger
import com.phairplay.update.StageResult
import com.phairplay.update.UpdateCheck
import com.phairplay.update.UpdateFlow
import com.phairplay.update.UpdateInfo
import com.phairplay.util.MdnsNames
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SettingsFragment — Settings screen for Hearth.
 *
 * WHY: Centralizes all user-configurable options in one screen. By separating
 * settings into their own Fragment, we keep MainActivity lean and make it easy
 * to navigate to/from settings via the nav panel.
 *
 * HOW: Reads current settings from [SettingsRepository] and populates the UI.
 * Each toggle/row saves immediately when changed (no "Save" button needed).
 * Receiver settings apply on service restart; update preferences are observed live by the service.
 *
 * Navigation: accessed via the "Settings" item in MainActivity's nav panel.
 */
class SettingsFragment : Fragment() {

    private lateinit var settingsRepository: SettingsRepository

    // Section header TextViews — set via include layout tag IDs
    private lateinit var headerDisplay: TextView
    private lateinit var headerProtocols: TextView
    private lateinit var headerAirPlay: TextView
    private lateinit var headerService: TextView
    private lateinit var headerDeveloper: TextView
    private lateinit var headerUpdates: TextView
    private lateinit var headerAbout: TextView

    // Settings rows
    private lateinit var rowDisplayName: LinearLayout
    private lateinit var textDisplayNameValue: TextView
    private lateinit var rowAirPlay: View
    private lateinit var rowMirrorAudio: View
    private lateinit var rowStartOnBoot: View
    private lateinit var rowDebugOverlay: View
    private lateinit var rowForceHighRes: View
    private lateinit var textVersionValue: TextView
    private lateinit var rowCheckUpdates: LinearLayout
    private lateinit var textCheckUpdatesValue: TextView
    private lateinit var textUpdateBadge: TextView
    private lateinit var textCheckUpdatesAction: TextView
    private lateinit var progressUpdate: ProgressBar
    private lateinit var rowAutoCheckUpdates: View
    private lateinit var rowAutoDownloadUpdates: View
    private lateinit var rowAutoInstallUpdates: View
    private lateinit var rowReset: LinearLayout

    /** Set while an update check or download is in flight, so repeat taps cannot race it. */
    private var updateCheckRunning = false

    /** The latest result remains actionable from the large TV-friendly update card. */
    private var pendingAvailableUpdate: UpdateInfo? = null
    private var pendingKeyMismatchUpdate: UpdateInfo? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsRepository = SettingsRepository(requireContext())
        bindViews(view)
        setSectionTitles()
        setRowLabels()
        loadAndPopulate()
    }

    override fun onResume() {
        super.onResume()
        if (::rowCheckUpdates.isInitialized && !updateCheckRunning) refreshStagedUpdateRow()
    }

    // ─── View Binding ────────────────────────────────────────────────────────

    private fun bindViews(view: View) {
        // Each header is an <include> of settings_section_header.xml (a bare
        // TextView). The include's android:id IS the TextView's id, so look it up
        // directly — no nested lookup.
        headerDisplay   = view.findViewById(R.id.header_display)
        headerProtocols = view.findViewById(R.id.header_protocols)
        headerAirPlay   = view.findViewById(R.id.header_airplay)
        headerService   = view.findViewById(R.id.header_service)
        headerDeveloper = view.findViewById(R.id.header_developer)
        headerUpdates   = view.findViewById(R.id.header_updates)
        headerAbout     = view.findViewById(R.id.header_about)

        rowDisplayName      = view.findViewById(R.id.row_display_name)
        textDisplayNameValue = view.findViewById(R.id.text_display_name_value)
        rowAirPlay          = view.findViewById(R.id.row_airplay)
        rowMirrorAudio      = view.findViewById(R.id.row_mirror_audio)
        rowStartOnBoot      = view.findViewById(R.id.row_start_on_boot)
        rowDebugOverlay     = view.findViewById(R.id.row_debug_overlay)
        rowForceHighRes     = view.findViewById(R.id.row_force_high_res)
        textVersionValue    = view.findViewById(R.id.text_version_value)
        rowCheckUpdates      = view.findViewById(R.id.row_check_updates)
        textCheckUpdatesValue = view.findViewById(R.id.text_check_updates_value)
        textUpdateBadge      = view.findViewById(R.id.text_update_badge)
        textCheckUpdatesAction = view.findViewById(R.id.text_check_updates_action)
        progressUpdate       = view.findViewById(R.id.progress_update)
        rowAutoCheckUpdates  = view.findViewById(R.id.row_auto_check_updates)
        rowAutoDownloadUpdates = view.findViewById(R.id.row_auto_download_updates)
        rowAutoInstallUpdates = view.findViewById(R.id.row_auto_install_updates)
        rowReset            = view.findViewById(R.id.row_reset)
    }

    /** Sets all section header titles from string resources. */
    private fun setSectionTitles() {
        headerDisplay.setText(R.string.settings_section_display)
        headerProtocols.setText(R.string.settings_section_protocols)
        headerAirPlay.setText(R.string.settings_section_airplay)
        headerService.setText(R.string.settings_section_service)
        headerDeveloper.setText(R.string.settings_section_developer)
        headerUpdates.setText(R.string.settings_section_updates)
        headerAbout.setText(R.string.settings_section_about)
    }

    /** Sets all row labels and subtitles from string resources. */
    private fun setRowLabels() {
        configureToggleRow(rowAirPlay,      R.string.setting_airplay_enabled,    R.string.setting_airplay_subtitle)
        configureToggleRow(rowMirrorAudio,  R.string.setting_mirror_audio,       R.string.setting_mirror_audio_subtitle)
        configureToggleRow(rowStartOnBoot,  R.string.setting_start_on_boot,      0)
        configureToggleRow(rowDebugOverlay, R.string.setting_debug_overlay,      R.string.setting_debug_overlay_subtitle)
        configureToggleRow(rowForceHighRes, R.string.setting_force_high_res,      R.string.setting_force_high_res_subtitle)
        configureToggleRow(rowAutoCheckUpdates,    R.string.setting_auto_check_updates,
            R.string.setting_auto_check_updates_subtitle)
        configureToggleRow(rowAutoDownloadUpdates, R.string.setting_auto_download_updates,
            R.string.setting_auto_download_updates_subtitle)
        configureToggleRow(rowAutoInstallUpdates,  R.string.setting_auto_install_updates,
            R.string.setting_auto_install_updates_subtitle)

        textVersionValue.text = getString(
            R.string.update_version_value, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
        )
    }

    /**
     * Sets the label and optional subtitle on a toggle row view.
     *
     * @param row       The row view (from settings_toggle_row.xml)
     * @param labelRes  String resource for the main label
     * @param subtitleRes String resource for the subtitle, or 0 to hide it
     */
    private fun configureToggleRow(row: View, labelRes: Int, subtitleRes: Int) {
        row.findViewById<TextView>(R.id.text_setting_label)?.setText(labelRes)
        val subtitle = row.findViewById<TextView>(R.id.text_setting_subtitle)
        if (subtitleRes != 0) {
            subtitle?.setText(subtitleRes)
            subtitle?.visibility = View.VISIBLE
        } else {
            subtitle?.visibility = View.GONE
        }
    }

    // ─── Settings Load & Save ────────────────────────────────────────────────

    /**
     * Loads the current settings and populates the UI.
     * Then sets up click/toggle listeners for each row.
     */
    private fun loadAndPopulate() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            populateUI(settings)
            setupListeners()
        }
    }

    /** Populates all UI elements with values from [settings]. */
    private fun populateUI(settings: AppSettings) {
        // effectiveDisplayName is never empty — it falls back to MdnsNames.DEFAULT_DISPLAY_NAME.
        textDisplayNameValue.text = settings.effectiveDisplayName
        setToggle(rowAirPlay,      settings.airPlayEnabled)
        setToggle(rowMirrorAudio,  settings.mirrorAudioEnabled)
        setToggle(rowStartOnBoot,  settings.startOnBoot)
        setToggle(rowDebugOverlay, settings.showDebugOverlay)
        setToggle(rowForceHighRes, settings.forceHighResolution)
        setToggle(rowAutoCheckUpdates,    settings.autoCheckForUpdates)
        setToggle(rowAutoDownloadUpdates, settings.autoDownloadUpdates)
        setToggle(rowAutoInstallUpdates,  settings.autoInstallUpdates)
        refreshStagedUpdateRow()
    }

    /**
     * Shows the staged download (if any) on the "Check for updates" row, so an update that
     * was downloaded in the background is still installable after the app was closed and
     * reopened — otherwise it would look like nothing ever happened.
     */
    private fun refreshStagedUpdateRow() {
        if (!::rowCheckUpdates.isInitialized) return
        val staged = com.phairplay.update.UpdateManager.get(requireContext()).stagedUpdate()
        if (staged != null) {
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

    private fun setToggle(row: View, value: Boolean) {
        row.findViewById<SwitchCompat>(R.id.switch_setting)?.isChecked = value
    }

    /**
     * Sets up click and toggle listeners for all settings rows.
     * Each listener immediately persists the change via [SettingsRepository.update].
     *
     * No "Save" button is needed — settings are saved on every interaction.
     * A restart prompt is shown after protocol-affecting changes.
     */
    private fun setupListeners() {
        rowDisplayName.setOnClickListener { showDisplayNameDialog() }

        setToggleListener(rowAirPlay)      { enabled -> saveAndRestart { it.copy(airPlayEnabled = enabled) } }
        setToggleListener(rowMirrorAudio)  { enabled -> saveAndRestart { it.copy(mirrorAudioEnabled = enabled) } }
        setToggleListener(rowStartOnBoot)  { enabled -> save { it.copy(startOnBoot = enabled) } }
        setToggleListener(rowDebugOverlay) { enabled -> save { it.copy(showDebugOverlay = enabled) } }
        // The mirror size is part of the `GET /info` capability record, which is built when the
        // receiver starts — so a resolution change needs a restart to reach the sender.
        setToggleListener(rowForceHighRes) { enabled -> saveAndRestart { it.copy(forceHighResolution = enabled) } }
        setToggleListener(rowAutoCheckUpdates)    { enabled -> save { it.copy(autoCheckForUpdates = enabled) } }
        setToggleListener(rowAutoDownloadUpdates) { enabled -> save { it.copy(autoDownloadUpdates = enabled) } }
        setToggleListener(rowAutoInstallUpdates)  { enabled -> save { it.copy(autoInstallUpdates = enabled) } }

        rowCheckUpdates.setOnClickListener {
            if (updateCheckRunning) return@setOnClickListener
            val staged = com.phairplay.update.UpdateManager.get(requireContext()).stagedUpdate()
            when {
                staged != null -> showInstallDialog(staged.info)
                pendingKeyMismatchUpdate != null -> showSigningKeyMismatchDialog(pendingKeyMismatchUpdate)
                pendingAvailableUpdate != null -> showAvailableDialog(pendingAvailableUpdate!!)
                else -> runUpdateCheck()
            }
        }
        rowReset.setOnClickListener { resetSettings() }
    }

    // ─── Updates ────────────────────────────────────────────────────────────

    /**
     * "Check for updates": checks GitHub and renders the result in the update card.
     *
     * Short confirmation dialogs are reserved for actions that need a decision (download,
     * install, or a one-time signing-key migration). Verification happens before an APK is
     * staged, so Android never receives a build it would reject as a package conflict.
     */
    private fun runUpdateCheck() {
        if (updateCheckRunning) return
        updateCheckRunning = true
        rowCheckUpdates.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val settings = settingsRepository.settingsFlow.first()

                // A staged download outranks a new check: offer the install we already have.
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
                            // The release does not carry a build number at all: nothing can be
                            // compared, so nothing is offered — and the card says exactly that
                            // instead of a "you are up to date" that might not be true.
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

    /**
     * The "an update is available" dialog: Download / Skip this version / Later.
     *
     * "Skip" is deliberately a separate, explicit choice rather than the same as "Later": Later
     * means ask me again, Skip means stop mentioning *this build* (a newer one will still be
     * offered). Both are stored in [com.phairplay.update.UpdatePreferences] so the background
     * check honours them too, not just this screen.
     */
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
            .setPositiveButton(R.string.update_action_install) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val installed = com.phairplay.update.UpdateManager.get(requireContext())
                        .installStaged()
                    if (installed) {
                        showMessageDialog(R.string.update_dialog_title, getString(R.string.update_install_started))
                    } else {
                        showMessageDialog(R.string.update_install_failed_title, getString(R.string.update_install_failed))
                    }
                    refreshStagedUpdateRow()
                }
            }
            .setNegativeButton(R.string.update_action_later, null)
            .show()
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
        val repo = BuildConfig.UPDATE_REPO.trim()
        val releaseUrl = if (repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            "https://github.com/$repo/releases/latest"
        } else {
            "https://github.com/2archiver/phairplay-archiver-fork-/releases/latest"
        }
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

    private fun setToggleListener(row: View, onChanged: (Boolean) -> Unit) {
        // The whole row is clickable (better TV UX than just the Switch widget)
        row.setOnClickListener {
            val switch = row.findViewById<SwitchCompat>(R.id.switch_setting) ?: return@setOnClickListener
            val newValue = !switch.isChecked
            switch.isChecked = newValue
            onChanged(newValue)
        }
    }

    /**
     * Saves an updated [AppSettings] via the repository.
     * Runs in a coroutine so it doesn't block the UI thread.
     *
     * @param transform A function that takes the current settings and returns updated settings.
     */
    private fun save(transform: (AppSettings) -> AppSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.update(transform)
            Logger.d("Settings saved")
        }
    }

    /**
     * Saves a setting that the AirPlay receiver only reads at startup (the enable flag, mirror
     * audio), then restarts the service so the change applies immediately instead of on the next
     * manual restart.
     */
    private fun saveAndRestart(transform: (AppSettings) -> AppSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.update(transform)
            ServiceController.restart(requireContext())
            Logger.i("Settings saved — restarting receivers to apply")
        }
    }

    /**
     * Shows a dialog allowing the user to edit the AirPlay display name.
     *
     * WHY: The display name is what appears in the macOS/iOS AirPlay picker.
     * Changing it is infrequent but important for multi-TV households.
     *
     * The save **restarts the receivers**. This used to only write to DataStore, which
     * left the running mDNS registration broadcasting the previous name until the user
     * happened to hit Restart — the single most confusing thing about the setting, since
     * the UI updated immediately and the sender's picker did not.
     *
     * TV UX notes:
     * - The EditText is pre-filled with the current name
     * - Max length is enforced to [AppSettings.DISPLAY_NAME_MAX_LENGTH] (63 chars, mDNS limit)
     * - "OK" saves the new name; "Reset to default" restores
     *   [MdnsNames.DEFAULT_DISPLAY_NAME] ("Apple TV"); "Cancel" = no-op
     * - Name trimming and sanitising are applied on save (see [MdnsNames.sanitize])
     *
     * Collision detection: Android's NsdManager automatically appends " (2)", " (3)" etc. if
     * another device on the network already uses the same mDNS name. This is transparent to
     * the user at save-time; the actual registered name is logged at registration and shown
     * on the Home screen when it differs from the requested one.
     */
    private fun showDisplayNameDialog() {
        // Read directly from the displayed value (already loaded). Older builds could show the
        // "using system device name" placeholder; treat that as "no name" rather than typing it
        // into the box.
        val displayed = textDisplayNameValue.text?.toString() ?: ""
        val currentName = if (displayed == getString(R.string.setting_display_name_system_default)) "" else displayed

        val editText = EditText(requireContext()).apply {
            setText(currentName)
            hint = getString(R.string.setting_display_name_dialog_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(AppSettings.DISPLAY_NAME_MAX_LENGTH))
            setSingleLine(true)
            // Move cursor to end so user can append rather than overwrite
            setSelection(currentName.length)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_display_name)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // Sanitise here as well as on read, so the row shows the name that will
                // actually be registered rather than what was typed.
                val newName = MdnsNames.sanitize(editText.text?.toString())
                saveAndRestart { it.copy(displayName = newName) }
                textDisplayNameValue.text = newName
                Logger.i("Display name updated to '$newName' — restarting receivers to re-advertise")
            }
            .setNeutralButton(R.string.setting_display_name_reset) { _, _ ->
                saveAndRestart { it.copy(displayName = MdnsNames.DEFAULT_DISPLAY_NAME) }
                textDisplayNameValue.text = MdnsNames.DEFAULT_DISPLAY_NAME
                Logger.i("Display name reset to '${MdnsNames.DEFAULT_DISPLAY_NAME}'")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Resets all settings to defaults and repopulates the UI.
     * TODO: Add a confirmation dialog before resetting.
     */
    private fun resetSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.resetToDefaults()
            val defaults = AppSettings.DEFAULT
            populateUI(defaults)
            Logger.i("Settings reset to defaults")
        }
    }
}
