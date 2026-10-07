package com.phairplay.ui

import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
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
import com.phairplay.update.UpdateWorkScheduler
import com.phairplay.util.Logger
import com.phairplay.util.MdnsNames
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SettingsFragment edits receiver preferences and applies changes immediately.
 * Update downloads live in SettingsUpdates to keep view and preference responsibilities small.
 * MainActivity hosts this fragment from its Settings navigation item.
 */
class SettingsFragment : Fragment() {

    private lateinit var settingsRepository: SettingsRepository
    private var updates: SettingsUpdates? = null
    private lateinit var headerDisplay: TextView
    private lateinit var headerProtocols: TextView
    private lateinit var headerAirPlay: TextView
    private lateinit var headerService: TextView
    private lateinit var headerDeveloper: TextView
    private lateinit var headerUpdates: TextView
    private lateinit var headerAbout: TextView
    private lateinit var rowDisplayName: LinearLayout
    private lateinit var textDisplayNameValue: TextView
    private lateinit var rowAirPlay: View
    private lateinit var rowMirrorAudio: View
    private lateinit var rowStartOnBoot: View
    private lateinit var rowDebugOverlay: View
    private lateinit var rowForceHighRes: View
    private lateinit var textVersionValue: TextView
    private lateinit var rowAutoCheckUpdates: View
    private lateinit var rowAutoDownloadUpdates: View
    private lateinit var rowAutoInstallUpdates: View
    private lateinit var rowReset: LinearLayout

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settingsRepository = SettingsRepository(requireContext())
        bindViews(view)
        updates = SettingsUpdates(this, view, settingsRepository)
        setSectionTitles()
        setRowLabels()
        loadAndPopulate()
        view.getFocusables(View.FOCUS_FORWARD).forEach { it.nextFocusLeftId = R.id.nav_item_settings }
    }

    override fun onDestroyView() {
        updates = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        updates?.refresh()
    }

    private fun bindViews(view: View) {
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
        rowAutoCheckUpdates  = view.findViewById(R.id.row_auto_check_updates)
        rowAutoDownloadUpdates = view.findViewById(R.id.row_auto_download_updates)
        rowAutoInstallUpdates = view.findViewById(R.id.row_auto_install_updates)
        rowReset            = view.findViewById(R.id.row_reset)
    }

    private fun setSectionTitles() {
        headerDisplay.setText(R.string.settings_section_display)
        headerProtocols.setText(R.string.settings_section_protocols)
        headerAirPlay.setText(R.string.settings_section_airplay)
        headerService.setText(R.string.settings_section_service)
        headerDeveloper.setText(R.string.settings_section_developer)
        headerUpdates.setText(R.string.settings_section_updates)
        headerAbout.setText(R.string.settings_section_about)
    }

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

    private fun loadAndPopulate() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            populateUI(settings)
            setupListeners()
        }
    }

    private fun populateUI(settings: AppSettings) {
        textDisplayNameValue.text = settings.effectiveDisplayName
        setToggle(rowAirPlay,      settings.airPlayEnabled)
        setToggle(rowMirrorAudio,  settings.mirrorAudioEnabled)
        setToggle(rowStartOnBoot,  settings.startOnBoot)
        setToggle(rowDebugOverlay, settings.showDebugOverlay)
        setToggle(rowForceHighRes, settings.forceHighResolution)
        setToggle(rowAutoCheckUpdates,    settings.autoCheckForUpdates)
        setToggle(rowAutoDownloadUpdates, settings.autoDownloadUpdates)
        setToggle(rowAutoInstallUpdates,  settings.autoInstallUpdates)
        updates?.refresh()
    }

    private fun setToggle(row: View, value: Boolean) {
        row.findViewById<SwitchCompat>(R.id.switch_setting)?.isChecked = value
        androidx.core.view.ViewCompat.setStateDescription(row, getString(
            if (value) R.string.setting_state_on else R.string.setting_state_off
        ))
    }

    private fun setupListeners() {
        rowDisplayName.setOnClickListener { showDisplayNameDialog() }

        setToggleListener(rowAirPlay)      { enabled -> saveAndRestart { it.copy(airPlayEnabled = enabled) } }
        setToggleListener(rowMirrorAudio)  { enabled -> saveAndRestart { it.copy(mirrorAudioEnabled = enabled) } }
        setToggleListener(rowStartOnBoot)  { enabled -> save { it.copy(startOnBoot = enabled) } }
        setToggleListener(rowDebugOverlay) { enabled -> save { it.copy(showDebugOverlay = enabled) } }
        setToggleListener(rowForceHighRes) { enabled -> saveAndRestart { it.copy(forceHighResolution = enabled) } }
        setToggleListener(rowAutoCheckUpdates)    { enabled -> save { it.copy(autoCheckForUpdates = enabled) } }
        setToggleListener(rowAutoDownloadUpdates) { enabled -> save { it.copy(autoDownloadUpdates = enabled) } }
        setToggleListener(rowAutoInstallUpdates)  { enabled -> save { it.copy(autoInstallUpdates = enabled) } }

        rowReset.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_reset_title)
                .setMessage(R.string.settings_reset_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.setting_reset_defaults) { _, _ -> resetSettings() }
                .show()
        }
    }

    private fun setToggleListener(row: View, onChanged: (Boolean) -> Unit) {
        row.setOnClickListener {
            val switch = row.findViewById<SwitchCompat>(R.id.switch_setting) ?: return@setOnClickListener
            val newValue = !switch.isChecked
            setToggle(row, newValue)
            onChanged(newValue)
        }
    }

    private fun save(transform: (AppSettings) -> AppSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.update(transform)
            val saved = settingsRepository.settingsFlow.first()
            UpdateWorkScheduler.sync(requireContext(), saved.autoCheckForUpdates)
            Logger.d("Settings saved")
        }
    }

    private fun saveAndRestart(transform: (AppSettings) -> AppSettings) {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.update(transform)
            val saved = settingsRepository.settingsFlow.first()
            UpdateWorkScheduler.sync(requireContext(), saved.autoCheckForUpdates)
            ServiceController.restart(requireContext())
            Logger.i("Settings saved — restarting receivers to apply")
        }
    }

    private fun showDisplayNameDialog() {
        val displayed = textDisplayNameValue.text?.toString() ?: ""
        val currentName = if (displayed == getString(R.string.setting_display_name_system_default)) "" else displayed

        val editText = EditText(requireContext()).apply {
            setText(currentName)
            hint = getString(R.string.setting_display_name_dialog_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(AppSettings.DISPLAY_NAME_MAX_LENGTH))
            setSingleLine(true)
            setSelection(currentName.length)
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.setting_display_name)
            .setView(editText)
            .setPositiveButton(android.R.string.ok) { _, _ ->
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

    private fun resetSettings() {
        viewLifecycleOwner.lifecycleScope.launch {
            settingsRepository.resetToDefaults()
            UpdateWorkScheduler.sync(requireContext(), AppSettings.DEFAULT.autoCheckForUpdates)
            val defaults = AppSettings.DEFAULT
            populateUI(defaults)
            ServiceController.restart(requireContext())
            Logger.i("Settings reset to defaults")
        }
    }
}
