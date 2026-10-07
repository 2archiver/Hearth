package com.phairplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPhotoAssetCacheTest {
    @Test
    fun `asset lookups stay within their session and return defensive copies`() {
        val cache = SessionPhotoAssetCache(maxBytes = 64)
        val original = byteArrayOf(1, 2, 3)
        assertTrue(cache.put("session-a", "asset-1", original, PhotoImageType.JPEG))
        original[0] = 99

        val retrieved = cache.get("session-a", "asset-1")
        assertArrayEquals(byteArrayOf(1, 2, 3), retrieved?.bytes)
        retrieved!!.bytes[1] = 88
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("session-a", "asset-1")?.bytes)
        assertNull("same key in a different AirPlay session is not shared", cache.get("session-b", "asset-1"))
    }

    @Test
    fun `least-recently-used asset is evicted at the per-session bound`() {
        val cache = SessionPhotoAssetCache(maxAssetsPerSession = 2, maxBytes = 64)
        cache.put("session", "one", byteArrayOf(1), PhotoImageType.PNG)
        cache.put("session", "two", byteArrayOf(2), PhotoImageType.PNG)
        assertTrue(cache.get("session", "one") != null) // Make one the most recently used.
        cache.put("session", "three", byteArrayOf(3), PhotoImageType.PNG)

        assertNull(cache.get("session", "two"))
        assertTrue(cache.get("session", "one") != null)
        assertTrue(cache.get("session", "three") != null)
        assertEquals(2, cache.stats().assets)
    }

    @Test
    fun `least-recently-used session is evicted without cross-session reuse`() {
        val cache = SessionPhotoAssetCache(maxSessions = 2, maxAssetsPerSession = 2, maxBytes = 64)
        cache.put("session-a", "asset", byteArrayOf(1), PhotoImageType.JPEG)
        cache.put("session-b", "asset", byteArrayOf(2), PhotoImageType.JPEG)
        cache.get("session-a", "asset")
        cache.put("session-c", "asset", byteArrayOf(3), PhotoImageType.JPEG)

        assertNull(cache.get("session-b", "asset"))
        assertTrue(cache.get("session-a", "asset") != null)
        assertTrue(cache.get("session-c", "asset") != null)
        assertEquals(2, cache.stats().sessions)
    }

    @Test
    fun `global byte budget evicts old bodies and rejects a single oversized asset`() {
        val cache = SessionPhotoAssetCache(maxBytes = 5)
        assertTrue(cache.put("session-a", "old", ByteArray(3) { 1 }, PhotoImageType.PNG))
        assertTrue(cache.put("session-b", "new", ByteArray(3) { 2 }, PhotoImageType.PNG))

        assertNull(cache.get("session-a", "old"))
        assertEquals(3, cache.stats().bytes)
        assertFalse(cache.put("session-b", "large", ByteArray(6), PhotoImageType.PNG))
        assertEquals(3, cache.stats().bytes)
    }

    @Test
    fun `cache rejects empty and malformed identifiers`() {
        val cache = SessionPhotoAssetCache(maxBytes = 64)
        assertFalse(cache.put("session", "bad key", byteArrayOf(1), PhotoImageType.JPEG))
        assertFalse(cache.put("", "asset", byteArrayOf(1), PhotoImageType.JPEG))
        assertFalse(cache.put("session", "asset", byteArrayOf(), PhotoImageType.JPEG))
        assertEquals(0, cache.stats().assets)
    }
}
