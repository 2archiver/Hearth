package com.phairplay.cast.bridge

import com.phairplay.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * CastReceiverApp — the Cast V2 protocol logic: what to answer when a sender says
 * `CONNECT`, `GET_STATUS`, `LAUNCH`, `LOAD`, `PLAY`, `PAUSE`, `SEEK`, `STOP`.
 *
 * WHY: PhairPlay's Google Cast support used to be a thin wrapper around Google's Cast
 * Connect SDK, which refuses to start without a Cast Application ID issued by Google after
 * registering the app in the Cast SDK Developer Console. Without that ID the Cast card in
 * PhairPlay could only ever show an error. This class speaks the wire protocol a Chromecast
 * speaks, so "cast a video" works without any registration: the sender discovers us over
 * mDNS, opens the TLS channel on 8009 and sends `LOAD <url>`, which we hand to
 * [CastMediaPlayer].
 *
 * Deliberately permissive about the requested App ID: a real Chromecast only launches IDs
 * registered to it, but accepting any ID is what lets arbitrary sender apps (VLC, Plex,
 * Chrome, photo and file apps) cast to us. We echo the ID back in RECEIVER_STATUS, which is
 * all a sender checks.
 *
 * Pure protocol logic — no sockets, no Android framework beyond [CastMediaPlayer] — so it
 * can be unit tested with a fake [CastSink].
 */
internal class CastReceiverApp(
    private val displayName: String,
    private val player: CastPlayback,
    private val onVolumeChanged: (level: Double, muted: Boolean) -> Unit = { _, _ -> },
    /** Called with true when a sender launches an app and false when the session ends. */
    private val onSessionChanged: (Boolean) -> Unit = {}
) {

    /** A live Cast application session, mirroring what RECEIVER_STATUS reports. */
    private data class CastSession(
        val appId: String,
        val displayName: String,
        val sessionId: String,
        val transportId: String
    )

    private val lock = Any()

    /** Every connected sender channel, so RECEIVER_STATUS can be broadcast to all of them. */
    private val sinks = LinkedHashSet<CastSink>()

    @Volatile private var session: CastSession? = null
    @Volatile private var volumeLevel = 1.0
    @Volatile private var volumeMuted = false
    @Volatile private var mediaSessionId = 1
    @Volatile private var contentId: String? = null
    @Volatile private var contentType: String = "video/mp4"
    @Volatile private var streamType: String = "BUFFERED"
    @Volatile private var metadataJson: String? = null
    private var sessionCounter = 0

    /** True while a sender has an application session open. */
    fun hasSession(): Boolean = session != null

    /** Registers a sender channel. */
    fun addSink(sink: CastSink) {
        synchronized(lock) { sinks.add(sink) }
    }

    /** Unregisters a sender channel. */
    fun removeSink(sink: CastSink) {
        synchronized(lock) { sinks.remove(sink) }
    }

    /**
     * Routes one decoded [message].
     *
     * Never throws — a sender we do not understand must not take the bridge down, so an
     * unparseable body is logged and dropped.
     */
    fun handle(message: CastMessage) {
        when (message.namespace) {
            NS_CONNECTION -> handleConnection(message)
            NS_HEARTBEAT -> handleHeartbeat(message)
            NS_RECEIVER -> handleReceiver(message)
            NS_MEDIA -> handleMedia(message)
            else -> Logger.d("Cast: ignoring namespace ${message.namespace}")
        }
    }

    // ─── Namespaces ──────────────────────────────────────────────────────────

    private fun handleConnection(message: CastMessage) {
        val type = message.payloadUtf8?.messageType() ?: return
        when (type) {
            "CONNECT" -> {
                Logger.d("Cast: CONNECT from ${message.sourceId} → ${message.destinationId}")
                // Real receivers acknowledge a CONNECT; senders that do not wait for one
                // simply ignore this.
                sendTo(message.sourceId, NS_CONNECTION, JSONObject().put("type", "CONNECT"))
            }

            "CLOSE" -> Logger.d("Cast: CLOSE from ${message.sourceId}")
            else -> Logger.d("Cast: unhandled connection message type=$type")
        }
    }

    private fun handleHeartbeat(message: CastMessage) {
        if (message.payloadUtf8?.messageType() == "PING") {
            sendTo(message.sourceId, NS_HEARTBEAT, JSONObject().put("type", "PONG"))
        }
    }

    private fun handleReceiver(message: CastMessage) {
        val body = message.payloadUtf8?.toJsonObject() ?: return
        val requestId = body.optInt("requestId", 0)
        val sender = message.sourceId

        when (body.optString("type")) {
            "GET_STATUS" -> {
                Logger.d("Cast: GET_STATUS from $sender")
                sendTo(sender, NS_RECEIVER, receiverStatus(requestId))
            }

            "LAUNCH" -> {
                val appId = body.optString("appId").ifBlank { DEFAULT_APP_ID }
                Logger.i("Cast: LAUNCH appId=$appId from $sender")
                val opened = openSession(appId)
                if (opened) {
                    player.stop()
                }
                broadcast(NS_RECEIVER, receiverStatus(requestId))
            }

            "STOP" -> {
                Logger.i("Cast: STOP session from $sender")
                closeSession()
                broadcast(NS_RECEIVER, receiverStatus(requestId))
            }

            "SET_VOLUME" -> {
                val volume = body.optJSONObject("volume")
                if (volume != null) {
                    if (volume.has("level")) {
                        volumeLevel = volume.optDouble("level", volumeLevel).coerceIn(0.0, 1.0)
                    }
                    if (volume.has("muted")) {
                        volumeMuted = volume.optBoolean("muted", volumeMuted)
                    }
                    onVolumeChanged(volumeLevel, volumeMuted)
                }
                broadcast(NS_RECEIVER, receiverStatus(requestId))
            }

            "GET_APP_AVAILABILITY" -> {
                val ids = body.optJSONArray("appId")
                val availability = JSONObject()
                if (ids != null) {
                    for (i in 0 until ids.length()) {
                        availability.put(ids.optString(i, ""), "APP_AVAILABLE")
                    }
                }
                sendTo(
                    sender,
                    NS_RECEIVER,
                    JSONObject()
                        .put("type", "GET_APP_AVAILABILITY")
                        .put("requestId", requestId)
                        .put("availability", availability)
                )
            }

            else -> Logger.d("Cast: unhandled receiver message type=${body.optString("type")}")
        }
    }

    private fun handleMedia(message: CastMessage) {
        val active = session
        if (active == null) {
            Logger.w("Cast: media message with no running session — ignoring")
            return
        }
        if (message.destinationId != active.transportId && message.destinationId != RECEIVER_ID) {
            Logger.d("Cast: media message for ${message.destinationId}, session is ${active.transportId}")
        }

        val body = message.payloadUtf8?.toJsonObject() ?: return
        val requestId = body.optInt("requestId", 0)
        val sender = message.sourceId

        when (body.optString("type")) {
            "LOAD" -> {
                val media = body.optJSONObject("media")
                val url = media?.optString("contentId").orEmpty()
                if (url.isBlank()) {
                    Logger.e("Cast: LOAD without a contentId")
                    return
                }
                contentId = url
                contentType = media?.optString("contentType").orEmpty().ifBlank { "video/mp4" }
                streamType = media?.optString("streamType").orEmpty().ifBlank { "BUFFERED" }
                metadataJson = media?.optJSONObject("metadata")?.toString()
                mediaSessionId++
                val autoplay = body.optBoolean("autoplay", true)
                val start = body.optDouble("currentTime", 0.0).coerceAtLeast(0.0)
                Logger.i("Cast: LOAD $url (type=$contentType start=${start}s autoplay=$autoplay)")
                player.load(url, start, autoplay)
                sendTo(sender, NS_MEDIA, mediaStatus(requestId))
            }

            "PLAY" -> {
                Logger.d("Cast: PLAY")
                player.play()
                sendTo(sender, NS_MEDIA, mediaStatus(requestId))
            }

            "PAUSE" -> {
                Logger.d("Cast: PAUSE")
                player.pause()
                sendTo(sender, NS_MEDIA, mediaStatus(requestId))
            }

            "STOP" -> {
                Logger.d("Cast: media STOP")
                player.stop()
                sendTo(sender, NS_MEDIA, mediaStatus(requestId))
            }

            "SEEK" -> {
                val position = body.optDouble("currentTime", 0.0)
                Logger.d("Cast: SEEK to ${position}s")
                player.seek(position)
                sendTo(sender, NS_MEDIA, mediaStatus(requestId))
            }

            "GET_STATUS" -> {
                sendTo(sender, NS_MEDIA, mediaStatus(requestId))
            }

            "SET_VOLUME", "SET_PLAYBACK_RATE" -> {
                sendTo(sender, NS_MEDIA, mediaStatus(requestId))
            }

            else -> Logger.d("Cast: unhandled media message type=${body.optString("type")}")
        }
    }

    /** Broadcasts the current media status, e.g. after the player changes state on its own. */
    fun broadcastMediaStatus() {
        broadcast(NS_MEDIA, mediaStatus(0))
    }

    // ─── Sessions ────────────────────────────────────────────────────────────

    private fun openSession(appId: String): Boolean {
        val existing = session
        if (existing != null && existing.appId == appId) return false
        sessionCounter++
        val newSession = CastSession(
            appId = appId,
            displayName = displayName,
            sessionId = UUID.randomUUID().toString(),
            transportId = "web-$sessionCounter"
        )
        session = newSession
        onSessionChanged(true)
        Logger.i("Cast: session ${newSession.sessionId} opened for $appId on ${newSession.transportId}")
        return true
    }

    private fun closeSession() {
        session = null
        player.stop()
        onSessionChanged(false)
        Logger.i("Cast: session closed")
    }

    /**
     * Opens a session for [appId].
     *
     * Called from the castv2 `LAUNCH` handler and from the DIAL server (`POST /apps/<id>`),
     * because some sender apps launch over DIAL and only then open the castv2 channel.
     */
    fun launch(appId: String) {
        openSession(appId)
        player.stop()
        onSessionChanged(true)
        broadcast(NS_RECEIVER, receiverStatus(0))
    }

    /** Ends the current session (castv2 `STOP` and DIAL `DELETE`). */
    fun stopSession() {
        closeSession()
        broadcast(NS_RECEIVER, receiverStatus(0))
    }

    // ─── Payload builders ────────────────────────────────────────────────────

    private fun receiverStatus(requestId: Int): JSONObject {
        val applications = JSONArray()
        session?.let { s ->
            applications.put(
                JSONObject()
                    .put("appId", s.appId)
                    .put("displayName", s.displayName)
                    .put("sessionId", s.sessionId)
                    .put("statusText", s.displayName)
                    .put("transportId", s.transportId)
                    .put("isIdleScreen", false)
                    .put("launchedFromCloud", false)
                    .put(
                        "namespaces",
                        JSONArray().apply {
                            put(JSONObject().put("name", NS_CONNECTION))
                            put(JSONObject().put("name", NS_HEARTBEAT))
                            put(JSONObject().put("name", NS_RECEIVER))
                            put(JSONObject().put("name", NS_MEDIA))
                        }
                    )
            )
        }

        val status = JSONObject()
            .put("userEq", JSONObject())
            .put(
                "volume",
                JSONObject()
                    .put("controlType", "attenuation")
                    .put("level", volumeLevel)
                    .put("muted", volumeMuted)
                    .put("stepInterval", 0.05)
            )
            .put("applications", applications)
            .put("isActiveInput", true)
            .put("isStandBy", false)
            .put("displayName", displayName)

        val out = JSONObject()
            .put("type", "RECEIVER_STATUS")
            .put("status", status)
        if (requestId != 0) out.put("requestId", requestId)
        return out
    }

    private fun mediaStatus(requestId: Int): JSONObject {
        val state = if (contentId == null) {
            CastMediaPlayer.State.IDLE
        } else {
            player.currentState()
        }
        // CastMediaPlayer.State values are the protocol's own strings.

        val media = JSONObject()
            .put("contentId", contentId ?: "")
            .put("contentType", contentType)
            .put("streamType", streamType)
            .put("duration", player.durationSec())
        metadataJson?.let { raw ->
            runCatching { media.put("metadata", JSONObject(raw)) }
        }

        val item = JSONObject()
            .put("mediaSessionId", mediaSessionId)
            .put("media", media)
            .put("playbackRate", 1)
            .put("playerState", state)
            .put("currentTime", player.positionSec())
            .put("supportedMediaCommands", SUPPORTED_MEDIA_COMMANDS)
            .put(
                "volume",
                JSONObject()
                    .put("level", volumeLevel)
                    .put("muted", volumeMuted)
                    .put("controlType", "attenuation")
            )
            .put("currentItemId", 1)
            .put("extendedStatus", JSONObject())
        if (state == CastMediaPlayer.State.IDLE) {
            item.put("idleReason", "FINISHED")
        }

        val out = JSONObject()
            .put("type", "MEDIA_STATUS")
            .put("status", JSONArray().put(item))
        if (requestId != 0) out.put("requestId", requestId)
        return out
    }

    // ─── Sending ─────────────────────────────────────────────────────────────

    private fun sendTo(destinationId: String, namespace: String, payload: JSONObject) {
        val message = CastMessage(
            sourceId = sourceIdFor(namespace),
            destinationId = destinationId,
            namespace = namespace,
            payloadUtf8 = payload.toString()
        )
        val targets: List<CastSink> = synchronized(lock) { sinks.toList() }
        for (sink in targets) {
            runCatching { sink.send(message) }
                .onFailure { Logger.w("Cast: send to $destinationId failed: ${it.message}") }
        }
    }

    private fun broadcast(namespace: String, payload: JSONObject) {
        val message = CastMessage(
            sourceId = sourceIdFor(namespace),
            destinationId = "*",
            namespace = namespace,
            payloadUtf8 = payload.toString()
        )
        val targets: List<CastSink> = synchronized(lock) { sinks.toList() }
        for (sink in targets) {
            runCatching { sink.send(message) }
                .onFailure { Logger.w("Cast: broadcast failed: ${it.message}") }
        }
    }

    /** Media replies come from the app's transport; everything else from the receiver. */
    private fun sourceIdFor(namespace: String): String =
        if (namespace == NS_MEDIA) session?.transportId ?: RECEIVER_ID else RECEIVER_ID

    private fun String.messageType(): String? = toJsonObject()?.optString("type")

    private fun String.toJsonObject(): JSONObject? = try {
        JSONObject(this)
    } catch (e: Exception) {
        Logger.w("Cast: could not parse payload: ${take(120)}")
        null
    }

    companion object {
        const val RECEIVER_ID = "receiver-0"

        /** Connection handshake / virtual channels. */
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        /** Keep-alive (PING/PONG). */
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        /** Receiver control: GET_STATUS, LAUNCH, STOP, SET_VOLUME. */
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        /** Media transport: LOAD, PLAY, PAUSE, SEEK, STOP, GET_STATUS. */
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

        /** The Default Media Receiver — what senders ask for when casting a plain media URL. */
        const val DEFAULT_APP_ID = "CC1AD845"

        /** Mirrors what a real Chromecast reports (pause, seek, volume, mute, skip, queue…). */
        private const val SUPPORTED_MEDIA_COMMANDS = 274447
    }
}

/** A connection a Cast sender has opened to us. */
internal interface CastSink {
    fun send(message: CastMessage)
}
