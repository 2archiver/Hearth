package com.phairplay.ui

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import com.phairplay.util.Logger

/**
 * PhotoScreen — Full-screen ImageView for AirPlay `/photo` sharing.
 */
class PhotoScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val imageView = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    init {
        setBackgroundColor(android.graphics.Color.BLACK)
        addView(
            imageView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
        )
    }

    fun showPhoto(bytes: ByteArray): Boolean {
        val bitmap = BoundedBitmap.decode(bytes, 2048)
        if (bitmap == null) {
            Logger.w("PhotoScreen: BitmapFactory rejected photo payload")
            return false
        }

        imageView.setImageBitmap(bitmap)
        return true
    }

    fun clearPhoto() {
        imageView.setImageDrawable(null)
    }
}
