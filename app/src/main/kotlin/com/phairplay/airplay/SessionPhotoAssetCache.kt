package com.phairplay.airplay

import java.util.LinkedHashMap

/** Immutable value returned from the bounded AirPlay Photos cache. */
data class CachedPhotoAsset(val bytes: ByteArray, val imageType: PhotoImageType)

/**
 * LRU cache keyed by both AirPlay session generation and X-Apple-AssetKey.
 *
 * The two-level LRU prevents `displayCached` from crossing sender sessions and bounds memory even
 * when a sender streams many images. Bodies are copied on both insertion and lookup so a decoded
 * view or request parser cannot mutate bytes retained by the cache.
 */
internal class SessionPhotoAssetCache(
    private val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    private val maxAssetsPerSession: Int = DEFAULT_MAX_ASSETS_PER_SESSION,
    private val maxBytes: Int = DEFAULT_MAX_CACHE_BYTES,
) {
    private data class Stored(val bytes: ByteArray, val imageType: PhotoImageType)
    private class SessionEntries {
        val assets = LinkedHashMap<String, Stored>(16, 0.75f, true)
    }

    private val lock = Any()
    private val sessions = LinkedHashMap<String, SessionEntries>(8, 0.75f, true)
    private var byteCount = 0

    fun put(sessionKey: String, assetKey: String, bytes: ByteArray, imageType: PhotoImageType): Boolean =
        synchronized(lock) {
            if (sessionKey.isBlank() || sessionKey.length > MAX_SESSION_KEY_CHARS ||
                !PhotoHandler.isValidAssetKey(assetKey) || assetKey.isBlank() ||
                bytes.isEmpty() || bytes.size > PhotoHandler.MAX_PHOTO_BYTES ||
                maxSessions <= 0 || maxAssetsPerSession <= 0 || maxBytes <= 0 || bytes.size > maxBytes
            ) return@synchronized false

            val session = sessions[sessionKey] ?: run {
                while (sessions.size >= maxSessions) evictOldestSessionLocked()
                SessionEntries().also { sessions[sessionKey] = it }
            }
            session.assets.remove(assetKey)?.let { byteCount -= it.bytes.size }
            session.assets[assetKey] = Stored(bytes.copyOf(), imageType)
            byteCount += bytes.size

            while (session.assets.size > maxAssetsPerSession) {
                val oldest = session.assets.entries.iterator().next()
                byteCount -= oldest.value.bytes.size
                session.assets.remove(oldest.key)
            }
            while (byteCount > maxBytes) evictOldestAssetLocked()
            sessions[sessionKey]?.assets?.containsKey(assetKey) == true
        }

    fun get(sessionKey: String, assetKey: String): CachedPhotoAsset? = synchronized(lock) {
        if (!PhotoHandler.isValidAssetKey(assetKey)) return@synchronized null
        val stored = sessions[sessionKey]?.assets?.get(assetKey) ?: return@synchronized null
        CachedPhotoAsset(stored.bytes.copyOf(), stored.imageType)
    }

    fun clearSession(sessionKey: String) = synchronized(lock) {
        sessions.remove(sessionKey)?.assets?.values?.forEach { byteCount -= it.bytes.size }
        byteCount = byteCount.coerceAtLeast(0)
    }

    fun clear() = synchronized(lock) {
        sessions.clear()
        byteCount = 0
    }

    internal fun stats(): Stats = synchronized(lock) {
        Stats(
            sessions = sessions.size,
            assets = sessions.values.sumOf { it.assets.size },
            bytes = byteCount,
        )
    }

    internal data class Stats(val sessions: Int, val assets: Int, val bytes: Int)

    private fun evictOldestSessionLocked() {
        val iterator = sessions.entries.iterator()
        if (!iterator.hasNext()) return
        val oldest = iterator.next()
        byteCount -= oldest.value.assets.values.sumOf { it.bytes.size }
        iterator.remove()
    }

    private fun evictOldestAssetLocked() {
        val sessionsIterator = sessions.entries.iterator()
        while (sessionsIterator.hasNext()) {
            val session = sessionsIterator.next()
            val assetsIterator = session.value.assets.entries.iterator()
            if (assetsIterator.hasNext()) {
                val oldest = assetsIterator.next()
                byteCount -= oldest.value.bytes.size
                assetsIterator.remove()
                if (session.value.assets.isEmpty()) sessionsIterator.remove()
                return
            }
            sessionsIterator.remove()
        }
    }

    companion object {
        const val DEFAULT_MAX_SESSIONS = 4
        const val DEFAULT_MAX_ASSETS_PER_SESSION = 16
        const val DEFAULT_MAX_CACHE_BYTES = 32 * 1024 * 1024
        private const val MAX_SESSION_KEY_CHARS = 128
    }
}
