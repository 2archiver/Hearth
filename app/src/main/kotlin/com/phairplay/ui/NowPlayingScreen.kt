package com.phairplay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.phairplay.R
import com.phairplay.airplay.NowPlayingInfo
import com.phairplay.util.Logger

/**
 * NowPlayingScreen — full-screen card shown while AirPlay audio plays without video (system audio
 * from a Mac, Apple Music, podcasts). Mirroring shows the video [StreamingScreen] instead; this
 * screen fills the otherwise-black surface for audio-only sessions.
 *
 * Layout (centered): album art (or an AirPlay-glyph placeholder when the sender sends no artwork),
 * track title, artist, album, and a "♪ Audio from <sender>" footer. Built programmatically to match
 * the other overlay views ([PhotoScreen]/[StreamingScreen]).
 */
class NowPlayingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private var lastArtwork: ByteArray? = null
    private val artwork: ImageView
    private val titleView: TextView
    private val artistView: TextView
    private val albumView: TextView
    private val senderView: TextView

    init {
        setBackgroundResource(R.drawable.hearth_hero)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), dp(48), dp(56), dp(48))
        }
        val artSize = minOf(280, (resources.configuration.screenHeightDp * 0.56f).toInt())
        artwork = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(artSize), dp(artSize)).apply {
                marginEnd = dp(44)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(color(R.color.background_surface))
                cornerRadius = dp(20).toFloat()
            }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        details.addView(textView(12f, R.color.accent_blue).apply {
            setText(R.string.now_playing_eyebrow)
            letterSpacing = 0.12f
            setPadding(0, 0, 0, dp(20))
        })
        titleView = textView(36f, R.color.text_primary, bold = true)
        artistView = textView(22f, R.color.text_secondary).apply { setPadding(0, dp(10), 0, 0) }
        albumView = textView(16f, R.color.text_tertiary).apply { setPadding(0, dp(6), 0, 0) }
        senderView = textView(14f, R.color.accent_blue).apply { setPadding(0, dp(28), 0, 0) }
        details.addView(titleView)
        details.addView(artistView)
        details.addView(albumView)
        details.addView(senderView)
        details.addView(textView(12f, R.color.text_secondary).apply {
            setText(R.string.now_playing_remote_hint)
            setPadding(0, dp(18), 0, 0)
        })
        row.addView(artwork)
        row.addView(details)
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    /** Updates the card to reflect [info]. Falls back to a placeholder glyph when no artwork. */
    fun update(info: NowPlayingInfo) {
        val bytes = info.artwork
        if (bytes !== lastArtwork || artwork.drawable == null) {
            lastArtwork = bytes
            val bitmap = bytes?.let { BoundedBitmap.decode(it, 1024) }
            if (bitmap != null) {
                artwork.scaleType = ImageView.ScaleType.CENTER_CROP
                artwork.setImageBitmap(bitmap)
                artwork.setColorFilter(null)
            } else {
                // No artwork from the sender (typical for raw system audio) — show the AirPlay glyph.
                artwork.scaleType = ImageView.ScaleType.CENTER_INSIDE
                artwork.setImageResource(R.drawable.ic_airplay)
                artwork.setColorFilter(color(R.color.text_secondary))
                if (info.artwork != null) {
                    Logger.w("NowPlayingScreen: artwork bytes (${info.artwork.size}B) failed to decode")
                }
            }

        }
        titleView.text = info.title ?: context.getString(R.string.now_playing_audio)
        artistView.setTextVisible(info.artist)
        albumView.setTextVisible(info.album)
        senderView.text = context.getString(R.string.now_playing_from, info.senderName)
    }

    /** Releases the (potentially large) artwork bitmap when the card is hidden. */
    fun clear() {
        lastArtwork = null
        artwork.setImageDrawable(null)
    }

    private fun TextView.setTextVisible(value: String?) {
        if (value.isNullOrBlank()) {
            visibility = View.GONE
        } else {
            text = value
            visibility = View.VISIBLE
        }
    }

    private fun textView(sizeSp: Float, colorRes: Int, bold: Boolean = false) = TextView(context).apply {
        setTextColor(color(colorRes))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.START
        maxLines = 2
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun color(res: Int): Int = try { context.getColor(res) } catch (_: Exception) { Color.WHITE }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

}
