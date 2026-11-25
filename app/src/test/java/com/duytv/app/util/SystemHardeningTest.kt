package com.duytv.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

class SystemHardeningTest {

    @Test
    fun `QrCodeGenerator creates valid 25x25 Version 2 matrix with intact finder patterns for LAN URL`() {
        val qr = QrCodeGenerator.encode("http://192.168.1.172:8888")
        assertEquals(25, qr.size)
        // Top-left 7x7 finder outer border corners & center must be dark
        assertTrue(qr[0, 0])
        assertTrue(qr[6, 0])
        assertTrue(qr[0, 6])
        assertTrue(qr[6, 6])
        assertTrue(qr[3, 3])
        // Inner ring of finder (1,1) must be light
        assertFalse(qr[1, 1])
        // Top-right finder center (size - 4, 3) and bottom-left finder center (3, size - 4)
        assertTrue(qr[qr.size - 4, 3])
        assertTrue(qr[3, qr.size - 4])
    }

    @Test
    fun `LanMediaServer extractXmlTag unescapes XML entities in DLNA URLs`() {
        val soap = """
            <s:Envelope>
              <s:Body>
                <u:SetAVTransportURI>
                  <CurrentURI>http://192.168.1.169:50001/media?id=99&amp;type=photo&amp;name=&quot;family&quot;</CurrentURI>
                  <CurrentURIMetaData>&lt;upnp:class&gt;object.item.imageItem.photo&lt;/upnp:class&gt;</CurrentURIMetaData>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>
        """.trimIndent()

        val uri = LanMediaServer.extractXmlTag(soap, "CurrentURI")
        val meta = LanMediaServer.extractXmlTag(soap, "CurrentURIMetaData")

        assertEquals("http://192.168.1.169:50001/media?id=99&type=photo&name=\"family\"", uri)
        assertTrue(meta.contains("<upnp:class>object.item.imageItem.photo</upnp:class>"))
    }

    @Test
    fun `HlsAdBlockEngine rejects ISP DNS sinkhole loopback IPs for external domains`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val realExternal = InetAddress.getByName("104.21.48.84")

        assertTrue(HlsAdBlockEngine.isSinkholeOrInvalidExternalIp("cobephim.tv", loopback))
        assertFalse(HlsAdBlockEngine.isSinkholeOrInvalidExternalIp("cobephim.tv", realExternal))
        // Localhost and LAN IPs are allowed to resolve to local addresses
        assertFalse(HlsAdBlockEngine.isSinkholeOrInvalidExternalIp("localhost", loopback))
    }

    @Test
    fun `AppUpdateManager strictly requires valid 64-hex SHA-256 checksum`() {
        val tempFile = File.createTempFile("duytv_sha_test", ".bin")
        try {
            tempFile.writeText("DuyTV-v2.6.5-Verification", Charsets.UTF_8)
            val actualHash = AppUpdateManager.computeFileSha256(tempFile)
            assertEquals(64, actualHash.length)
            assertTrue(AppUpdateManager.verifyRequiredSha256(tempFile, actualHash))
            assertTrue(AppUpdateManager.verifyOptionalSha256(tempFile, actualHash.uppercase()))
            assertFalse(AppUpdateManager.verifyRequiredSha256(tempFile, null))
            assertFalse(AppUpdateManager.verifyOptionalSha256(tempFile, null))
            assertFalse(AppUpdateManager.verifyRequiredSha256(tempFile, "   "))
            assertFalse(AppUpdateManager.verifyRequiredSha256(tempFile, "invalid_short_hash"))
            assertFalse(AppUpdateManager.verifyRequiredSha256(tempFile, "0000000000000000000000000000000000000000000000000000000000000000"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `LanMediaServer validates private LAN origins, IPs, and bounded JSON lengths`() {
        assertTrue(LanMediaServer.isPrivateOrLoopbackIp("192.168.1.1"))
        assertTrue(LanMediaServer.isPrivateOrLoopbackIp("127.0.0.1"))
        assertTrue(LanMediaServer.isPrivateOrLoopbackIp("10.0.0.5"))
        assertTrue(LanMediaServer.isPrivateOrLoopbackIp("172.16.4.2"))
        assertFalse(LanMediaServer.isPrivateOrLoopbackIp("8.8.8.8"))
        assertFalse(LanMediaServer.isPrivateOrLoopbackIp("172.32.0.1"))

        assertTrue(LanMediaServer.isAllowedLanOrigin(null))
        assertTrue(LanMediaServer.isAllowedLanOrigin("http://192.168.1.218:8888"))
        assertFalse(LanMediaServer.isAllowedLanOrigin("https://evil.example.com"))
        assertFalse(LanMediaServer.isAllowedLanOrigin("http://8.8.8.8:8888"))

        assertEquals(256, LanMediaServer.parseBoundedJsonBodyLength(256L))
        assertEquals(null, LanMediaServer.parseBoundedJsonBodyLength(-1L))
        assertEquals(null, LanMediaServer.parseBoundedJsonBodyLength(Integer.MAX_VALUE.toLong() + 100L))
    }

    @Test
    fun `LanCommandReceiver guards screen wakeup and exposes default blank LAN AI host`() {
        assertTrue(LanCommandReceiver.shouldWakeScreenForPayload(null, "mở vtv1"))
        assertTrue(LanCommandReceiver.shouldWakeScreenForPayload("notify", ""))
        assertFalse(LanCommandReceiver.shouldWakeScreenForPayload(null, ""))
        assertFalse(LanCommandReceiver.shouldWakeScreenForPayload(null, "tắt tivi phòng ngủ đi ngủ"))
        assertEquals("", GeminiVoiceClient.DEFAULT_LAN_HOST_URL)
    }

    @Test
    fun `MovieRepository mergeAndDeduplicateChannels purges dead domains and prioritizes official CDNs`() {
        val inputs = listOf(
            com.duytv.app.data.TvChannel(
                id = "c1",
                name = "VTV1 HD",
                streamUrl = "https://1011154949.vnns.net/CDN-FPT02/VTV1-1080p/playlist.m3u8",
                backupUrls = listOf(
                    "https://coca.dl.dropboxusercontent.coin/s/abc/vtv1.m3u8",
                    "https://freem3u.xyz/api/live/play.m3u8?vid=1"
                )
            ),
            com.duytv.app.data.TvChannel(
                id = "c2",
                name = "VTV1",
                streamUrl = "https://dethich.pw/vtv1/index.m3u8",
                backupUrls = listOf("https://live.vtvdigital.vn/vtv1/index.m3u8")
            )
        )
        val merged = com.duytv.app.data.MovieRepository.mergeAndDeduplicateChannels(inputs)
        assertEquals(1, merged.size)
        val vtv1 = merged.first()
        assertEquals("https://live.vtvdigital.vn/vtv1/index.m3u8", vtv1.streamUrl)
        assertTrue(vtv1.backupUrls.contains("https://dethich.pw/vtv1/index.m3u8"))
        assertFalse(vtv1.allStreamUrls().any { it.contains("vnns.net") || it.contains(".coin") || it.contains("freem3u.xyz") })
    }

    @Test
    fun `AppCacheManager offline fallback handles empty or invalid state safely`() {
        val fallback = com.duytv.app.data.AppCacheManager.loadCleanTvM3uFallback()
        if (fallback != null) {
            assertTrue(fallback.contains("#EXTM3U"))
        }
    }
}
