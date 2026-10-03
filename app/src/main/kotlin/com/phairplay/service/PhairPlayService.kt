package com.phairplay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.phairplay.MainActivity
import com.phairplay.R
import android.view.Surface
import com.phairplay.airplay.AirPlayReceiver
import com.phairplay.cast.CastReceiver
import com.phairplay.cast.bridge.CastBridgeReceiver
import com.phairplay.cast.bridge.CastMediaPlayer
import com.phairplay.update.StageResult
import com.phairplay.update.StagedUpdate
import com.phairplay.update.UpdateCheck
import com.phairplay.update.UpdateInfo
import com.phairplay.update.UpdateManager
import com.phairplay.settings.AppSettings
import com.phairplay.miracast.MiracastReceiver
import com.phairplay.settings.SettingsRepository
import com.phairplay.util.DisplayCaps
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * PhairPlayService — Android ForegroundService that hosts all receiver protocols.
 *
 * WHY: The AirPlay/Miracast/Cast receivers need to run continuously in the background.
 * Android may kill background processes. A ForegroundService with a persistent
 * notification keeps the app alive and shows the user that PhairPlay is active.
 *
 * HOW: Bind to this service from [MainActivity] to receive state updates.
 * Use [ServiceController] to send start/stop/restart commands.
 *
 * Service lifecycle:
 *   startForegroundService() → onCreate() → onStartCommand() → [running in background]
 *   stopSelf() / stopService() → onDestroy() → all receivers stopped
 *
 * Commands via Intent actions (sent by [ServiceController]):
 *   ACTION_START   — starts all enabled receivers
 *   ACTION_STOP    — stops all receivers and stops the service
 *   ACTION_RESTART — stops then starts all receivers (service keeps running)
 */
class PhairPlayService : Service() {

    // Binder for Activity binding (returns this service directly)
    private val binder = LocalBinder()

    // Coroutine scope — cancelled in onDestroy() to clean up all coroutines
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    // Observable state — Activities and Fragments observe this via the binder
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _airPlayState = MutableStateFlow(ProtocolState.DISABLED)
    val airPlayState: StateFlow<ProtocolState> = _airPlayState.asStateFlow()

    private val _miracastState = MutableStateFlow(ProtocolState.DISABLED)
    val miracastState: StateFlow<ProtocolState> = _miracastState.asStateFlow()

    private val _castState = MutableStateFlow(ProtocolState.DISABLED)
    val castState: StateFlow<ProtocolState> = _castState.asStateFlow()

    private val _activeConnection = MutableStateFlow<ActiveConnection?>(null)
    val activeConnection: StateFlow<ActiveConnection?> = _activeConnection.asStateFlow()

    private val _photoFrame = MutableStateFlow<PhotoFrame?>(null)
    val photoFrame: StateFlow<PhotoFrame?> = _photoFrame.asStateFlow()

    // Non-null while AirPlay audio is playing WITHOUT video — drives the now-playing overlay.
    private val _nowPlaying = MutableStateFlow<com.phairplay.airplay.NowPlayingInfo?>(null)
    val nowPlaying: StateFlow<com.phairplay.airplay.NowPlayingInfo?> = _nowPlaying.asStateFlow()

    // Non-null while a PIN should be shown on screen for SRP pair-setup (PIN access control).
    private val _pairingPin = MutableStateFlow<String?>(null)
    val pairingPin: StateFlow<String?> = _pairingPin.asStateFlow()

    /**
     * The name mDNS actually registered for `_airplay._tcp`, or null while nothing is
     * registered. NsdManager renames us to "… (2)" when another device on the LAN already
     * uses the name, and the picker shows that renamed value — surfacing it here lets the
     * Home screen show the name a sender will really see.
     */
    private val _registeredName = MutableStateFlow<String?>(null)
    val registeredName: StateFlow<String?> = _registeredName.asStateFlow()

    // Surface provider — supplied by MainActivity after binding (Sprint 5).
    // The lambda captures this field so it always uses the latest provider even if
    // setVideoSurfaceProvider() is called after startAirPlay().
    @Volatile private var videoSurfaceProvider: (() -> Surface?)? = null

    // Receiver instances — null when not running
    private var airPlayReceiver: AirPlayReceiver? = null
    private var miracastReceiver: MiracastReceiver? = null
    private var castReceiver: CastReceiver? = null

    /**
     * PhairPlay's own Google Cast receiver, used when no Google-issued Cast App ID is
     * configured. Runs instead of [castReceiver], never alongside it — two receivers
     * advertising `_googlecast._tcp` for the same IP make senders show duplicate entries.
     */
    private var castBridge: CastBridgeReceiver? = null

    /** Background update-check loop, cancelled in [onDestroy] (see [startUpdateChecker]). */
    private var updateCheckJob: kotlinx.coroutines.Job? = null

    /** Watches update preferences so toggles take effect without restarting the receivers. */
    private var updateSettingsJob: kotlinx.coroutines.Job? = null

    /** Why the Cast card is in its current state ("Cast App ID not set", "port 8009 in use"…). */
    private val _castDetail = MutableStateFlow<String?>(null)
    val castDetail: StateFlow<String?> = _castDetail.asStateFlow()

    /**
     * True while a Cast sender has media loaded (as opposed to merely connected).
     *
     * WHY: showing the full-screen video surface the moment a sender *launches* an app would
     * black out the Home screen for audio-only casting (Spotify, podcasts). This flips only
     * once there is actually something to draw — or something audible to report in the HUD.
     */
    private val _castMediaActive = MutableStateFlow(false)
    val castMediaActive: StateFlow<Boolean> = _castMediaActive.asStateFlow()

    /** Set while an automatic update check is running, so the UI can show a spinner. */
    private val _updateChecking = MutableStateFlow(false)
    val updateChecking: StateFlow<Boolean> = _updateChecking.asStateFlow()

    // Settings — read once when starting, re-read on restart
    private lateinit var settingsRepository: SettingsRepository

    // ─── Service Lifecycle ───────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        Logger.i("PhairPlayService created")
        settingsRepository = SettingsRepository(applicationContext)
        createNotificationChannel()
        updateSettingsJob = serviceScope.launch {
            var previous: Triple<Boolean, Boolean, Boolean>? = null
            settingsRepository.settingsFlow.collect { settings ->
                // Mirror the debug-overlay toggle into the shared stats bus as it changes.
                //
                // WHY HERE: Settings saves this one without restarting the receivers (it is a
                // UI-only preference), and it used to be read only inside startAirPlay() — so
                // flipping the switch did nothing until the user happened to press Restart, and
                // the overlay never appeared at all for Cast/Miracast sessions. Reading the flow
                // makes the toggle take effect the moment it is flipped, mid-session included.
                com.phairplay.airplay.StreamStats.overlayEnabled = settings.showDebugOverlay
                val current = Triple(
                    settings.autoCheckForUpdates,
                    settings.autoDownloadUpdates,
                    settings.autoInstallUpdates
                )
                if (previous != null && previous != current && _serviceState.value == ServiceState.Running) {
                    Logger.i("Update preferences changed — refreshing the background update checker")
                    startUpdateChecker(settings)
                }
                previous = current
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately with a persistent notification
        startForeground(NOTIFICATION_ID, buildNotification(isRunning = false))

        when (intent?.action) {
            ACTION_START   -> serviceScope.launch { startReceivers() }
            ACTION_STOP    -> serviceScope.launch { stopReceivers(); stopSelf() }
            ACTION_RESTART -> serviceScope.launch { restartReceivers() }
            else           -> serviceScope.launch { startReceivers() } // default: start
        }

        // START_STICKY: if the system kills the service, restart it with a null intent
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The app was swiped away from recents. Cleanly stop all receivers (which closes the RTSP
     * connection so an active mirror ends on the sender too) and stop the service — don't let
     * START_STICKY silently resurrect it as a zombie that keeps advertising/streaming invisibly.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i("App task removed — stopping receivers + service")
        stopReceivers()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Called by [MainActivity] after it binds, to supply the [Surface] for video rendering.
     *
     * The lambda is invoked lazily — only when a stream is actually being started — so it
     * is safe to call this before or after [startAirPlay]. The lambda should return null
     * if the Activity's StreamingScreen is not yet available (e.g., surface not yet created).
     *
     * Call with `{ null }` (or simply don't call) during Activity destruction so we stop
     * holding a reference to the Activity's Surface after the window is gone.
     *
     * @param provider Lambda that returns the current [Surface], or null if unavailable.
     */
    fun setVideoSurfaceProvider(provider: () -> Surface?) {
        videoSurfaceProvider = provider
        // A Cast session may already be playing (started while the Activity was in the
        // background with no Surface). Hand the new Surface over so video shows up.
        castBridge?.attachSurface()
    }

    /**
     * Bring PhairPlay's Activity to the front so there is a Surface to render video onto.
     *
     * WHY: PhairPlay draws through a Surface owned by [MainActivity], and MainActivity drops
     * that Surface as soon as it is no longer visible (it installs a null-returning provider
     * in `onStop`). A sender that starts casting while the TV is sitting on the home screen
     * would therefore play audio with no picture. Re-opening the Activity restores it.
     *
     * Android 10+ restricts background activity launches, so this is best-effort: it normally
     * succeeds because MainActivity is still in the back stack of a recent task. If the
     * system refuses we log it and carry on — audio-only is better than nothing.
     */
    private fun bringToFront() {
        runCatching {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            startActivity(intent)
        }.onFailure { error ->
            Logger.w("Could not bring PhairPlay to the front for playback: ${error.message}", error)
        }
    }

    /**
     * Sends a DACP transport command (TV remote → AirPlay sender), e.g. play/pause or skip what the
     * Mac/iPhone is streaming. Bound Activities call this from media-key events. No-op if no AirPlay
     * sender has advertised a DACP identity.
     */
    fun sendAirPlayRemoteCommand(command: String) {
        airPlayReceiver?.sendRemoteCommand(command)
    }

    override fun onDestroy() {
        updateCheckJob?.cancel()
        updateCheckJob = null
        updateSettingsJob?.cancel()
        updateSettingsJob = null
        Logger.i("PhairPlayService destroying")
        stopAllReceiversInternal()
        serviceJob.cancel()
        super.onDestroy()
    }

    // ─── Service Control ─────────────────────────────────────────────────────

    /**
     * Starts all receivers that are enabled in Settings.
     *
     * Reads current settings, then starts AirPlay, Miracast, and/or Cast
     * receivers according to the enabled flags.
     */
    private suspend fun startReceivers() {
        val settings = settingsRepository.settingsFlow.first()
        Logger.i("Starting receivers: AirPlay=${settings.airPlayEnabled}, Miracast=${settings.miracastEnabled}, Cast=${settings.castEnabled}")

        // Belt and braces for the debug overlay: the settings collector in onCreate() already
        // mirrors this, but a receiver started before that collector's first emission must not
        // read a stale value.
        com.phairplay.airplay.StreamStats.overlayEnabled = settings.showDebugOverlay

        _serviceState.value = ServiceState.Running
        updateNotification(isRunning = true)

        if (settings.airPlayEnabled)   startAirPlay(settings)
        if (settings.miracastEnabled)  startMiracast()
        if (settings.castEnabled)      startCast(settings)

        // Look for a newer PhairPlay build in the background. Throttled inside
        // UpdateManager (once every few hours), so this is cheap to call every start.
        startUpdateChecker(settings)
    }

    /**
     * Stops all active receivers and updates the service state to Stopped.
     * Does NOT call stopSelf() — use [ACTION_STOP] for that.
     */
    private fun stopReceivers() {
        Logger.i("Stopping all receivers")
        stopAllReceiversInternal()
        _serviceState.value = ServiceState.Stopped
        _activeConnection.value = null
        updateNotification(isRunning = false)
    }

    /**
     * Restarts all receivers: stops them, waits briefly, then starts them again.
     * Used for applying settings changes or recovering from errors.
     */
    private suspend fun restartReceivers() {
        Logger.i("Restarting all receivers")
        _serviceState.value = ServiceState.Restarting
        updateNotification(isRunning = false)
        stopAllReceiversInternal()
        kotlinx.coroutines.delay(500) // brief pause to ensure ports are released
        startReceivers()
    }

    // ─── Individual Protocol Starters ────────────────────────────────────────

    /**
     * Creates and starts the [AirPlayReceiver].
     *
     * The display name comes from settings — blank means use the Android device name,
     * which [MdnsService] resolves at runtime.
     *
     * Surface is not available here (it lives in the Activity/Fragment).
     * The surface provider is wired up from [MainActivity] in Sprint 5.
     * Until then, video frames are silently discarded and only audio plays.
     *
     * @param settings Current app settings; read once per start/restart cycle.
     */
    private fun startAirPlay(settings: AppSettings) {
        // (The debug-overlay setting is mirrored in onCreate()/startReceivers() — it is live,
        // not start-up-only.)

        // Idempotent: a redundant ACTION_START (e.g. the activity being recreated while the
        // foreground service is still alive) must NOT spin up a second AirPlayReceiver competing
        // for port 7000. The existing receiver keeps running and picks up the new Surface via the
        // surfaceProvider. A genuine restart goes through ACTION_RESTART (stop → delay → start).
        if (airPlayReceiver != null) {
            Logger.i("AirPlay receiver already running — skipping duplicate start")
            return
        }
        // Captures the sender name reported by AirPlayReceiver before CONNECTED fires.
        // onSenderNameChanged is called synchronously before emitState(CONNECTED), so
        // this assignment happens-before the Main-thread read in onStateChanged.
        var pendingSenderName = "AirPlay Sender"

        // Ask the sender for the largest mirror this TV can both show and decode: 1080p by
        // default, up to 4K on a 4K Google TV when Settings → "Higher resolution" is on.
        // Advertising more than the H.264 decoder supports would produce a black screen.
        val panel = DisplayCaps.panelSize(applicationContext)
        val decode = DisplayCaps.maxH264Size()
        val mirror = settings.advertisedMirrorResolution(
            panelWidth = panel.first, panelHeight = panel.second,
            decodeWidth = decode.first, decodeHeight = decode.second
        )
        Logger.i("AirPlay mirror advertised at ${mirror.label} (${mirror.width}x${mirror.height}) — " +
                 "panel ${panel.first}x${panel.second}, H.264 ceiling ${decode.first}x${decode.second}")

        airPlayReceiver = AirPlayReceiver(
            context = applicationContext,
            displayName = settings.effectiveDisplayName,
            mirrorWidth = mirror.width,
            mirrorHeight = mirror.height,
            audioEnabled = settings.mirrorAudioEnabled,
            pinAuthEnabled = settings.airPlayPinAuthEnabled,
            // Delegate to the current provider at call time — captures the field, not a fixed value.
            // When MainActivity calls setVideoSurfaceProvider(), future surface requests use it.
            videoSurfaceProvider = { videoSurfaceProvider?.invoke() },
            onSenderNameChanged = { name ->
                pendingSenderName = name.ifEmpty { "AirPlay Sender" }
            },
            onActualNameRegistered = { name -> _registeredName.value = name },
            onPhotoReceived = { bytes, imageType ->
                _photoFrame.value = PhotoFrame(
                    bytes = bytes.copyOf(),
                    mimeType = imageType.mimeType
                )
                updateNotification(isRunning = true)
            },
            onPhotoCleared = {
                _photoFrame.value = null
            },
            onNowPlayingChanged = { info ->
                _nowPlaying.value = info
            },
            onPinChanged = { pin ->
                _pairingPin.value = pin
            },
            onStateChanged = { state ->
                _airPlayState.value = state
                when (state) {
                    ProtocolState.CONNECTED   -> {
                        _photoFrame.value = null
                        _activeConnection.value =
                            ActiveConnection(pendingSenderName, Protocol.AIRPLAY)
                        beginStatsSession(com.phairplay.airplay.StreamStats.SOURCE_AIRPLAY)
                        updateNotification(isRunning = true, streamingSenderName = pendingSenderName)
                    }
                    ProtocolState.ADVERTISING,
                    ProtocolState.DISABLED,
                    ProtocolState.ERROR       -> {
                        _activeConnection.value = null
                        endStatsSession(com.phairplay.airplay.StreamStats.SOURCE_AIRPLAY)
                        updateNotification(isRunning = state != ProtocolState.DISABLED &&
                                                       state != ProtocolState.ERROR)
                    }
                }
            }
        ).also { it.start() }
        Logger.d("AirPlay receiver started (displayName='${settings.effectiveDisplayName}')")
    }

    /**
     * Starts a fresh debug-overlay session for [source], clearing the previous one's counters.
     *
     * Never clears another protocol's session: a Cast session ending must not wipe counters an
     * AirPlay stream is still writing.
     */
    private fun beginStatsSession(source: String) {
        com.phairplay.airplay.StreamStats.beginSession(source)
    }

    /** Ends the debug-overlay session, but only if [source] is the one that owns it. */
    private fun endStatsSession(source: String) {
        if (com.phairplay.airplay.StreamStats.source == source) {
            com.phairplay.airplay.StreamStats.endSession()
        }
    }

    private fun startMiracast() {
        // Sender name: Wi-Fi Direct does not expose a friendly peer name during
        // WFD setup, so a stable generic label is used on the status card.
        val senderName = getString(R.string.miracast_sender_name)
        miracastReceiver = MiracastReceiver(
            context = applicationContext,
            // Same surface AirPlay decodes onto — MainActivity shows the streaming
            // overlay for Miracast CONNECTED, so the Surface exists before frames flow.
            videoSurfaceProvider = { videoSurfaceProvider?.invoke() },
            onStateChanged = { state ->
                _miracastState.value = state
                when (state) {
                    ProtocolState.CONNECTED -> {
                        _activeConnection.value = ActiveConnection(senderName, Protocol.MIRACAST)
                        beginStatsSession(com.phairplay.airplay.StreamStats.SOURCE_MIRACAST)
                        updateNotification(isRunning = true, streamingSenderName = senderName)
                    }
                    ProtocolState.ADVERTISING,
                    ProtocolState.DISABLED,
                    ProtocolState.ERROR -> {
                        // Another protocol (AirPlay) may own the active connection.
                        if (_activeConnection.value?.protocol == Protocol.MIRACAST) {
                            _activeConnection.value = null
                        }
                        endStatsSession(com.phairplay.airplay.StreamStats.SOURCE_MIRACAST)
                        updateNotification(isRunning = state != ProtocolState.DISABLED &&
                                                       state != ProtocolState.ERROR)
                    }
                }
            }
            // Note: no optimistic ADVERTISING preset — the receiver emits the real
            // state once the Wi-Fi P2P service registration completes (async).
        ).also { it.start() }
        Logger.d("Miracast receiver started")
    }

    /**
     * Starts Google Cast, choosing between the two implementations:
     *
     * 1. **Cast Connect** (Google's SDK) when this build carries a Cast Application ID.
     *    That is the official path and needs a registration at <https://cast.google.com/publish>.
     * 2. **PhairPlay's Cast bridge** otherwise — mDNS + DIAL + castv2 implemented here, so
     *    casting works on a TV whose owner has not registered anything with Google.
     */
    private fun startCast(settings: AppSettings) {
        if (CastReceiver.isConfigured()) {
            castReceiver = CastReceiver(
                context = applicationContext,
                onStateChanged = { state ->
                    _castState.value = state
                    _castDetail.value = when (state) {
                        ProtocolState.ADVERTISING -> getString(R.string.cast_detail_connect_advertising)
                        ProtocolState.ERROR -> getString(R.string.protocol_detail_error_cast)
                        else -> null
                    }
                }
            ).also { it.start() }
            Logger.d("Cast receiver started (Cast Connect, registered app ID)")
            return
        }

        if (!settings.castBridgeEnabled) {
            _castDetail.value = getString(R.string.cast_detail_disabled)
            _castState.value = ProtocolState.DISABLED
            Logger.w("Cast is on but both back-ends are unavailable: no Cast App ID and the bridge is off")
            return
        }

        val bridge = CastBridgeReceiver(
            context = applicationContext,
            displayName = settings.effectiveDisplayName,
            surfaceProvider = { videoSurfaceProvider?.invoke() },
            onStateChanged = { state ->
                _castState.value = state
                _castDetail.value = when (state) {
                    ProtocolState.ADVERTISING -> castBridge?.lastNotice
                        ?: castBridge?.lastError
                        ?: getString(R.string.cast_detail_bridge_advertising)
                    ProtocolState.ERROR -> castBridge?.lastError ?: getString(R.string.cast_detail_error)
                    else -> null
                }
                if (state == ProtocolState.CONNECTED) {
                    // A Cast sender connects from outside the app — make sure there is a
                    // Surface to draw on before the LOAD command arrives.
                    bringToFront()
                    _activeConnection.value = ActiveConnection(
                        getString(R.string.cast_sender_name), Protocol.CAST
                    )
                    beginStatsSession(com.phairplay.airplay.StreamStats.SOURCE_CAST)
                } else {
                    if (_activeConnection.value?.protocol == Protocol.CAST) {
                        _activeConnection.value = null
                    }
                    endStatsSession(com.phairplay.airplay.StreamStats.SOURCE_CAST)
                }
            },
            onMediaChanged = { state, positionSec, durationSec ->
                val stats = com.phairplay.airplay.StreamStats
                stats.castState = state
                stats.castPositionSec = positionSec
                stats.castDurationSec = durationSec
                stats.audioActive = state == CastMediaPlayer.State.PLAYING
                _castMediaActive.value = state != CastMediaPlayer.State.IDLE
            },
            onNotice = { message -> _castDetail.value = message }
        )
        castBridge = bridge
        bridge.start()
        if (bridge.lastError != null) {
            _castDetail.value = bridge.lastError
            _castState.value = ProtocolState.ERROR
        }
        Logger.d("Cast bridge started as '${settings.effectiveDisplayName}'")
    }

    /**
     * Periodically asks GitHub whether a newer PhairPlay APK exists.
     *
     * Runs on the service's own scope, so a TV left sitting on the Home screen still hears
     * about an update instead of only finding out the next time Settings is opened.
     * UpdateManager throttles the network call itself; this loop only wakes it up.
     */
    private fun startUpdateChecker(settings: AppSettings) {
        updateCheckJob?.cancel()
        if (!settings.autoCheckForUpdates) return
        updateCheckJob = serviceScope.launch {
            while (isActive) {
                runCatching { runUpdateCheck(settings) }
                    .onFailure { Logger.w("Background update check failed: ${it.message}") }
                delay(UPDATE_CHECK_PERIOD_MS)
            }
        }
    }

    /**
     * One full update pass: check → (maybe) download → (maybe) install → notify.
     *
     * @return what the check found, so the Settings screen can report it.
     */
    private suspend fun runUpdateCheck(settings: AppSettings): UpdateCheck =
        com.phairplay.update.UpdateFlow.run(
            context = applicationContext,
            autoDownload = settings.autoDownloadUpdates,
            autoInstall = settings.autoInstallUpdates,
            onNotifyAvailable = { notifyUpdateAvailable(it) },
            onNotifyReady = { notifyUpdateReady(it) },
            onNotifyKeyMismatch = { notifyUpdateKeyMigrationRequired(it) }
        )

    /**
     * Settings → "Check for updates": one immediate check, respecting the download/install
     * preferences. Safe to call from the UI — the network work runs on [Dispatchers.IO].
     */
    suspend fun checkForUpdatesNow(): UpdateCheck {
        val settings = settingsRepository.settingsFlow.first()
        _updateChecking.value = true
        return try {
            runUpdateCheck(settings)
        } finally {
            _updateChecking.value = false
        }
    }

    /** Downloads an update the user just agreed to. */
    suspend fun downloadUpdate(info: UpdateInfo): StageResult =
        UpdateManager.get(applicationContext).downloadAndStage(info)

    /** Installs the staged update. */
    suspend fun installStagedUpdate(): Boolean =
        UpdateManager.get(applicationContext).installStaged()

    /** The download waiting to be installed, if any. */
    fun stagedUpdate(): StagedUpdate? = UpdateManager.get(applicationContext).stagedUpdate()

    /** Forgets the staged download. */
    fun discardStagedUpdate() = UpdateManager.get(applicationContext).clearDownload()

    private fun notifyUpdateAvailable(info: UpdateInfo) = notifyUpdate(
        title = getString(R.string.update_notification_title),
        text = getString(R.string.update_notification_available, info.shortLabel())
    )

    private fun notifyUpdateReady(info: UpdateInfo) = notifyUpdate(
        title = getString(R.string.update_notification_title),
        text = getString(R.string.update_notification_ready, info.shortLabel())
    )

    private fun notifyUpdateKeyMigrationRequired(info: UpdateInfo) = notifyUpdate(
        title = getString(R.string.update_notification_migration_title),
        text = getString(R.string.update_notification_migration, info.shortLabel())
    )

    /** Posts a dismissible (non-ongoing) notification on the service channel. */
    private fun notifyUpdate(title: String, text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        val openApp = PendingIntent.getActivity(
            this, UPDATE_PENDING_INTENT_ID,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .build()
        try {
            manager.notify(UPDATE_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Android 13+ needs POST_NOTIFICATIONS; without it the Settings screen is the
            // only place an update can be announced, which is fine.
            Logger.w("Could not post the update notification: notification permission not granted")
        }
    }

    private fun stopAllReceiversInternal() {
        try { airPlayReceiver?.stop() } catch (e: Exception) { Logger.e("AirPlay stop error", e) }
        try { miracastReceiver?.stop() } catch (e: Exception) { Logger.e("Miracast stop error", e) }
        try { castReceiver?.stop() } catch (e: Exception) { Logger.e("Cast stop error", e) }
        try { castBridge?.stop() } catch (e: Exception) { Logger.e("Cast bridge stop error", e) }
        airPlayReceiver = null
        miracastReceiver = null
        castReceiver = null
        castBridge = null
        _castDetail.value = null
        _airPlayState.value = ProtocolState.DISABLED
        _miracastState.value = ProtocolState.DISABLED
        _castState.value = ProtocolState.DISABLED
        _castMediaActive.value = false
        _photoFrame.value = null
        _nowPlaying.value = null
        _pairingPin.value = null
        _registeredName.value = null
        // Every receiver is gone, so no session can still be writing counters.
        com.phairplay.airplay.StreamStats.endSession()
    }

    // ─── Notification ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW  // LOW: no sound, minimal visual interruption
            ).apply {
                description = getString(R.string.notification_channel_description)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    /**
     * Builds the persistent notification for the ForegroundService.
     *
     * The notification shows the service status and provides quick actions
     * so users can Stop or Restart without opening the app.
     *
     * @param isRunning            True if receivers are active; false if stopped/restarting.
     * @param notificationContentText Override for the notification body text.
     *   When null, the default running/stopped status string is used.
     *   Pass the sender name here (e.g. "Streaming from MacBook Pro") when connected.
     */
    private fun buildNotification(
        isRunning: Boolean,
        notificationContentText: String? = null
    ): Notification {
        // Tapping the notification opens the app
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Stop" action — sends ACTION_STOP to this service
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PhairPlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Restart" action — sends ACTION_RESTART to this service
        val restartIntent = PendingIntent.getService(
            this, 2,
            Intent(this, PhairPlayService::class.java).apply { action = ACTION_RESTART },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = if (isRunning) R.string.notification_status_running
                         else           R.string.notification_status_stopped

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationContentText ?: getString(statusText))
            .setContentIntent(openAppIntent)
            .setOngoing(true)                   // Prevents user from swiping away
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(R.drawable.ic_stop,    getString(R.string.action_stop),    stopIntent)
            .addAction(R.drawable.ic_restart, getString(R.string.action_restart), restartIntent)
            .build()
    }

    private fun updateNotification(isRunning: Boolean, streamingSenderName: String? = null) {
        val contentText = streamingSenderName?.let {
            getString(R.string.notification_status_streaming, it)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(isRunning, contentText))
    }

    // ─── Binder ─────────────────────────────────────────────────────────────

    /**
     * LocalBinder — Provides direct access to [PhairPlayService] for bound Activities.
     *
     * WHY: Binding (rather than just starting) the service gives the Activity a
     * direct reference, so it can observe the service's StateFlows without
     * using broadcasts or a shared ViewModel.
     */
    inner class LocalBinder : Binder() {
        fun getService(): PhairPlayService = this@PhairPlayService
    }

    companion object {
        const val CHANNEL_ID      = "phairplay_service_channel"
        const val NOTIFICATION_ID = 1001

        /** Separate, dismissible notification used to announce an available update. */
        const val UPDATE_NOTIFICATION_ID = 1002
        const val UPDATE_PENDING_INTENT_ID = 20

        /** How often the background updater wakes up (UpdateManager throttles the rest). */
        const val UPDATE_CHECK_PERIOD_MS = 6 * 60 * 60 * 1000L
        const val ACTION_START    = "com.phairplay.action.START"
        const val ACTION_STOP     = "com.phairplay.action.STOP"
        const val ACTION_RESTART  = "com.phairplay.action.RESTART"
    }
}

/**
 * PhotoFrame — latest still image received via AirPlay `/photo`.
 *
 * The bytes are kept in memory only and cleared on DELETE `/photo`, streaming
 * start, receiver stop, or service destruction.
 */
data class PhotoFrame(
    val bytes: ByteArray,
    val mimeType: String,
    val receivedAtMillis: Long = System.currentTimeMillis()
)
