package com.phairplay.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.phairplay.util.Logger

/**
 * BoundedBitmap decodes sender-supplied images with a fixed pixel budget.
 * It keeps high-resolution photo/cover-art payloads from exhausting a TV's heap.
 * Use decode(bytes, maxEdge) before displaying an AirPlay image.
 */
internal object BoundedBitmap {
    /** Read dimensions first; cap both decoded edges before allocating the actual bitmap. */
    fun decode(bytes: ByteArray, maxEdge: Int): Bitmap? {
        if (bytes.isEmpty() || maxEdge <= 0) return null
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            var sample = 1
            // Keep the divisor positive even for malformed dimensions near Int.MAX_VALUE.
            while (options.outWidth / sample > maxEdge || options.outHeight / sample > maxEdge) {
                if (sample >= (1 shl 30)) break
                sample *= 2
            }
            options.inJustDecodeBounds = false
            options.inSampleSize = sample
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (error: OutOfMemoryError) {
            Logger.w("AirPlay image exceeds available memory; keeping the receiver alive")
            null
        } catch (error: RuntimeException) {
            Logger.w("AirPlay image could not be decoded: ${error.javaClass.simpleName}")
            null
        }
    }
}
