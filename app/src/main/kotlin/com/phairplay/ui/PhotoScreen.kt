package com.phairplay.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Full-screen, bounded AirPlay Photos still-image view. */
class PhotoScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val imageView = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = context.getString(com.phairplay.R.string.photo_screen_description)
    }
    private val decodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var displayGeneration = 0L
    private var decodeJob: Job? = null

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(
            imageView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
        )
    }

    /** Starts a cancellable background decode; a stale completion can never replace a newer photo. */
    fun showPhoto(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val generation = ++displayGeneration
        decodeJob?.cancel()
        clearDisplayedBitmap()
        // Service-owned PhotoFrame byte arrays are immutable after publication; retain the
        // reference rather than copying up to 25 MiB on the main thread.
        val imageData = bytes
        decodeJob = decodeScope.launch {
            var decodedBitmap: Bitmap? = null
            val bitmap = try {
                // BitmapFactory's native decode is not cooperatively cancellable. withContext waits
                // for it to finish before returning cancellation, so retain the result long enough
                // to recycle it if cancellation wins before the bitmap reaches the view.
                withContext(Dispatchers.Default) {
                    BoundedBitmap.decode(imageData, MAX_IMAGE_EDGE).also { decodedBitmap = it }
                }
            } catch (cancelled: CancellationException) {
                decodedBitmap?.takeUnless { it.isRecycled }?.recycle()
                throw cancelled
            }
            if (generation != displayGeneration) {
                bitmap?.recycle()
                return@launch
            }
            if (bitmap == null) {
                Logger.w("PhotoScreen: photo decode failed; ignoring this still")
                return@launch
            }
            imageView.setImageBitmap(bitmap)
        }
        return true
    }

    /** Invalidates an in-flight decode before clearing the displayed drawable. */
    fun clearPhoto() {
        displayGeneration++
        decodeJob?.cancel()
        decodeJob = null
        clearDisplayedBitmap()
    }

    private fun clearDisplayedBitmap() {
        val oldBitmap = (imageView.drawable as? BitmapDrawable)?.bitmap
        imageView.setImageDrawable(null)
        // This view owns decoded bitmaps; recycle promptly instead of retaining a large image until GC.
        if (oldBitmap != null && !oldBitmap.isRecycled) oldBitmap.recycle()
    }

    private companion object {
        const val MAX_IMAGE_EDGE = 2048
    }
}
