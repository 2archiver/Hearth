package com.phairplay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
import android.widget.FrameLayout
import android.widget.TextView
import com.phairplay.R
import com.phairplay.airplay.StreamStats
import com.phairplay.util.Logger
import kotlin.math.max

/**
 * StreamingScreen — Full-screen view that displays the AirPlay video stream.
 *
 * WHY: The decoded video from MediaCodec must be rendered to a [Surface].
 * A [SurfaceView] provides a dedicated, hardware-accelerated drawing surface
 * that can receive MediaCodec output directly — no intermediate bitmap copies.
 * This is the lowest-latency way to display video on Android.
 *
 * HOW: Add this view to the streaming_container in activity_main.xml.
 * Call [getSurface] to get the Surface to pass to [VideoDecoder.initialize].
 * The Surface is valid as long as this view is attached to the window.
 *
 * IMPORTANT: The Surface becomes available asynchronously after the view is
 * laid out. [getSurface] returns null if called before the Surface is ready.
 * [VideoDecoder.initialize] must not be called until [getSurface] returns non-null.
 *
 * DEBUG OVERLAY (2.1.0)
 * ---------------------
 * The optional HUD is anchored to the **top-left of the TV safe area** (5% overscan margin, with a
 * 24dp floor) and is only laid out while Settings → "Debug overlay" is on. It is deliberately not
 * focusable and hidden from accessibility services: a D-pad press must never land on the HUD
 * instead of a control, and a screen reader must not announce a value that changes every second.
 *
 * Its text comes from [StreamStats.summary], which labels the *presented* frame rate separately
 * from the sender cadence and from RX packet throughput. 2.0 rendered `rx=…/s` and called it FPS;
 * that number never claimed a frame had reached the screen.
 *
 * [REFRESH_MS] is 1 s: a HUD that repaints four times a second churns the main thread for no
 * benefit, and none of these counters are meaningful at sub-second resolution.
 */
class StreamingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // The SurfaceView that provides the hardware-accelerated rendering surface
    private val surfaceView: SurfaceView = SurfaceView(context)

    // The Surface is created asynchronously by SurfaceView — stored here when ready
    private var surface: Surface? = null

    // Optional debug HUD (Settings → "Debug overlay"), drawn on top of the video.
    private val debugView = TextView(context).apply {
        setTextColor(Color.parseColor("#FFD9F5E2"))
        background = context.getDrawable(R.drawable.debug_overlay_background)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.MONOSPACE
        setPadding(dp(16), dp(12), dp(16), dp(12))
        setLineSpacing(dp(2).toFloat(), 1f)
        includeFontPadding = false
        visibility = GONE
        // The HUD is a readout, never a control: no focus, no clicks, no accessibility node.
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        // SurfaceView is composited in its own layer *below* the window, so a sibling normally
        // draws above it — but on some TV stacks (and after a decoder re-configure) the video
        // layer ends up in front. An explicit elevation wins in every case.
        elevation = 1000f
    }
    // Last applied surface size, so we only re-layout on an actual change (rotation/resolution switch).
    private var lastSurfaceW = Int.MIN_VALUE
    private var lastSurfaceH = Int.MIN_VALUE

    /** Last text handed to the HUD; re-assigning identical text still dirties the view. */
    private var lastOverlayText: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            applyAspectFit()
            updateOverlay()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        // Black backing so the letterbox/pillarbox bars (when the video is sized to its aspect ratio
        // and doesn't fill 16:9) are black — without this, those margins are transparent and the home
        // menu shows through behind the streaming overlay. The SurfaceView punches its own hole on top.
        setBackgroundColor(Color.BLACK)

        // SurfaceView is centred; its size is set to the video's aspect ratio by applyAspectFit().
        addView(surfaceView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })

        // Debug HUD overlay, top-left of the safe area, above the video surface.
        addView(debugView, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START })

        // Register a callback to track when the Surface is created/destroyed
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Surface is now ready — store reference for VideoDecoder
                surface = holder.surface
                Logger.d("StreamingScreen: Surface created")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Called when the surface size changes (e.g., resolution change)
                // MediaCodec handles this automatically — no action needed here
                Logger.d("StreamingScreen: Surface changed ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Surface is gone (e.g., screen turned off) — VideoDecoder must stop
                surface = null
                Logger.d("StreamingScreen: Surface destroyed")
            }
        })
    }

    /**
     * Returns the [Surface] where decoded video frames will be rendered.
     *
     * Returns null if the Surface has not been created yet (the view hasn't
     * been laid out) or if it has been destroyed. The caller must check for
     * null before passing this to [VideoDecoder.initialize].
     *
     * @return The rendering Surface, or null if not yet available.
     */
    fun getSurface(): Surface? = surface

    /**
     * Sizes the SurfaceView to the decoded video's aspect ratio (letterbox/pillarbox) instead of
     * stretching it to fill 16:9. Without this, a portrait phone stream is squashed horizontally.
     * Falls back to filling the container when the size isn't known yet.
     */
    private fun applyAspectFit() {
        val vw = StreamStats.videoWidth
        val vh = StreamStats.videoHeight
        val cw = width
        val ch = height
        val (targetW, targetH) = if (vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) {
            LayoutParams.MATCH_PARENT to LayoutParams.MATCH_PARENT
        } else {
            val videoRatio = vw.toFloat() / vh
            val containerRatio = cw.toFloat() / ch
            if (videoRatio > containerRatio) cw to (cw / videoRatio).toInt()   // fit width, bars top/bottom
            else (ch * videoRatio).toInt() to ch                              // fit height, bars left/right
        }
        if (targetW == lastSurfaceW && targetH == lastSurfaceH) return
        lastSurfaceW = targetW
        lastSurfaceH = targetH
        surfaceView.layoutParams = (surfaceView.layoutParams as LayoutParams).apply {
            width = targetW; height = targetH; gravity = Gravity.CENTER
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applySafeAreaMargins(w, h)
        applyAspectFit()
    }

    /**
     * Anchors the HUD inside the TV safe area.
     *
     * WHY NOT A FIXED 40px: 2.0 used a 40px offset, which is ~10mm on a 720p panel (inside the
     * bezel on some sets, clipped on others) but a less than 1% margin on a 4K panel. A
     * proportional margin with a dp floor is legible at 720p, 1080p and 4K without landing in the
     * overscan region.
     */
    private fun applySafeAreaMargins(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val left = max(dp(24), (width * SAFE_AREA_FRACTION).toInt())
        val top = max(dp(24), (height * SAFE_AREA_FRACTION).toInt())
        val params = debugView.layoutParams as LayoutParams
        val available = (width - left * 2).coerceAtLeast(dp(240))
        if (params.leftMargin == left && params.topMargin == top && debugView.maxWidth == available) {
            return
        }
        params.leftMargin = left
        params.topMargin = top
        debugView.layoutParams = params
        // A long hardware decoder name must wrap, not run off the right edge of the screen.
        debugView.maxWidth = available
    }

    /** Shows/updates/hides the HUD. Cheap when disabled: at most a visibility check per second. */
    private fun updateOverlay() {
        if (!StreamStats.overlayEnabled) {
            if (debugView.visibility != GONE) {
                debugView.visibility = GONE
                lastOverlayText = null
            }
            return
        }
        if (debugView.visibility != VISIBLE) {
            debugView.visibility = VISIBLE
            // SurfaceView lives in its own layer; re-assert the HUD above it on every show so a
            // decoder re-configure mid-session cannot bury the readout behind the video.
            debugView.bringToFront()
        }
        val text = StreamStats.summary()
        if (text != lastOverlayText) {
            debugView.text = text
            lastOverlayText = text
        }
    }

    private fun updateDisplayRefreshRate() {
        // Reported as "panel Hz" by the summary. It is a display capability, never an fps:
        // a 60 Hz panel says nothing about how many frames were presented.
        StreamStats.displayRefreshHz = try {
            display?.refreshRate ?: 0f
        } catch (_: Exception) {
            0f
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateDisplayRefreshRate()
        applySafeAreaMargins(width, height)
        handler.post(tick)                 // drive aspect-fit + debug HUD
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        lastOverlayText = null
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        /** HUD refresh period. 1 Hz is the frequency at which these counters are meaningful. */
        private const val REFRESH_MS = 1_000L

        /** Broadcast-style safe area: keep the HUD out of the overscan region on every panel. */
        private const val SAFE_AREA_FRACTION = 0.05f
    }
}
