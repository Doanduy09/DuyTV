package com.duytv.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsAdBlockEngineTest {

    @Test
    fun `test strips inline cue-out and ad keyword segments`() {
        val m3u8 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:4.0,
            https://cdn.example.com/movie/seg_001.ts
            #EXT-X-CUE-OUT:15
            #EXTINF:15.0,
            https://cdn.example.com/ads/preroll_1.ts
            #EXT-X-CUE-IN
            #EXTINF:4.0,
            https://cdn.example.com/movie/seg_002.ts
            #EXTINF:5.0,
            https://casino.8xbet.promo/ads/mid.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val cleaned = HlsAdBlockEngine.sanitizeM3u8Manifest(m3u8, "https://cdn.example.com/movie/index.m3u8")
        assertTrue(cleaned.contains("seg_001.ts"))
        assertTrue(cleaned.contains("seg_002.ts"))
        assertFalse(cleaned.contains("preroll_1.ts"))
        assertFalse(cleaned.contains("8xbet"))
        assertFalse(cleaned.contains("EXT-X-CUE-OUT"))
    }

    @Test
    fun `test strips discontinuity ad island while preserving main VOD`() {
        val mainSegments = (1..20).joinToString("\n") { i ->
            "#EXTINF:5.0,\nhttps://cdn.phim.com/v1/stream/seg_$i.ts"
        }
        val m3u8 = """
            #EXTM3U
            #EXT-X-VERSION:3
            $mainSegments
            #EXT-X-DISCONTINUITY
            #EXTINF:6.0,
            https://ads.promo-cdn.net/spot/ad_1.ts
            #EXTINF:6.0,
            https://ads.promo-cdn.net/spot/ad_2.ts
            #EXT-X-DISCONTINUITY
            #EXTINF:5.0,
            https://cdn.phim.com/v1/stream/seg_21.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val cleaned = HlsAdBlockEngine.sanitizeM3u8Manifest(m3u8, "https://cdn.phim.com/v1/stream/index.m3u8")
        assertTrue(cleaned.contains("seg_1.ts"))
        assertTrue(cleaned.contains("seg_21.ts"))
        assertFalse(cleaned.contains("ad_1.ts"))
        assertFalse(cleaned.contains("ad_2.ts"))
    }

    @Test
    fun `test master playlist prioritizes highest resolution first`() {
        val master = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,RESOLUTION=1920x1080
            1080p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1500000,RESOLUTION=1280x720
            720p/index.m3u8
        """.trimIndent()

        val prioritized = HlsAdBlockEngine.sanitizeM3u8Manifest(master, "https://cdn.phim.com/master.m3u8")
        val idx1080 = prioritized.indexOf("1080p/index.m3u8")
        val idx720 = prioritized.indexOf("720p/index.m3u8")
        val idx360 = prioritized.indexOf("360p/index.m3u8")
        assertTrue(idx1080 in 0 until idx720)
        assertTrue(idx720 < idx360)
    }

    @Test
    fun `test hex hash segment names with b52 m88 w88 bk8 ok9 are not false positives`() {
        val m3u8 = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXTINF:4.0,
            https://cdn.example.com/hls/seg_a1b52c9.ts
            #EXTINF:4.0,
            https://cdn.example.com/hls/chunk_f9m88e1.ts
            #EXTINF:4.0,
            https://cdn.example.com/hls/part_0w88bk8ok9.ts
            #EXTINF:6.0,
            https://m88.bet/spot/ad_segment.ts
            #EXTINF:6.0,
            https://cdn.ok9.vip/qc_01.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        val cleaned = HlsAdBlockEngine.sanitizeM3u8Manifest(m3u8, "https://cdn.example.com/hls/index.m3u8")
        assertTrue(cleaned.contains("seg_a1b52c9.ts"))
        assertTrue(cleaned.contains("chunk_f9m88e1.ts"))
        assertTrue(cleaned.contains("part_0w88bk8ok9.ts"))
        assertFalse(cleaned.contains("m88.bet"))
        assertFalse(cleaned.contains("ok9.vip"))
    }
}
