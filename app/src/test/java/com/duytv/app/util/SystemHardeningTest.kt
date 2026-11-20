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

        assertTrue(HlsAdBlockEngine.isSinkholeOrInvalidExternalIp("cobephim.mom", loopback))
        assertFalse(HlsAdBlockEngine.isSinkholeOrInvalidExternalIp("cobephim.mom", realExternal))
        // Localhost and LAN IPs are allowed to resolve to local addresses
        assertFalse(HlsAdBlockEngine.isSinkholeOrInvalidExternalIp("localhost", loopback))
    }

    @Test
    fun `AppUpdateManager verifies SHA-256 checksum accurately`() {
        val tempFile = File.createTempFile("duytv_sha_test", ".bin")
        try {
            tempFile.writeText("DuyTV-v2.6.5-Verification", Charsets.UTF_8)
            val actualHash = AppUpdateManager.computeFileSha256(tempFile)
            assertEquals(64, actualHash.length)
            assertTrue(AppUpdateManager.verifyOptionalSha256(tempFile, actualHash))
            assertTrue(AppUpdateManager.verifyOptionalSha256(tempFile, actualHash.uppercase()))
            assertTrue(AppUpdateManager.verifyOptionalSha256(tempFile, null))
            assertTrue(AppUpdateManager.verifyOptionalSha256(tempFile, "   "))
            assertFalse(AppUpdateManager.verifyOptionalSha256(tempFile, "0000000000000000000000000000000000000000000000000000000000000000"))
        } finally {
            tempFile.delete()
        }
    }
}
