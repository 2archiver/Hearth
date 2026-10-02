package com.phairplay.util

import android.content.Context
import android.graphics.Rect
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.phairplay.settings.MirrorResolution

/**
 * DisplayCaps — reports what this TV can actually show and decode.
 *
 * WHAT: Two probes — the real panel resolution and the largest 16:9 H.264 size the hardware
 *       decoder claims to support.
 *
 * WHY:  Choosing the AirPlay mirroring size needs facts about the device, and Android only
 *       answers them through framework APIs. A 4K Google TV reports 3840x2160 here and gets a
 *       4K mirror; a 1080p TV reports 1920x1080 and never gets one it could not decode. Both
 *       probes are defensive: any failure (missing service, odd OEM codec list, security
 *       exception on a locked-down TV) falls back to 1080p rather than breaking mirroring.
 *
 * HOW:  Used by [com.phairplay.service.PhairPlayService] through
 *       [com.phairplay.settings.AppSettings.advertisedMirrorResolution]:
 *
 *   val panel  = DisplayCaps.panelSize(applicationContext)   // 3840x2160 on a 4K Google TV
 *   val decode = DisplayCaps.maxH264Size()                   // decoder ceiling
 *
 * The codec probe runs once (enumerating [MediaCodecList] costs ~100 ms); the panel probe is a
 * cheap WindowManager query.
 */
object DisplayCaps {

    /** 1080p — the mandatory baseline every Google TV can show and decode. */
    private const val SAFE_WIDTH = 1920
    private const val SAFE_HEIGHT = 1080

    /** Candidate sizes, ascending: the probe keeps the largest one the decoder accepts. */
    private val CANDIDATES = listOf(MirrorResolution.FHD, MirrorResolution.QHD, MirrorResolution.UHD)

    /** Decoder ceiling, probed on first use — the codec list never changes while we run. */
    private val decoderCeiling: Pair<Int, Int> by lazy { probeDecoder() }

    /**
     * Real panel resolution in pixels: 3840x2160 on a 4K Google TV, 1920x1080 on a 1080p one.
     *
     * Uses [WindowManager.getMaximumWindowMetrics] on Android 11+ (API 30) and the deprecated
     * `Display.getRealMetrics` on Android 10, which is this app's minSdk. Never throws: a failed
     * probe returns 1080p so mirroring keeps working.
     */
    fun panelSize(context: Context): Pair<Int, Int> {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val bounds: Rect? = when {
                wm == null -> null
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> wm.maximumWindowMetrics.bounds
                else -> legacyRealMetrics(wm)
            }
            val width = bounds?.width() ?: 0
            val height = bounds?.height() ?: 0
            when {
                wm == null -> fallback("no WindowManager")
                width <= 0 || height <= 0 -> fallback("panel reported ${width}x${height}")
                else -> Pair(width, height)
            }
        } catch (t: Throwable) {
            fallback("panel probe failed: $t")
        }
    }

    /**
     * Largest candidate H.264 size the hardware decoder supports, e.g. 3840x2160 on a 4K-capable
     * Google TV SoC, 1920x1080 on a decoder that tops out there.
     */
    fun maxH264Size(): Pair<Int, Int> = decoderCeiling

    private fun fallback(reason: String): Pair<Int, Int> {
        Logger.w("DisplayCaps: $reason — assuming ${SAFE_WIDTH}x$SAFE_HEIGHT")
        return Pair(SAFE_WIDTH, SAFE_HEIGHT)
    }

    /**
     * Android 10 (this app's minSdk) has no `WindowMetrics`; `Display.getRealMetrics` is the only
     * way to get the panel size there. Deprecated on newer APIs, hence the suppression — the
     * caller only reaches this branch below API 30.
     */
    @Suppress("DEPRECATION")
    private fun legacyRealMetrics(wm: WindowManager): Rect {
        val metrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    /**
     * Asks every registered decoder whether it supports each candidate size and keeps the
     * largest one any of them accepts. A device with several H.264 decoders (common: a hardware
     * decoder plus a software one) is judged by the best of them — MediaCodec picks the decoder
     * at configure time, and the software decoder would only be used as a last resort.
     */
    private fun probeDecoder(): Pair<Int, Int> {
        val supported = mutableSetOf<MirrorResolution>()
        try {
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                val caps = avcCapabilities(info) ?: continue
                supported += CANDIDATES.filter { caps.isSizeSupported(it.width, it.height) }
            }
        } catch (t: Throwable) {
            // Some OEM builds throw while enumerating codecs; never let that stop the receiver.
            return fallback("H.264 decoder probe failed: $t")
        }
        val best = supported.maxByOrNull { it.width } ?: MirrorResolution.FHD
        Logger.i("DisplayCaps: H.264 decode ceiling ${best.label} (${best.width}x${best.height})")
        return Pair(best.width, best.height)
    }

    /** H.264 (`video/avc`) capabilities of one codec, or null when it is not an H.264 decoder. */
    private fun avcCapabilities(info: MediaCodecInfo): MediaCodecInfo.VideoCapabilities? = try {
        if (info.supportedTypes.none { it.equals("video/avc", ignoreCase = true) }) {
            null
        } else {
            info.getCapabilitiesForType("video/avc").videoCapabilities
        }
    } catch (t: Throwable) {
        null    // a codec that cannot describe itself is simply not a candidate
    }
}
