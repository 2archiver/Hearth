package com.phairplay.airplay

/**
 * PhotoHandler — validates AirPlay photo endpoint payloads.
 *
 * AirPlay photo sharing sends an HTTP PUT to `/photo` with a JPEG or PNG body
 * and an HTTP DELETE to clear the displayed photo. This object keeps validation
 * separate from socket handling so malformed network input is easy to test.
 */
object PhotoHandler {
    const val PHOTO_PATH = "/photo"
    const val MAX_PHOTO_BYTES = 25 * 1024 * 1024
    const val MAX_ASSET_KEY_CHARS = 128
    // Asset keys are opaque; printable ASCII is safe because values are map keys only, never paths/log text.
    private val ASSET_KEY = Regex("""[\x21-\x7E]{1,$MAX_ASSET_KEY_CHARS}""")

    /** AirPlay Photos may cache an asset without displaying it, then select it by key later. */
    fun parseAssetAction(value: String?): PhotoAssetAction? = when (value?.trim()?.lowercase()) {
        null, "" -> PhotoAssetAction.DISPLAY
        "cacheonly" -> PhotoAssetAction.CACHE_ONLY
        "displaycached" -> PhotoAssetAction.DISPLAY_CACHED
        else -> null
    }

    fun isValidAssetKey(value: String?): Boolean =
        value == null || ASSET_KEY.matches(value)

    fun validatePhoto(bytes: ByteArray, contentType: String?): PhotoValidation {
        if (bytes.isEmpty()) {
            return PhotoValidation.Invalid("empty photo payload")
        }
        if (bytes.size > MAX_PHOTO_BYTES) {
            return PhotoValidation.Invalid("photo payload too large")
        }

        val detectedType = detectImageType(bytes)
            ?: return PhotoValidation.Invalid("unsupported image format")

        val declaredType = contentType?.substringBefore(";")?.trim()?.lowercase()
        if (declaredType != null &&
            declaredType.isNotEmpty() &&
            declaredType !in setOf(detectedType.mimeType, "application/octet-stream")
        ) {
            return PhotoValidation.Invalid("content type does not match image payload")
        }

        return PhotoValidation.Valid(detectedType)
    }

    private fun detectImageType(bytes: ByteArray): PhotoImageType? {
        if (bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() &&
            bytes[1] == 0xD8.toByte() &&
            bytes[2] == 0xFF.toByte()
        ) {
            return PhotoImageType.JPEG
        }

        if (bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() &&
            bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
        ) {
            return PhotoImageType.PNG
        }

        return null
    }
}

enum class PhotoAssetAction { DISPLAY, CACHE_ONLY, DISPLAY_CACHED }

data class PhotoPutRequest(
    val bytes: ByteArray,
    val contentType: String?,
    val assetKey: String?,
    val action: PhotoAssetAction,
    val transition: String?,
)

data class PhotoRequestResult(
    val statusCode: Int,
    val statusMessage: String,
    /** Short, value-free detail for the receiver log; never includes an asset key or body bytes. */
    val diagnostic: String,
) {
    companion object {
        val ACCEPTED = PhotoRequestResult(200, "OK", "accepted")
        val BAD_REQUEST = PhotoRequestResult(400, "Bad Request", "invalid request")
        val NOT_FOUND = PhotoRequestResult(404, "Not Found", "cached photo not found")
        val CONFLICT = PhotoRequestResult(409, "Conflict", "stale or stopped photo session")
        val STORAGE_FULL = PhotoRequestResult(507, "Insufficient Storage", "photo cache limit reached")
    }
}

enum class PhotoImageType(val mimeType: String) {
    JPEG("image/jpeg"),
    PNG("image/png")
}

sealed class PhotoValidation {
    data class Valid(val imageType: PhotoImageType) : PhotoValidation()
    data class Invalid(val reason: String) : PhotoValidation()
}
