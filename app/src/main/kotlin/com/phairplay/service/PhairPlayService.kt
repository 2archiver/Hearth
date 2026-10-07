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
import com.phairplay.airplay.ReceiverShutdownReason
import com.phairplay.update.StageResult
import com.phairplay.update.StagedUpdate
import com.phairplay.update.UpdateCheck
import com.phairplay.update.UpdateInfo
import com.phairplay.update.UpdateManager
import com.phairplay.update.UpdateNotifications
import com.phairplay.update.UpdateWorkScheduler
import com.phairplay.update.UpdateInstallSafety
import com.phairplay.settings.AppSettings
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel

/**
 * PhairPlayService — Android ForegroundService that hosts all receiver protocols.
 *
 * WHY: The AirPlay receiver needs to run continuously in the background.
 * Android may kill background processes. A ForegroundService with a persistent
 * notification keeps the app alive and shows the user that Hearth is active.
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

    private sealed interface ReceiverLifecycleCommand {
        val ticket: Long
        data class Start(override val ticket: Long) : ReceiverLifecycleCommand
        data class Stop(override val ticket: Long, val reason: ReceiverShutdownReason) : ReceiverLifecycleCommand
        data class Restart(override val ticket: Long) : ReceiverLifecycleCommand
    }

    // Coroutine scope — cancelled in onDestroy() to clean up all coroutines
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    /** Commands arrive on Android's main thread and are consumed in their exact enqueue order. */
    private val receiverLifecycleCommands = Channel<ReceiverLifecycleCommand>(Channel.UNLIMITED)
    private val receiverCommandGate = ReceiverLifecycleGate()
    private val receiverCallbackGate = ReceiverLifecycleGate()
    private val playbackStopGate = PlaybackStopGate()
    @Volatile private var destroyed = false

    // Observable state — Activities and Fragments observe this via the binder
    private val _serviceState = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()

    private val _airPlayState = MutableStateFlow(ProtocolState.DISABLED)
    val airPlayState: StateFlow<ProtocolState> = _airPlayState.asStateFlow()

    /** URL-video player state is distinct from the RTSP connection's connected/advertising state. */
    private val _airPlayPlaybackState = MutableStateFlow<com.phairplay.airplay.AirPlayPlaybackState?>(null)
    val airPlayPlaybackState: StateFlow<com.phairplay.airplay.AirPlayPlaybackState?> =
        _airPlayPlaybackState.asStateFlow()
    private val _airPlayPlaybackFailure =
        MutableStateFlow<com.phairplay.airplay.AirPlayPlaybackFailure?>(null)
    val airPlayPlaybackFailure: StateFlow<com.phairplay.airplay.AirPlayPlaybackFailure?> =
        _airPlayPlaybackFailure.asStateFlow()

    /**
     * The **Apple Casting** card: screen mirroring from an iPhone/iPad/Mac.
     *
     * Same AirPlay receiver as [airPlayState] — one radio, one protocol — but it answers a
     * different question, so it reports a different thing: [ProtocolState.CONNECTED] only while a
     * mirror *video* stream is actually on screen, not merely while a sender holds a control
     * connection. Someone looking at this card wants to know "is my phone's screen on the TV?".
     */
    private val _appleCastingState = MutableStateFlow(ProtocolState.DISABLED)
    val appleCastingState: StateFlow<ProtocolState> = _appleCastingState.asStateFlow()

    private val _activeConnection = MutableStateFlow<ActiveConnection?>(null)
    val activeConnection: StateFlow<ActiveConnection?> = _activeConnection.asStateFlow()

    private val _photoFrame = MutableStateFlow<PhotoFrame?>(null)
    val photoFrame: StateFlow<PhotoFrame?> = _photoFrame.asStateFlow()

    // Non-null while AirPlay audio is playing WITHOUT video — drives the now-playing overlay.
    private val _nowPlaying = MutableStateFlow<com.phairplay.airplay.NowPlayingInfo?>(null)
    val nowPlaying: StateFlow<com.phairplay.airplay.NowPlayingInfo?> = _nowPlaying.asStateFlow()

    /**
     * Mirroring in progress — set by the AirPlay receiver when the video stream starts and cleared
     * when it stops. Drives [appleCastingState].
     */
    @Volatile private var mirroring = false

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
    @Volatile private var airPlayReceiver: AirPlayReceiver? = null
    @Volatile private var airPlayReceiverCallbackTicket: Long = 0L

    /** Watches update preferences so toggles take effect without restarting the receivers. */
    private var updateSettingsJob: kotlinx.coroutines.Job? = null

    /**
     * Why each card is in the state it is in — the address the TV is advertising on, a
     * refused mDNS record, a TV whose Wi-Fi radio is off.
     *
     * WHY: a bare "Advertising"/"Disabled" line is what made "AirPlay does not work on my
     * wired TV" impossible to answer. The receivers already know the real reason; the Home
     * screen just never showed it. Null means "nothing to add", and the card falls back to its
     * generic wording.
     */
    private val _airPlayDetail = MutableStateFlow<String?>(null)
    val airPlayDetail: StateFlow<String?> = _airPlayDetail.asStateFlow()

    /** One honest line under the Apple Casting card — a how-to, or who is mirroring. */
    private val _appleCastingDetail = MutableStateFlow<String?>(null)
    val appleCastingDetail: StateFlow<String?> = _appleCastingDetail.asStateFlow()

    /**
     * Every step of the most recent sender connection, newest last — the list the UI shows so a
     * failed connection can be described in one sentence instead of "it doesn't work".
     */
    val airPlayTrace: StateFlow<List<com.phairplay.airplay.AirPlayTrace.Entry>> =
        com.phairplay.airplay.AirPlayTrace.entries

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
        serviceScope.launch {
            for (command in receiverLifecycleCommands) {
                if (destroyed) break
                try {
                    when (command) {
                        is ReceiverLifecycleCommand.Start -> {
                            if (receiverCommandGate.isCurrent(command.ticket)) startReceivers(command.ticket)
                        }
                        is ReceiverLifecycleCommand.Stop -> {
                            stopReceivers(command.reason)
                            receiverCommandGate.runIfCurrent(command.ticket) {
                                _serviceState.value = ServiceState.Stopped
                                stopSelf()
                            }
                        }
                        is ReceiverLifecycleCommand.Restart -> {
                            if (receiverCommandGate.isCurrent(command.ticket)) restartReceivers(command.ticket)
                        }
                    }
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Logger.e("Receiver lifecycle command failed (${error.javaClass.simpleName})")
                    receiverCommandGate.runIfCurrent(command.ticket) {
                        _serviceState.value = ServiceState.Error("Receiver lifecycle failed")
                        updateNotification(isRunning = false)
                    }
                }
            }
        }
        updateSettingsJob = serviceScope.launch {
            var previous: Triple<Boolean, Boolean, Boolean>? = null
            settingsRepository.settingsFlow.collect { settings ->
                // Mirror the debug-overlay toggle into the shared stats bus as it changes.
                //
                // WHY HERE: Settings saves this one without restarting the receivers (it is a
                // UI-only preference), and it used to be read only inside startAirPlay() — so
                // flipping the switch did nothing until the user happened to press Restart, and
                // the overlay never appeared at all for any session. Reading the flow
                // makes the toggle take effect the moment it is flipped, mid-session included.
                com.phairplay.airplay.StreamStats.overlayEnabled = settings.showDebugOverlay
                val current = Triple(
                    settings.autoCheckForUpdates,
                    settings.autoDownloadUpdates,
                    settings.autoInstallUpdates
                )
                if (previous == null || previous.first != current.first) {
                    UpdateWorkScheduler.sync(applicationContext, settings.autoCheckForUpdates)
                }
                if (previous != null && previous != current) {
                    Logger.i("Update preferences changed — the next scheduled check will use the new policy")
                }
                previous = current
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately with a persistent notification
        startForeground(NOTIFICATION_ID, buildNotification(isRunning = false))

        when (intent?.action) {
            ACTION_START -> enqueueReceiverStart()
            ACTION_STOP -> enqueueReceiverStop(ReceiverShutdownReason.USER_REQUESTED)
            ACTION_STOP_PLAYBACK -> stopCurrentPlayback()
            ACTION_RESTART -> enqueueReceiverRestart()
            else -> enqueueReceiverStart() // default: start
        }

        // START_STICKY: if the system kills the service, restart it with a null intent
        return START_STICKY
    }

    /** Enqueues a serialized start after issuing a generation for this user/system command. */
    private fun enqueueReceiverStart() {
        queueReceiverLifecycleCommand(ReceiverLifecycleCommand.Start(receiverCommandGate.next()))
    }

    /** Acknowledge Stop synchronously, then enqueue the potentially slow receiver teardown. */
    private fun enqueueReceiverStop(reason: ReceiverShutdownReason) {
        val command = ReceiverLifecycleCommand.Stop(receiverCommandGate.next(), reason)
        receiverCallbackGate.next() // invalidate callbacks before the synchronous Stop acknowledgment
        acknowledgeReceiverStop()
        queueReceiverLifecycleCommand(command)
    }

    /** Mark Restarting immediately; the lifecycle actor performs stop → delay → start in order. */
    private fun enqueueReceiverRestart() {
        val command = ReceiverLifecycleCommand.Restart(receiverCommandGate.next())
        receiverCallbackGate.next()
        receiverCommandGate.runIfCurrent(command.ticket) {
            _serviceState.value = ServiceState.Restarting
            updateNotification(isRunning = false)
        }
        queueReceiverLifecycleCommand(command)
    }

    /** Queues lifecycle commands from onStartCommand in main-thread arrival order. */
    private fun queueReceiverLifecycleCommand(command: ReceiverLifecycleCommand) {
        if (receiverLifecycleCommands.trySend(command).isFailure && !destroyed) {
            Logger.w("Could not queue receiver lifecycle command ${command.javaClass.simpleName}")
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * The app was swiped away from recents. Cleanly stop all receivers (which closes the RTSP
     * connection so an active mirror ends on the sender too) and stop the service — don't let
     * START_STICKY silently resurrect it as a zombie that keeps advertising/streaming invisibly.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.i("App task removed — stopping receivers + service")
        enqueueReceiverStop(ReceiverShutdownReason.APP_TASK_REMOVED)
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
    }

    /**
     * Sends a DACP transport command (TV remote → AirPlay sender), e.g. play/pause or skip what the
     * Mac/iPhone is streaming. Bound Activities call this from media-key events. No-op if no AirPlay
     * sender has advertised a DACP identity.
     */
    fun sendAirPlayRemoteCommand(command: String) {
        airPlayReceiver?.sendRemoteCommand(command)
    }

    /** Stop the current cast and retain this service/receiver so discovery stays available. */
    fun stopCurrentPlayback() {
        val receiver = airPlayReceiver ?: return
        val callbackTicket = airPlayReceiverCallbackTicket
        var acknowledged = false
        receiverCallbackGate.runIfCurrent(callbackTicket) {
            if (destroyed || airPlayReceiver !== receiver ||
                airPlayReceiverCallbackTicket != callbackTicket || _serviceState.value !is ServiceState.Running
            ) return@runIfCurrent

            acknowledged = playbackStopGate.beginStopIf(
                isActive = {
                    val state = _airPlayPlaybackState.value
                    _activeConnection.value != null || mirroring || _photoFrame.value != null ||
                        _nowPlaying.value != null || state != null && state !in setOf(
                            com.phairplay.airplay.AirPlayPlaybackState.STOPPED,
                            com.phairplay.airplay.AirPlayPlaybackState.DISCONNECTED,
                        )
                },
                acknowledge = {
                    // These StateFlows are observed on the main thread. Publish synchronously, and
                    // serialize against callbacks so a stale CONNECTED/PLAYING event cannot undo
                    // the Stop acknowledgment while socket/codec teardown is still on IO.
                    _airPlayPlaybackState.value = com.phairplay.airplay.AirPlayPlaybackState.STOPPING
                    _airPlayPlaybackFailure.value = null
                    refreshUpdateInstallSafety()
                    _airPlayState.value = ProtocolState.ADVERTISING
                    _appleCastingState.value = ProtocolState.ADVERTISING
                    _appleCastingDetail.value = null
                    _activeConnection.value = null
                    _photoFrame.value = null
                    _nowPlaying.value = null
                    mirroring = false
                    endStatsSession(com.phairplay.airplay.StreamStats.SOURCE_AIRPLAY)
                    updateNotification(isRunning = true)
                }
            )
        }
        if (!acknowledged) return

        serviceScope.launch {
            try {
                if (!destroyed && airPlayReceiver === receiver && receiverCallbackGate.isCurrent(callbackTicket)) {
                    receiver.stopPlayback()
                }
            } catch (error: Exception) {
                Logger.w("Stop playback failed (${error.javaClass.simpleName})")
                com.phairplay.airplay.AirPlayTrace.record(
                    "Stop playback failed (${error.javaClass.simpleName})",
                    kind = com.phairplay.airplay.AirPlayTrace.Kind.FAILURE,
                )
            } finally {
                receiverCallbackGate.runIfCurrent(callbackTicket) {
                    if (!destroyed && airPlayReceiver === receiver &&
                        airPlayReceiverCallbackTicket == callbackTicket
                    ) {
                        playbackStopGate.runCallback(publishDuringStop = true) {
                            if (_airPlayPlaybackState.value == com.phairplay.airplay.AirPlayPlaybackState.STOPPING) {
                                _airPlayPlaybackState.value = com.phairplay.airplay.AirPlayPlaybackState.STOPPED
                                refreshUpdateInstallSafety()
                            }
                        }
                    }
                }
                playbackStopGate.finishStop()
            }
        }
    }

    override fun onDestroy() {
        destroyed = true
        receiverCallbackGate.next()
        receiverLifecycleCommands.close()
        serviceJob.cancel()
        updateSettingsJob?.cancel()
        updateSettingsJob = null
        Logger.i("PhairPlayService destroying")
        stopAllReceiversInternal(ReceiverShutdownReason.SERVICE_DESTROYED)
        super.onDestroy()
    }

    // ─── Service Control ─────────────────────────────────────────────────────

    /**
     * Starts all receivers that are enabled in Settings.
     *
     * Reads current settings, then starts the AirPlay receiver when it is enabled.
     */
    private suspend fun startReceivers(ticket: Long) {
        if (destroyed || !receiverCommandGate.isCurrent(ticket)) return
        val settings = settingsRepository.settingsFlow.first()
        if (destroyed || !receiverCommandGate.isCurrent(ticket)) return
        Logger.i("Starting receivers: AirPlay=${settings.airPlayEnabled}")

        // Belt and braces for the debug overlay: the settings collector in onCreate() already
        // mirrors this, but a receiver started before that collector's first emission must not
        // read a stale value.
        com.phairplay.airplay.StreamStats.overlayEnabled = settings.showDebugOverlay

        if (!receiverCommandGate.runIfCurrent(ticket) {
                _serviceState.value = ServiceState.Running
                updateNotification(isRunning = true)
            }
        ) return

        if (settings.airPlayEnabled) {
            startAirPlay(settings, ticket)
        } else {
            _appleCastingState.value = ProtocolState.DISABLED
            _appleCastingDetail.value = null
        }

        if (destroyed || !receiverCommandGate.isCurrent(ticket)) return
        // Keep durable hourly work aligned with the existing preference; Android may defer it in Doze.
        UpdateWorkScheduler.sync(applicationContext, settings.autoCheckForUpdates)
    }

    /** Publish a visible Stop acknowledgment before socket/codec teardown begins. */
    private fun acknowledgeReceiverStop() {
        _serviceState.value = ServiceState.Stopping
        _airPlayState.value = ProtocolState.DISABLED
        _appleCastingState.value = ProtocolState.DISABLED
        _appleCastingDetail.value = null
        _activeConnection.value = null
        _photoFrame.value = null
        _nowPlaying.value = null
        mirroring = false
        refreshUpdateInstallSafety()
        endStatsSession(com.phairplay.airplay.StreamStats.SOURCE_AIRPLAY)
        updateNotification(isRunning = false)
    }

    /**
     * Stops all active receivers and updates the service state to Stopped.
     * Does NOT call stopSelf() — use [ACTION_STOP] for that.
     */
    private fun stopReceivers(reason: ReceiverShutdownReason) {
        Logger.i("Stopping all receivers (${reason.name.lowercase()})")
        stopAllReceiversInternal(reason)
        _activeConnection.value = null
        updateNotification(isRunning = false)
    }

    /**
     * Restarts all receivers: stops them, waits briefly, then starts them again.
     * Used for applying settings changes or recovering from errors.
     */
    private suspend fun restartReceivers(ticket: Long) {
        if (destroyed || !receiverCommandGate.isCurrent(ticket)) return
        Logger.i("Restarting all receivers (${ReceiverShutdownReason.RESTART.name.lowercase()})")
        stopAllReceiversInternal(ReceiverShutdownReason.RESTART)
        kotlinx.coroutines.delay(500) // brief pause to ensure ports are released
        if (destroyed || !receiverCommandGate.isCurrent(ticket)) return
        startReceivers(ticket)
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
    private fun startAirPlay(settings: AppSettings, ticket: Long) {
        if (destroyed || !receiverCommandGate.isCurrent(ticket)) return
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
        _airPlayPlaybackState.value = null
        _airPlayPlaybackFailure.value = null
        refreshUpdateInstallSafety()
        // Captures the sender name reported by AirPlayReceiver before CONNECTED fires.
        // onSenderNameChanged is called synchronously before emitState(CONNECTED), so
        // this assignment happens-before the Main-thread read in onStateChanged.
        val pendingSenderName = java.util.concurrent.atomic.AtomicReference("AirPlay Sender")

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

        val callbackTicket = receiverCallbackGate.next()
        lateinit var receiver: AirPlayReceiver
        receiver = AirPlayReceiver(
            context = applicationContext,
            displayName = settings.effectiveDisplayName,
            mirrorWidth = mirror.width,
            mirrorHeight = mirror.height,
            audioEnabled = settings.mirrorAudioEnabled,
            // Delegate to the current provider at call time — captures the field, not a fixed value.
            videoSurfaceProvider = { videoSurfaceProvider?.invoke() },
            onSenderNameChanged = { name ->
                withCurrentAirPlayReceiver(receiver, callbackTicket, publishDuringPlaybackStop = true) {
                    pendingSenderName.set(name.ifEmpty { "AirPlay Sender" })
                }
            },
            onActualNameRegistered = { name ->
                withCurrentAirPlayReceiver(receiver, callbackTicket, publishDuringPlaybackStop = true) {
                    _registeredName.value = name
                }
            },
            onPhotoReceived = { bytes, imageType ->
                withCurrentAirPlayReceiver(receiver, callbackTicket) {
                    // The receiver transfers this request-owned array; neither it nor the view mutates it.
                    _photoFrame.value = PhotoFrame(bytes, imageType.mimeType)
                    refreshUpdateInstallSafety()
                    updateNotification(isRunning = true)
                }
            },
            onPhotoCleared = {
                withCurrentAirPlayReceiver(receiver, callbackTicket, publishDuringPlaybackStop = true) {
                    _photoFrame.value = null
                    refreshUpdateInstallSafety()
                }
            },
            onNowPlayingChanged = { info ->
                withCurrentAirPlayReceiver(
                    receiver,
                    callbackTicket,
                    publishDuringPlaybackStop = info == null,
                ) {
                    _nowPlaying.value = info
                    refreshUpdateInstallSafety()
                }
            },
            onAdvertiseNotice = { notice ->
                withCurrentAirPlayReceiver(receiver, callbackTicket, publishDuringPlaybackStop = true) {
                    _airPlayDetail.value = notice
                }
            },
            onUrlPlaybackStateChanged = { state, failure ->
                withCurrentAirPlayReceiver(
                    receiver,
                    callbackTicket,
                    publishDuringPlaybackStop = state == com.phairplay.airplay.AirPlayPlaybackState.STOPPED,
                ) {
                    _airPlayPlaybackState.value = state.takeUnless {
                        it == com.phairplay.airplay.AirPlayPlaybackState.DISCONNECTED
                    }
                    _airPlayPlaybackFailure.value = failure
                    refreshUpdateInstallSafety()
                }
            },
            onMirroringChanged = { active ->
                withCurrentAirPlayReceiver(receiver, callbackTicket, publishDuringPlaybackStop = !active) {
                    mirroring = active
                    _appleCastingState.value = when {
                        active -> ProtocolState.CONNECTED
                        _airPlayState.value == ProtocolState.ERROR -> ProtocolState.ERROR
                        else -> ProtocolState.ADVERTISING
                    }
                    _appleCastingDetail.value = if (active) {
                        getString(R.string.protocol_detail_mirroring, pendingSenderName.get())
                    } else {
                        null
                    }
                    refreshUpdateInstallSafety()
                    Logger.i("Apple Casting: mirroring ${if (active) "started" else "stopped"}")
                }
            },
            onStateChanged = { state ->
                withCurrentAirPlayReceiver(
                    receiver,
                    callbackTicket,
                    publishDuringPlaybackStop = state != ProtocolState.CONNECTED,
                ) {
                    _airPlayState.value = state
                    when (state) {
                        ProtocolState.CONNECTED -> {
                            _photoFrame.value = null
                            _activeConnection.value = ActiveConnection(pendingSenderName.get(), Protocol.AIRPLAY)
                            beginStatsSession(com.phairplay.airplay.StreamStats.SOURCE_AIRPLAY)
                            updateNotification(
                                isRunning = true,
                                streamingSenderName = pendingSenderName.get(),
                            )
                        }
                        ProtocolState.ADVERTISING,
                        ProtocolState.UNAVAILABLE,
                        ProtocolState.DISABLED,
                        ProtocolState.ERROR -> {
                            if (state == ProtocolState.ADVERTISING &&
                                _airPlayPlaybackState.value == com.phairplay.airplay.AirPlayPlaybackState.AUDIO_ONLY
                            ) {
                                _airPlayPlaybackState.value = null
                                _airPlayPlaybackFailure.value = null
                            }
                            _activeConnection.value = null
                            endStatsSession(com.phairplay.airplay.StreamStats.SOURCE_AIRPLAY)
                            updateNotification(
                                isRunning = state != ProtocolState.DISABLED && state != ProtocolState.ERROR,
                            )
                            _appleCastingState.value = when {
                                mirroring -> ProtocolState.CONNECTED
                                state == ProtocolState.DISABLED -> ProtocolState.DISABLED
                                else -> state
                            }
                            if (state == ProtocolState.DISABLED) _appleCastingDetail.value = null
                        }
                    }
                    refreshUpdateInstallSafety()
                }
            },
        )
        airPlayReceiver = receiver
        airPlayReceiverCallbackTicket = callbackTicket
        receiver.start()
        Logger.d("AirPlay receiver started (displayName='${settings.effectiveDisplayName}')")
    }

    /** Apply a receiver callback only while both its instance and callback generation still own state. */
    private fun withCurrentAirPlayReceiver(
        receiver: AirPlayReceiver,
        callbackTicket: Long,
        publishDuringPlaybackStop: Boolean = false,
        publish: () -> Unit,
    ) {
        receiverCallbackGate.runIfCurrent(callbackTicket) {
            if (!destroyed && airPlayReceiver === receiver &&
                airPlayReceiverCallbackTicket == callbackTicket
            ) {
                playbackStopGate.runCallback(publishDuringPlaybackStop, publish)
            }
        }
    }

    /**
     * Starts a fresh debug-overlay session for [source], clearing the previous one's counters.
     *
     * Keeps the counters of a live stream safe: this is the only media session Hearth runs
     * now, but the guard stays so a stale callback cannot end a session it does not own.
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

    /**
     * One full update pass: check → (maybe) download → verify/stage → (maybe) install → notify.
     * Scheduled execution is owned by WorkManager, not this foreground service's lifetime.
     */
    private suspend fun runUpdateCheck(
        settings: AppSettings,
        forceCheck: Boolean = false,
    ): UpdateCheck = com.phairplay.update.UpdateFlow.run(
        context = applicationContext,
        autoDownload = settings.autoDownloadUpdates,
        autoInstall = settings.autoInstallUpdates,
        forceCheck = forceCheck,
        onNotifyAvailable = { notifyUpdateAvailable(it) },
        onNotifyReady = { notifyUpdateReady(it) },
        onNotifyKeyMismatch = { notifyUpdateKeyMigrationRequired(it) },
    )

    /**
     * Settings → "Check for updates": one immediate check, respecting the download/install
     * preferences. Safe to call from the UI — the network work runs on [Dispatchers.IO].
     */
    suspend fun checkForUpdatesNow(): UpdateCheck {
        val settings = settingsRepository.settingsFlow.first()
        _updateChecking.value = true
        return try {
            runUpdateCheck(settings, forceCheck = true)
        } finally {
            _updateChecking.value = false
        }
    }

    /** Downloads an update the user just agreed to. */
    suspend fun downloadUpdate(info: UpdateInfo): StageResult =
        UpdateManager.get(applicationContext).downloadAndStage(info)

    /** Installs the staged update. */
    suspend fun installStagedUpdate(): com.phairplay.update.InstallStart =
        UpdateManager.get(applicationContext).installStaged()

    /** The download waiting to be installed, if any. */
    fun stagedUpdate(): StagedUpdate? = UpdateManager.get(applicationContext).stagedUpdate()

    /** Forgets the staged download. */
    fun discardStagedUpdate() = UpdateManager.get(applicationContext).clearDownload()

    private fun notifyUpdateAvailable(info: UpdateInfo) = UpdateNotifications.available(this, info)

    private fun notifyUpdateReady(info: UpdateInfo) = UpdateNotifications.ready(this, info)

    private fun notifyUpdateKeyMigrationRequired(info: UpdateInfo) =
        UpdateNotifications.keyMigrationRequired(this, info)

    private fun refreshUpdateInstallSafety() {
        val playback = _airPlayPlaybackState.value
        val active = _activeConnection.value != null || mirroring || _photoFrame.value != null ||
            _nowPlaying.value != null || (playback != null && playback !in setOf(
                com.phairplay.airplay.AirPlayPlaybackState.STOPPED,
                com.phairplay.airplay.AirPlayPlaybackState.DISCONNECTED,
            ))
        UpdateInstallSafety.setPlaybackActive(active)
    }

    private fun stopAllReceiversInternal(reason: ReceiverShutdownReason) {
        com.phairplay.airplay.AirPlayTrace.record(
            "Receiver shutdown requested (${reason.traceLabel})",
            kind = com.phairplay.airplay.AirPlayTrace.Kind.LIFECYCLE,
        )
        val receiver = airPlayReceiver
        airPlayReceiver = null // Ignore any callback still queued by this receiver before tearing it down.
        airPlayReceiverCallbackTicket = 0L
        try {
            receiver?.stop(reason)
        } catch (e: Exception) {
            Logger.w("AirPlay stop failed (${reason.name.lowercase()}, ${e.javaClass.simpleName})")
        }

        _airPlayDetail.value = null
        _airPlayPlaybackState.value = null
        _airPlayPlaybackFailure.value = null
        _airPlayState.value = ProtocolState.DISABLED
        _appleCastingState.value = ProtocolState.DISABLED
        _appleCastingDetail.value = null
        mirroring = false
        // AirPlayTrace is bounded and redacted; retain failures after Stop for an explicit export.
        _photoFrame.value = null
        _nowPlaying.value = null
        _registeredName.value = null
        refreshUpdateInstallSafety()
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
     * The notification shows the service status and provides distinct Stop playback,
     * Stop receiver and Restart actions.
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

        // Stop media without shutting down the receiver; Stop receiver remains a separate action.
        val stopPlaybackIntent = PendingIntent.getService(
            this, 3,
            Intent(this, PhairPlayService::class.java).apply { action = ACTION_STOP_PLAYBACK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop discovery/listeners and then stop the service.
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
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop_casting), stopPlaybackIntent)
            .addAction(R.drawable.ic_stop, getString(R.string.action_stop), stopIntent)
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

        /** Compatibility/display constant; scheduling is durable WorkManager work, not an exact timer. */
        const val UPDATE_CHECK_PERIOD_MS = 60 * 60 * 1000L
        const val ACTION_START    = "com.phairplay.action.START"
        const val ACTION_STOP     = "com.phairplay.action.STOP"
        const val ACTION_STOP_PLAYBACK = "com.phairplay.action.STOP_PLAYBACK"
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
