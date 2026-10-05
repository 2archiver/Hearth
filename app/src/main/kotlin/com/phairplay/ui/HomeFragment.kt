package com.phairplay.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.phairplay.R
import com.phairplay.settings.SettingsRepository
import com.phairplay.service.ActiveConnection
import com.phairplay.service.PhairPlayService
import com.phairplay.service.Protocol
import com.phairplay.service.ProtocolState
import com.phairplay.service.ServiceController
import com.phairplay.service.ServiceState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import com.phairplay.update.UpdateManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * HomeFragment presents receiver state, connection instructions and service actions.
 * It owns one collection job per binding so returning to Home cannot duplicate observers.
 * Hosted by MainActivity; all dynamic status comes from PhairPlayService.
 */
class HomeFragment : Fragment() {
    private var service: PhairPlayService? = null
    private var isBound = false
    private var observationJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? PhairPlayService.LocalBinder)?.getService()
            isBound = true
            Logger.d("HomeFragment: bound to PhairPlayService")
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            observationJob?.cancel()
            service = null
            Logger.d("HomeFragment: unbound from PhairPlayService")
        }
    }
    private lateinit var textDeviceName: TextView
    private lateinit var textNetwork: TextView
    private lateinit var textUpdate: TextView
    private lateinit var textServiceState: TextView
    private lateinit var dotServiceState: View
    private lateinit var cardAirPlay: View
    private lateinit var cardAppleCasting: View
    private lateinit var textConnectionLog: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnRestart: Button
    private var airPlayState = ProtocolState.DISABLED
    private var appleCastingState = ProtocolState.DISABLED
    private var activeConnection: ActiveConnection? = null
    private var airPlayPlaybackState: com.phairplay.airplay.AirPlayPlaybackState? = null
    /** The name mDNS actually registered (differs from the requested one on a collision). */
    private var registeredName: String? = null
    /** The name the user asked for; kept so a late mDNS registration can be compared to it. */
    private var lastRequestedName: String = com.phairplay.util.MdnsNames.DEFAULT_DISPLAY_NAME
    /** Receiver-supplied network and failure details for each card. */
    private var airPlayDetail: String? = null
    private var appleCastingDetail: String? = null
    /** Recent connection steps, ordered oldest to newest. */
    private var connectionLog: List<com.phairplay.airplay.AirPlayTrace.Entry> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindViews(view)
        configureProtocolCards()
        configureButtons()
        showDeviceName()
        showNetwork()
        showUpdateBadge()
        view.findViewById<View>(R.id.btn_connection_log).setOnClickListener { showConnectionLogDialog() }
        textUpdate.setOnClickListener {
            requireActivity().findViewById<View>(R.id.nav_item_settings).performClick()
        }
    }

    override fun onStart() {
        super.onStart()
        showNetwork()
        showUpdateBadge()
        val intent = Intent(requireContext(), PhairPlayService::class.java)
        isBound = requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        observationJob?.cancel()
        observationJob = null
        service = null
        super.onStop()
        if (isBound) {
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun bindViews(view: View) {
        textDeviceName   = view.findViewById(R.id.text_device_name)
        textNetwork      = view.findViewById(R.id.text_network)
        textUpdate       = view.findViewById(R.id.text_update)
        textServiceState = view.findViewById(R.id.text_service_state)
        dotServiceState  = view.findViewById(R.id.dot_service_state)
        cardAirPlay      = view.findViewById(R.id.card_airplay)
        cardAppleCasting = view.findViewById(R.id.card_apple_casting)
        textConnectionLog = view.findViewById(R.id.text_connection_log)
        btnStart         = view.findViewById(R.id.btn_start)
        btnStop          = view.findViewById(R.id.btn_stop)
        btnRestart       = view.findViewById(R.id.btn_restart)
    }

    /** Sets the static content on each protocol card: icon and protocol name. */
    private fun configureProtocolCards() {
        setupCard(cardAirPlay,      R.drawable.ic_airplay,       R.string.protocol_airplay)
        setupCard(cardAppleCasting, R.drawable.ic_apple_casting, R.string.protocol_apple_casting)
        cardAirPlay.setOnClickListener { showConnectionLogDialog() }
        cardAppleCasting.setOnClickListener { showConnectionLogDialog() }
    }

    /** The detail line for the Apple Casting card. */
    private fun castingDetailFor(state: ProtocolState): String? = when {
        appleCastingDetail != null -> appleCastingDetail
        // The receiver name is printed on the hero card above, so this line takes no argument.
        state == ProtocolState.ADVERTISING -> getString(R.string.protocol_detail_casting_waiting)
        else -> null
    }

    private fun airPlayPlaybackDetail(): String? = when (airPlayPlaybackState) {
        com.phairplay.airplay.AirPlayPlaybackState.FAILED -> getString(R.string.protocol_detail_video_failed)
        com.phairplay.airplay.AirPlayPlaybackState.LOADING -> getString(R.string.protocol_detail_video_loading)
        com.phairplay.airplay.AirPlayPlaybackState.PLAYING -> getString(R.string.protocol_detail_video_playing)
        com.phairplay.airplay.AirPlayPlaybackState.PAUSED -> getString(R.string.protocol_detail_video_paused)
        com.phairplay.airplay.AirPlayPlaybackState.AUDIO_ONLY ->
            getString(R.string.protocol_detail_video_audio_only)
        else -> null
    }

    private fun airPlayConnectedFormat(): Int = when (airPlayPlaybackState) {
        com.phairplay.airplay.AirPlayPlaybackState.LOADING -> R.string.protocol_detail_video_loading
        com.phairplay.airplay.AirPlayPlaybackState.PLAYING -> R.string.protocol_detail_video_playing
        com.phairplay.airplay.AirPlayPlaybackState.PAUSED -> R.string.protocol_detail_video_paused
        com.phairplay.airplay.AirPlayPlaybackState.FAILED -> R.string.protocol_detail_video_failed
        com.phairplay.airplay.AirPlayPlaybackState.AUDIO_ONLY -> R.string.protocol_detail_video_audio_only
        else -> R.string.protocol_detail_connected
    }

    /** Full connection history, newest last — what a Settings screen would show if a TV had room. */
    private fun showConnectionLogDialog() {
        if (!isAdded) return
        val text = connectionLog.joinToString("\n") { entry ->
            "%s  %s".format(entry.timeLabel(), entry.message)
        }.ifBlank { getString(R.string.home_log_empty) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.home_log_dialog_title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** Shows the latest connection step; Activity opens the complete history. */
    private fun renderConnectionLog() {
        if (!::textConnectionLog.isInitialized) return
        textConnectionLog.text = if (connectionLog.isEmpty()) {
            getString(R.string.home_log_empty)
        } else {
            connectionLog.takeLast(LOG_LINES_ON_SCREEN).joinToString("\n") { entry ->
                "%s  %s".format(entry.timeLabel(), entry.message)
            }
        }
    }

    private fun setupCard(card: View, iconRes: Int, nameRes: Int) {
        card.findViewById<android.widget.ImageView>(R.id.img_protocol_icon)?.setImageResource(iconRes)
        card.findViewById<TextView>(R.id.text_protocol_name)?.setText(nameRes)
    }

    /** Configures Start / Stop / Restart button click listeners. */
    private fun configureButtons() {
        btnStart.setOnClickListener {
            Logger.d("User tapped Start")
            ServiceController.start(requireContext())
        }
        btnStop.setOnClickListener {
            Logger.d("User tapped Stop")
            ServiceController.stop(requireContext())
        }
        btnRestart.setOnClickListener {
            Logger.d("User tapped Restart")
            ServiceController.restart(requireContext())
        }
    }

    /** Loads the configured name shown in the sender’s device picker. */
    private fun showDeviceName() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = SettingsRepository(requireContext()).settingsFlow.first()
            lastRequestedName = settings.effectiveDisplayName
            updateDeviceNameText(lastRequestedName)
        }
    }

    /** Prefers the registered mDNS name, including any collision suffix. */
    private fun updateDeviceNameText(requestedName: String) {
        val actual = registeredName
        val nameToShow = if (actual.isNullOrBlank()) requestedName else actual
        if (!actual.isNullOrBlank() && actual != requestedName) {
            Logger.w("mDNS name collision — requested '$requestedName', registered as '$actual'")
        }
        textDeviceName.text = getString(R.string.home_device_visible_as, nameToShow)
    }

    /** Shows which network Hearth is advertising on. */
    private fun showNetwork() {
        if (!::textNetwork.isInitialized) return
        val summary = NetworkUtils.getNetworkSummary(requireContext())
        textNetwork.text = if (summary.ipAddress.isNullOrBlank()) {
            getString(R.string.home_network_unknown)
        } else {
            getString(R.string.home_network, summary.label())
        }
    }

    /** Makes a staged update visible and actionable from Home. */
    private fun showUpdateBadge() {
        if (!::textUpdate.isInitialized) return
        val staged = UpdateManager.get(requireContext()).stagedUpdate()
        if (staged == null) {
            textUpdate.visibility = View.GONE
            return
        }
        textUpdate.text = getString(R.string.home_update_available, staged.info.shortLabel())
        textUpdate.visibility = View.VISIBLE
    }

    /** Starts collecting state updates from [PhairPlayService]. */
    private fun observeServiceState() {
        observationJob?.cancel()
        val svc = service ?: return
        observationJob = viewLifecycleOwner.lifecycleScope.launch {

            launch {
                svc.serviceState.collectLatest { state -> updateServiceStateBadge(state) }
            }
            launch {
                svc.registeredName.collectLatest { name ->
                    registeredName = name
                    updateDeviceNameText(lastRequestedName)
                }
            }
            launch {
                svc.airPlayState.collectLatest { state ->
                    airPlayState = state
                    updateProtocolCard(
                        cardAirPlay, state,
                        R.string.protocol_detail_error_airplay, Protocol.AIRPLAY,
                        detailOverride = airPlayPlaybackDetail() ?: airPlayDetail,
                        connectedFormat = airPlayConnectedFormat(),
                    )
                }
            }
            launch {
                svc.airPlayDetail.collectLatest { detail ->
                    airPlayDetail = detail
                    updateProtocolCard(
                        cardAirPlay, airPlayState,
                        R.string.protocol_detail_error_airplay, Protocol.AIRPLAY,
                        detailOverride = airPlayPlaybackDetail() ?: detail,
                        connectedFormat = airPlayConnectedFormat(),
                    )
                }
            }
            launch {
                svc.airPlayPlaybackState.collectLatest { state ->
                    airPlayPlaybackState = state
                    refreshProtocolCardDetails()
                }
            }
            launch {
                svc.appleCastingState.collectLatest { state ->
                    appleCastingState = state
                    updateProtocolCard(
                        cardAppleCasting, state,
                        R.string.protocol_detail_error_casting, Protocol.AIRPLAY,
                        detailOverride = castingDetailFor(state),
                        connectedFormat = R.string.protocol_detail_mirroring
                    )
                }
            }
            launch {
                svc.appleCastingDetail.collectLatest { detail ->
                    appleCastingDetail = detail
                    updateProtocolCard(
                        cardAppleCasting, appleCastingState,
                        R.string.protocol_detail_error_casting, Protocol.AIRPLAY,
                        detailOverride = castingDetailFor(appleCastingState),
                        connectedFormat = R.string.protocol_detail_mirroring
                    )
                }
            }
            launch {
                svc.airPlayTrace.collectLatest { entries ->
                    connectionLog = entries
                    renderConnectionLog()
                }
            }
            launch {
                svc.activeConnection.collectLatest { connection ->
                    activeConnection = connection
                    refreshProtocolCardDetails()
                }
            }
        }
    }
    /** Re-renders the detail line of both cards (used when the sender name changes). */
    private fun refreshProtocolCardDetails() {
        updateProtocolCard(
            cardAirPlay, airPlayState,
            R.string.protocol_detail_error_airplay, Protocol.AIRPLAY,
            detailOverride = airPlayPlaybackDetail() ?: airPlayDetail,
            connectedFormat = airPlayConnectedFormat(),
        )
        updateProtocolCard(
            cardAppleCasting, appleCastingState,
            R.string.protocol_detail_error_casting, Protocol.AIRPLAY,
            detailOverride = castingDetailFor(appleCastingState),
            connectedFormat = R.string.protocol_detail_mirroring
        )
    }
    /** Updates the global service state badge (top-right corner of HomeScreen). */
    private fun updateServiceStateBadge(state: ServiceState) {
        val (textRes, colorRes) = when (state) {
            is ServiceState.Running    -> Pair(R.string.service_state_running,    R.color.status_running)
            is ServiceState.Stopped    -> Pair(R.string.service_state_stopped,    R.color.status_stopped)
            is ServiceState.Restarting -> Pair(R.string.service_state_restarting, R.color.status_transitioning)
            is ServiceState.Error      -> Pair(R.string.service_state_error,      R.color.status_stopped)
        }
        val focusedControl = listOf(btnStart, btnStop, btnRestart).firstOrNull { it.hasFocus() }
        btnStart.isEnabled = state is ServiceState.Stopped || state is ServiceState.Error
        btnStop.isEnabled = state is ServiceState.Running
        btnRestart.isEnabled = state is ServiceState.Running || state is ServiceState.Error
        listOf(btnStart, btnStop, btnRestart).forEach { it.alpha = if (it.isEnabled) 1f else 0.4f }
        if (focusedControl != null && !focusedControl.isEnabled) cardAirPlay.requestFocus()
        textServiceState.setText(textRes)
        dotServiceState.background.mutate().setTint(requireContext().getColor(colorRes))
    }
    /** Updates a single protocol status card with the current [ProtocolState]. */
    private fun updateProtocolCard(
        card: View,
        state: ProtocolState,
        errorDetailRes: Int,
        protocol: Protocol,
        /** Replaces the ADVERTISING/ERROR detail when the service has a better explanation. */
        detailOverride: String? = null,
        connectedFormat: Int = R.string.protocol_detail_connected
    ) {
        val dot    = card.findViewById<View>(R.id.dot_protocol_status)
        val stateText = card.findViewById<TextView>(R.id.text_protocol_state)
        val detail = card.findViewById<TextView>(R.id.text_protocol_detail)

        val (stateRes, colorRes) = when (state) {
            ProtocolState.DISABLED    -> Pair(R.string.protocol_state_disabled,    R.color.status_disabled)
            ProtocolState.ADVERTISING -> Pair(R.string.protocol_state_advertising, R.color.status_running)
            ProtocolState.CONNECTED   -> Pair(R.string.protocol_state_connected,   R.color.status_running)
            ProtocolState.UNAVAILABLE -> Pair(R.string.protocol_state_unavailable, R.color.status_disabled)
            ProtocolState.ERROR       -> Pair(R.string.protocol_state_error,       R.color.status_stopped)
        }

        stateText.setText(stateRes)
        detail.text = detailText(state, protocol, errorDetailRes, detailOverride, connectedFormat)
        dot.background.mutate().setTint(requireContext().getColor(colorRes))
    }
    /** Shows the actual sender or failure reason for a receiver card. */
    private fun detailText(
        state: ProtocolState,
        protocol: Protocol,
        errorDetailRes: Int,
        detailOverride: String? = null,
        connectedFormat: Int = R.string.protocol_detail_connected
    ): String =
        when {
            state == ProtocolState.CONNECTED -> {
                val connection = activeConnection
                if (connection != null && connection.protocol == protocol) {
                    getString(connectedFormat, connection.senderName)
                } else if (!detailOverride.isNullOrBlank()) {
                    detailOverride
                } else {
                    getString(R.string.protocol_detail_connected_fallback)
                }
            }
            state == ProtocolState.ERROR       -> detailOverride ?: getString(errorDetailRes)
            state == ProtocolState.UNAVAILABLE -> detailOverride
                ?: getString(R.string.protocol_detail_unavailable)
            state == ProtocolState.ADVERTISING -> detailOverride ?: getString(R.string.protocol_detail_waiting)
            else -> if (detailOverride.isNullOrBlank()) getString(R.string.protocol_detail_disabled)
                    else getString(R.string.protocol_detail_disabled) + " · " + detailOverride
        }

    private companion object {
        /** How many connection-log lines fit on Home without pushing the buttons off the screen. */
        const val LOG_LINES_ON_SCREEN = 1
    }
}
