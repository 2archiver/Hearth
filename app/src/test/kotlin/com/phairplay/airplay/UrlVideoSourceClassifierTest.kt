package com.phairplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlVideoSourceClassifierTest {
    @Test
    fun recognizesHlsMimeTypesEvenWhenUrlHasNoExtension() {
        listOf(
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl; charset=utf-8",
            "audio/mpegurl",
        ).forEach { mimeType ->
            assertEquals(true, UrlVideoSourceClassifier.classify(mimeType).isHls)
        }
    }

    @Test
    fun recognizesHlsBodyPrefixWhenServerUsesGenericMimeType() {
        val prefix = "\uFEFF  \r\n#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray(Charsets.UTF_8)
        val result = UrlVideoSourceClassifier.classify("application/octet-stream", prefix)
        assertEquals(true, result.isHls)
        assertEquals("response body prefix", result.evidence)
    }

    @Test
    fun leavesProgressiveBodiesOnProgressiveRoute() {
        val result = UrlVideoSourceClassifier.classify("video/mp4", byteArrayOf(0, 0, 0, 24))
        assertEquals(false, result.isHls)
    }

    @Test
    fun rejectsMimeLookalikesAndEmptyBody() {
        assertEquals(false, UrlVideoSourceClassifier.classify("text/plain; note=application/x-mpegurl").isHls)
        assertEquals(false, UrlVideoSourceClassifier.classify(null).isHls)
    }
}
