package com.phairplay.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * HomeFragment — The main screen of Hearth.
 *
 * WHY: Shows the two things this TV can receive — **AirPlay** (music, video and photos from an
 * iPhone/iPad/Mac) and **Apple Casting** (screen mirroring from those same devices) — plus a live
 * connection log, and provides Start / Stop / Restart controls. Designed for TV: large cards,
 * D-pad navigable, Google TV Streamer design language.
 *
 * HOW: Binds to [PhairPlayService] to receive real-time state updates.
 * User interactions call [ServiceController] to send commands to the service.
 *
 * Navigation: accessed via the "Home" item in MainActivity's nav panel.
 */
class HomeFragment : Fragment() {

    // Service binding — gives direct access to PhairPlayService StateFlows
    private var service: PhairPlayService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? PhairPlayService.LocalBinder)?.getService()
            isBound = true
            Logger.d("HomeFragment: bound to PhairPlayService")
            observeServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            isBound = false
            Logger.d("HomeFragment: unbound from PhairPlayService")
        }
    }

    // View references — bound in onViewCreated
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

    // Latest protocol states + active connection — needed to render each card's
    // detail line (per-protocol error text, real sender name while streaming).
    private var airPlayState = ProtocolState.DISABLED
    private var appleCastingState = ProtocolState.DISABLED
    private var activeConnection: ActiveConnection? = null

    /** The name mDNS actually registered (differs from the requested one on a collision). */
    private var registeredName: String? = null

    /** The name the user asked for; kept so a late mDNS registration can be compared to it. */
    private var lastRequestedName: String = com.phairplay.util.MdnsNames.DEFAULT_DISPLAY_NAME

    /**
     * Honest explanations of each card's state, published by [PhairPlayService] — which address
     * the TV is advertising on, which record the mDNS responder refused, and — on the mirroring
     * card — what the sender is doing.
     */
    private var airPlayDetail: String? = null
    private var appleCastingDetail: String? = null

    /**
     * Every step of the most recent sender connection, newest last — the answer to "it shows up
     * in the list but nothing happens". [PhairPlayService.airPlayTrace] fills it.
     */
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
    }

    override fun onStart() {
        super.onStart()
        // The IP/interface can change while the app is in the background (Wi-Fi ↔ Ethernet),
        // so re-read it every time the Home screen is shown.
        showNetwork()
        showUpdateBadge()
        // Bind to the service so we can observe its StateFlows
        val intent = Intent(requireContext(), PhairPlayService::class.java)
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        if (isBound) {
            requireContext().unbindService(serviceConnection)
            isBound = false
        }
    }

    // ─── View Setup ──────────────────────────────────────────────────────────

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

    /**
     * Sets the static content on each protocol card: icon and protocol name.
     * The dynamic parts (state, detail text) are updated when service state changes.
     */
    private fun configureProtocolCards() {
        setupCard(cardAirPlay,      R.drawable.ic_airplay,       R.string.protocol_airplay)
        setupCard(cardAppleCasting, R.drawable.ic_apple_casting, R.string.protocol_apple_casting)
        // Either card opens the full connection log — the one place a "why did it not connect?"
        // question can actually be answered on the TV itself.
        cardAirPlay.setOnClickListener { showConnectionLogDialog() }
        cardAppleCasting.setOnClickListener { showConnectionLogDialog() }
    }

    /**
     * The detail line for the Apple Casting card.
     *
     * While idle this must be the *instruction* (Control Centre → Screen Mirroring → the name),
     * because that is the only place on the TV a user can read it. The service supplies a line
     * while mirroring; anything else falls back to null so the generic wording is used.
     */
    private fun castingDetailFor(state: ProtocolState): String? = when {
        appleCastingDetail != null -> appleCastingDetail
        state == ProtocolState.ADVERTISING ->
            getString(
                R.string.protocol_detail_casting_waiting,
                registeredName?.takeIf { it.isNotBlank() } ?: lastRequestedName
            )
        else -> null
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

    /**
     * Renders the on-screen connection log: the newest few steps, or one line explaining that
     * nothing has connected yet.
     */
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

    /**
     * Configures Start / Stop / Restart button click listeners.
     * Calls [ServiceController] which sends Intent actions to [PhairPlayService].
     */
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

    /**
     * Shows the AirPlay name on the Home screen so the user knows what to look for in
     * their sender's picker.
     *
     * This reads the **spoofed** name from Settings, not the Android device name — the
     * whole point of the setting is that the sender sees the name chosen here, and showing
     * the system name made a working rename look broken.
     */
    private fun showDeviceName() {
        viewLifecycleOwner.lifecycleScope.launch {
            val settings = SettingsRepository(requireContext()).settingsFlow.first()
            // effectiveDisplayName is never blank — see MdnsNames.DEFAULT_DISPLAY_NAME.
            lastRequestedName = settings.effectiveDisplayName
            updateDeviceNameText(lastRequestedName)
        }
    }

    /**
     * Renders the "Visible as: …" line.
     *
     * Once mDNS has reported the name it actually registered, that wins over the requested
     * one: NsdManager resolves a collision with another device of the same name by appending
     * " (2)", and the picker shows the registered name, so that is what the user needs to
     * see here too.
     */
    private fun updateDeviceNameText(requestedName: String) {
        val actual = registeredName
        val nameToShow = if (actual.isNullOrBlank()) requestedName else actual
        if (!actual.isNullOrBlank() && actual != requestedName) {
            Logger.w("mDNS name collision — requested '$requestedName', registered as '$actual'")
        }
        textDeviceName.text = getString(R.string.home_device_visible_as, nameToShow)
    }

    /**
     * Shows which network Hearth is advertising on.
     *
     * WHY: "my iPhone cannot see the TV" is nearly always a network question. On a wired
     * Google TV the phone has to be on the same network as the *Ethernet* address shown
     * here — if the TV is also joined to a Wi-Fi network, that Wi-Fi address is irrelevant
     * to discovery, and without this line there is no way to tell them apart from the TV.
     */
    private fun showNetwork() {
        if (!::textNetwork.isInitialized) return
        val summary = NetworkUtils.getNetworkSummary(requireContext())
        textNetwork.text = if (summary.ipAddress.isNullOrBlank()) {
            getString(R.string.home_network_unknown)
        } else {
            getString(R.string.home_network, summary.label())
        }
    }

    /**
     * Shows a one-line hint when a newer Hearth build is already downloaded and waiting,
     * or has been published but not downloaded. Tapping is not needed — Settings →
     * "Check for updates" drives it — but silently sitting on an update is worse than a line
     * of text on the Home screen.
     */
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

    // ─── State Observation ───────────────────────────────────────────────────

    /**
     * Starts collecting state updates from [PhairPlayService].
     * Called after the service is bound. Each StateFlow is collected independently
     * so that a change in one protocol card doesn't trigger a full UI redraw.
     */
    private fun observeServiceState() {
        val svc = service ?: return

        viewLifecycleOwner.lifecycleScope.launch {
            svc.serviceState.collectLatest { state -> updateServiceStateBadge(state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // The name mDNS really registered — used to show " (2)"-style collision renames
            // instead of the name the user asked for.
            svc.registeredName.collectLatest { name ->
                registeredName = name
                updateDeviceNameText(lastRequestedName)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.airPlayState.collectLatest { state ->
                airPlayState = state
                updateProtocolCard(
                    cardAirPlay, state,
                    R.string.protocol_detail_error_airplay, Protocol.AIRPLAY,
                    detailOverride = airPlayDetail
                )
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            // "Advertising on Ethernet · 192.168.1.42" / "the mDNS record was refused" — the
            // difference between a working wired TV and an bug report that says "it doesn't work".
            svc.airPlayDetail.collectLatest { detail ->
                airPlayDetail = detail
                updateProtocolCard(
                    cardAirPlay, airPlayState,
                    R.string.protocol_detail_error_airplay, Protocol.AIRPLAY,
                    detailOverride = detail
                )
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
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
        viewLifecycleOwner.lifecycleScope.launch {
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
        viewLifecycleOwner.lifecycleScope.launch {
            svc.airPlayTrace.collectLatest { entries ->
                connectionLog = entries
                renderConnectionLog()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            svc.activeConnection.collectLatest { connection ->
                activeConnection = connection
                refreshProtocolCardDetails()
            }
        }
    }

    /** Re-renders the detail line of both cards (used when the sender name changes). */
    private fun refreshProtocolCardDetails() {
        updateProtocolCard(
            cardAirPlay, airPlayState,
            R.string.protocol_detail_error_airplay, Protocol.AIRPLAY,
            detailOverride = airPlayDetail
        )
        updateProtocolCard(
            cardAppleCasting, appleCastingState,
            R.string.protocol_detail_error_casting, Protocol.AIRPLAY,
            detailOverride = castingDetailFor(appleCastingState),
            connectedFormat = R.string.protocol_detail_mirroring
        )
    }

    /**
     * Updates the global service state badge (top-right corner of HomeScreen).
     * Colors and text reflect whether the service is running, stopped, or restarting.
     */
    private fun updateServiceStateBadge(state: ServiceState) {
        val (textRes, colorRes) = when (state) {
            is ServiceState.Running    -> Pair(R.string.service_state_running,    R.color.status_running)
            is ServiceState.Stopped    -> Pair(R.string.service_state_stopped,    R.color.status_stopped)
            is ServiceState.Restarting -> Pair(R.string.service_state_restarting, R.color.status_transitioning)
            is ServiceState.Error      -> Pair(R.string.service_state_error,      R.color.status_stopped)
        }
        textServiceState.setText(textRes)
        dotServiceState.background.setTint(requireContext().getColor(colorRes))
    }

    /**
     * Updates a single protocol status card with the current [ProtocolState].
     *
     * @param card           The card root view (cardAirPlay or cardAppleCasting — exactly two).
     * @param state          The current state of this protocol.
     * @param errorDetailRes Honest, protocol-specific detail shown in the ERROR state
     *                       (never a generic "Check Wi-Fi settings" guess).
     * @param protocol       Which protocol this card represents — selects the sender
     *                       name from the active connection while streaming.
     * @param connectedFormat String used while CONNECTED (defaults to "Streaming from %1$s";
     *                       the Apple Casting card says "Mirroring from %1$s" instead).
     */
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
            // Grey, not red: nothing is broken, this TV simply cannot do it.
            ProtocolState.UNAVAILABLE -> Pair(R.string.protocol_state_unavailable, R.color.status_disabled)
            ProtocolState.ERROR       -> Pair(R.string.protocol_state_error,       R.color.status_stopped)
        }

        stateText.setText(stateRes)
        detail.text = detailText(state, protocol, errorDetailRes, detailOverride, connectedFormat)
        dot.background.setTint(requireContext().getColor(colorRes))
    }

    /**
     * Resolves the detail line for one card. While streaming, the connected card
     * shows the actual sender name (the previous implementation rendered the raw
     * "%1$s" format placeholder); ERROR cards show the real reason for that protocol.
     */
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
                    // The mirror card knows it is mirroring before the service has swapped in a
                    // named ActiveConnection — say what it knows instead of a bare "Streaming".
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
        const val LOG_LINES_ON_SCREEN = 4
    }
}
