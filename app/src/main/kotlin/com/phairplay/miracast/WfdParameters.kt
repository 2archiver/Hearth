package com.phairplay.miracast

/**
 * WfdParameters — the Wi-Fi Display capability strings PhairPlay publishes, and the
 * `text/parameters` body handling behind RTSP `GET_PARAMETER` / `SET_PARAMETER` (the M3–M5
 * exchange).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * The old code answered every `GET_PARAMETER` with one fixed blob and ignored what the source
 * actually asked for. A WFD source does not read that as "here is everything" — it asked for a
 * specific list and validates the reply against it. Windows in particular asks for
 * `wfd_uibc_capability` (User Input Back Channel) and `wfd_standby_resume_capability`, and
 * drops the session when the answer is missing. It also sends a second `OPTIONS` and checks the
 * `Public:` header for the methods it is about to use.
 *
 * So three concrete failures, all fixed here:
 *  1. Only the requested parameters are returned, in the order requested (empty request → all).
 *  2. `wfd_uibc_capability`, `wfd_standby_resume_capability`, `wfd_3d_video_formats`,
 *     `wfd_idr_request_capability` and `wfd_route` are answered instead of omitted.
 *  3. `wfd_video_formats` was `… 02 10 …` — 2 is not a defined H.264 profile and 10 is not a
 *     defined level, so a validating source could reject the whole list. It now reports
 *     Constrained Baseline / level 4.2, which covers the 1080p60 the CEA bitmap advertises.
 *
 * Pure string handling — no sockets, no Android framework — so the negotiation is unit tested
 * on the plain JVM.
 *
 * Reference: Wi-Fi Display Technical Specification (M3 request / M3 response), and the worked
 * M3 examples in Microsoft's [MS-WFDPE].
 */
internal object WfdParameters {

    /**
     * Every parameter PhairPlay offers, in the order they are reported.
     *
     * Deliberately conservative: `none` everywhere we do not actually implement something.
     * Advertising a capability we cannot honour — HDCP, 3D, a coupled secondary sink — is how a
     * negotiation succeeds and the session then fails in a way nobody can diagnose.
     */
    val SUPPORTED: LinkedHashMap<String, String> = linkedMapOf(
        // 48 kHz / 16-bit / 2-channel LPCM is the mandatory WFD format (00000003); AAC
        // (00000007) is offered because plenty of sources prefer it.
        "wfd_audio_codecs" to "LPCM 00000003 00, AAC 00000007 00",
        // <native> <preferred-display-mode> <profile> <level> <cea> <vesa> <hh> <latency>
        // <min-slice> <slice-enc> <frame-rate-control> <max-hres> <max-vres>
        //   01 = Constrained Baseline, 04 = level 4.2, 0001FFFF = every CEA mode up to 1080p24
        "wfd_video_formats" to "00 00 01 04 0001FFFF 00000000 00000000 00 0000 0000 00 none none",
        "wfd_3d_video_formats" to "none",
        "wfd_content_protection" to "none",
        "wfd_display_edid" to "none",
        "wfd_coupled_sink" to "none",
        // Interleaved over the RTSP TCP connection: the only transport this receiver reads.
        "wfd_client_rtp_ports" to "RTP/AVP/TCP;unicast 0 0 mode=play",
        // No User Input Back Channel — we do not forward remote keys back to the source.
        "wfd_uibc_capability" to "none",
        "wfd_standby_resume_capability" to "none",
        "wfd_connector_type" to "05",
        "wfd_idr_request_capability" to "none",
        "wfd_route" to "none",
        "wfd_I2C" to "none"
    )

    /**
     * Parameters the **source** owns. Whatever it sends in an M4 `SET_PARAMETER` is echoed back
     * rather than replaced with our default — a presentation URL is the source's to choose.
     */
    private val SOURCE_DRIVEN: Set<String> = setOf(
        "wfd_presentation_URL",
        "wfd_trigger_method"
    )

    /**
     * Splits a `text/parameters` body into `name → value` pairs.
     *
     * A `GET_PARAMETER` body lists bare parameter names (empty value) — that is a request for
     * those parameters, not a claim that they are empty. A `SET_PARAMETER` body carries
     * `name: value` lines.
     */
    fun parse(body: String): List<Pair<String, String>> {
        if (body.isBlank()) return emptyList()
        val result = ArrayList<Pair<String, String>>()
        for (rawLine in body.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val separator = line.indexOf(':')
            if (separator < 0) {
                result += line to ""
            } else {
                result += line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
        }
        return result
    }

    /**
     * Builds the response body for one `GET_PARAMETER`.
     *
     * @param requestBody   the request's `text/parameters` body (bare parameter names).
     * @param sourceValues  parameters the source has already set via `SET_PARAMETER`.
     * @return a `text/parameters` body answering exactly the requested names, or every
     *         parameter we publish when the request named none.
     */
    fun renderResponse(requestBody: String, sourceValues: Map<String, String> = emptyMap()): String {
        val requested = parse(requestBody).map { it.first }
        val names = if (requested.isEmpty()) SUPPORTED.keys.toList() else requested
        val out = StringBuilder()
        for (name in names) {
            val value = sourceValues[name]
                ?: SUPPORTED[name]
                ?: if (name in SOURCE_DRIVEN) "" else "none"
            out.append(name).append(": ").append(value).append("\r\n")
        }
        return out.toString()
    }

    /**
     * Folds a `SET_PARAMETER` body into [sourceValues], keeping only the parameters the source
     * is allowed to drive.
     *
     * We never accept the source's `wfd_video_formats` / `wfd_audio_codecs` /
     * `wfd_client_rtp_ports`: echoing those back would promise a codec or transport this
     * receiver does not implement, and the source would then stream in a format we drop.
     */
    fun applySetParameter(
        body: String,
        sourceValues: MutableMap<String, String>
    ): List<Pair<String, String>> {
        val applied = ArrayList<Pair<String, String>>()
        for ((name, value) in parse(body)) {
            if (name !in SOURCE_DRIVEN) continue
            sourceValues[name] = value
            applied += name to value
        }
        return applied
    }

    /** The `Public:` header for our OPTIONS reply — every method this receiver implements. */
    val PUBLIC_METHODS: String =
        "org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER"
}
