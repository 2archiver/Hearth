package com.phairplay.airplay

/**
 * ReceiverShutdownReason — identifies why the whole AirPlay receiver is being torn down.
 *
 * WHY: media Stop must not be confused with closing discovery/listener sockets, and shutdown traces
 * need the initiating command before socket closure can produce its own expected exceptions.
 * Use [traceLabel] in bounded diagnostics; it contains no sender-provided data.
 */
enum class ReceiverShutdownReason(val traceLabel: String) {
    USER_REQUESTED("user requested Stop receiver"),
    APP_TASK_REMOVED("app task removed"),
    RESTART("receiver restart requested"),
    SERVICE_DESTROYED("service destroyed"),
    UNSPECIFIED("unspecified receiver shutdown"),
}
