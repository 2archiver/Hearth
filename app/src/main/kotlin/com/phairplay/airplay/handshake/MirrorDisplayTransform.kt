package com.phairplay.airplay.handshake

import kotlin.math.min

/** Maps TextureView's default viewport-sized image to aspect-fit, oriented video pixels.
 * Rotation acts on the texture, never on a separately composited SurfaceView layer.
 * Flips are in source coordinates, before the clockwise display rotation.
 */
internal object MirrorDisplayTransform {
    fun matrix(videoWidth: Int, videoHeight: Int, viewportWidth: Int, viewportHeight: Int,
        orientation: MirrorOrientation): FloatArray {
        if (videoWidth <= 0 || videoHeight <= 0 || viewportWidth <= 0 || viewportHeight <= 0) {
            return floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        }
        val rotation = ((orientation.clockwiseDegrees % 360) + 360) % 360
        val quarterTurn = rotation == 90 || rotation == 270
        val displayWidth = if (quarterTurn) videoHeight else videoWidth
        val displayHeight = if (quarterTurn) videoWidth else videoHeight
        val fit = min(viewportWidth.toFloat() / displayWidth, viewportHeight.toFloat() / displayHeight)
        val sx = fit * videoWidth / viewportWidth * if (orientation.horizontalFlip) -1f else 1f
        val sy = fit * videoHeight / viewportHeight * if (orientation.verticalFlip) -1f else 1f
        val (cos, sin) = when (rotation) {
            90 -> 0f to 1f
            180 -> -1f to 0f
            270 -> 0f to -1f
            else -> 1f to 0f
        }
        val a = cos * sx
        val b = -sin * sy
        val c = sin * sx
        val d = cos * sy
        val cx = viewportWidth / 2f
        val cy = viewportHeight / 2f
        return floatArrayOf(a, b, cx - a * cx - b * cy,
            c, d, cy - c * cx - d * cy, 0f, 0f, 1f)
    }
}
