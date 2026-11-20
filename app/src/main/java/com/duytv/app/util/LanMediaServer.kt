package com.duytv.app.util

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.duytv.app.BuildConfig
import com.duytv.app.MainActivity
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Máy chủ LAN Media, DLNA UPnP Digital Media Renderer & Web Control Hub nhúng siêu nhẹ (Port 8888).
 * - Tự động hiển thị "DuyTV (Tivi Sharp)" qua DLNA/SSDP.
 * - Chiếu ảnh từ điện thoại (nén tự động 1920px trên trình duyệt, file name động chống dính cache).
 * - Chiếu video trực tiếp từ thư viện điện thoại (streaming 64KB chunk + HTTP Range) hoặc dán link MP4/HLS.
 * - Bảo vệ chống OOM (giới hạn body), chống CSRF (tắt GET state-changing + không mở CORS bừa bãi),
 *   SupervisorJob tự phục hồi 24/7.
 */
object LanMediaServer {
    private const val TAG = "LanMediaServer"
    const val PORT = 8888
    internal const val MAX_JSON_BODY_BYTES = 10 * 1024 * 1024 // 10 MB trần chống OOM
    private const val MAX_VIDEO_UPLOAD_BYTES = 150L * 1024 * 1024 // 150 MB trần cho video stream ra đĩa

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.w(TAG, "LanMediaServer coroutine error (recovered): ${throwable.message}")
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    @Volatile
    private var lastTransportState: String = "STOPPED"

    fun start(context: Context) {
        if (serverJob != null && serverJob?.isActive == true) return
        val appCtx = context.applicationContext

        serverJob = scope.launch {
            while (isActive) {
                try {
                    serverSocket = ServerSocket(PORT).apply {
                        reuseAddress = true
                    }
                    Log.i(TAG, "DuyTV LAN Media Server đang lắng nghe tại cổng HTTP $PORT")
                    while (isActive && serverSocket?.isClosed == false) {
                        val client = serverSocket?.accept() ?: break
                        launch { handleClient(appCtx, client) }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "LanMediaServer ServerSocket exception: ${e.message}. Khởi động lại sau 3s...")
                } finally {
                    try { serverSocket?.close() } catch (_: Exception) {}
                }
                delay(3000)
            }
        }
    }

    private fun readHeaderBlock(bis: BufferedInputStream): String? {
        val baos = ByteArrayOutputStream(1024)
        var prev3 = 0
        var prev2 = 0
        var prev1 = 0
        var total = 0
        while (total < 16384) {
            val b = bis.read()
            if (b == -1) return if (baos.size() == 0) null else baos.toString("UTF-8")
            baos.write(b)
            total++
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                break
            }
            if (prev1 == '\n'.code && b == '\n'.code) {
                break
            }
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }
        return baos.toString("UTF-8")
    }

    private fun readExactBytes(input: InputStream, length: Int): ByteArray? {
        if (length <= 0) return ByteArray(0)
        if (length > MAX_JSON_BODY_BYTES) return null
        val out = ByteArray(length)
        var total = 0
        while (total < length) {
            val r = input.read(out, total, length - total)
            if (r == -1) break
            total += r
        }
        return if (total == length) out else out.copyOf(total)
    }

    private fun handleClient(context: Context, socket: Socket) {
        try {
            socket.soTimeout = 15000
            val bis = BufferedInputStream(socket.getInputStream(), 16 * 1024)
            val output = socket.getOutputStream()

            val headerBlock = readHeaderBlock(bis) ?: return
            val lines = headerBlock.split("\r\n", "\n")
            if (lines.isEmpty()) return

            val parts = lines[0].trim().split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawPath = parts[1]

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isBlank()) break
                val colonIdx = line.indexOf(":")
                if (colonIdx > 0) {
                    headers[line.substring(0, colonIdx).trim().lowercase()] =
                        line.substring(colonIdx + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toLongOrNull() ?: 0L
            val contentType = headers["content-type"] ?: ""
            val soapAction = headers["soapaction"] ?: ""
            val rangeHeader = headers["range"]

            when {
                // Trang Web Remote & Bắn Media chính
                method == "GET" && (rawPath == "/" || rawPath.startsWith("/index")) -> {
                    serveWebHubHtml(output)
                }

                // Trạng thái hệ thống
                method == "GET" && rawPath == "/api/status" -> {
                    val resp = JSONObject()
                        .put("status", "ok")
                        .put("app", "DuyTV")
                        .put("version", BuildConfig.VERSION_NAME)
                        .put("playing", MainActivity.isVideoActivelyPlaying)
                        .toString()
                    sendHttpResponse(output, 200, "application/json; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
                }

                // Phục vụ video cục bộ đã upload từ điện thoại (hỗ trợ HTTP Range cho ExoPlayer tua mượt)
                (method == "GET" || method == "HEAD") && rawPath.startsWith("/local_media/") -> {
                    serveLocalMediaFile(context, rawPath, rangeHeader, method == "HEAD", output)
                }

                // --- DLNA UPnP Digital Media Renderer Endpoints ---
                method == "GET" && rawPath.startsWith("/upnp/description.xml") -> {
                    serveUpnpDeviceDescription(output)
                }

                method == "GET" && rawPath.startsWith("/upnp/AVTransport.xml") -> {
                    serveUpnpAvTransportScpd(output)
                }

                method == "GET" && rawPath.startsWith("/upnp/RenderingControl.xml") -> {
                    serveUpnpRenderingControlScpd(output)
                }

                method == "GET" && rawPath.startsWith("/upnp/ConnectionManager.xml") -> {
                    serveUpnpConnectionManagerScpd(output)
                }

                method == "POST" && rawPath.startsWith("/upnp/control/AVTransport") -> {
                    val bytes = readExactBytes(bis, contentLength.toInt().coerceAtLeast(0))
                    if (bytes == null) {
                        sendPayloadTooLarge(output)
                    } else {
                        handleUpnpAvTransportControl(soapAction, String(bytes, Charsets.UTF_8), output)
                    }
                }

                method == "POST" && rawPath.startsWith("/upnp/control/RenderingControl") -> {
                    val bytes = readExactBytes(bis, contentLength.toInt().coerceAtLeast(0))
                    if (bytes == null) {
                        sendPayloadTooLarge(output)
                    } else {
                        handleUpnpRenderingControl(context, soapAction, String(bytes, Charsets.UTF_8), output)
                    }
                }

                method == "POST" && rawPath.startsWith("/upnp/control/ConnectionManager") -> {
                    val bytes = readExactBytes(bis, contentLength.toInt().coerceAtLeast(0))
                    if (bytes == null) {
                        sendPayloadTooLarge(output)
                    } else {
                        handleUpnpConnectionManager(output)
                    }
                }

                // Bắn ảnh (POST JSON Base64 hoặc Raw bytes an toàn không hỏng binary)
                method == "POST" && rawPath.startsWith("/api/cast_photo") -> {
                    if (contentLength > MAX_JSON_BODY_BYTES) {
                        sendPayloadTooLarge(output)
                    } else {
                        handleCastPhoto(context, bis, contentLength.toInt().coerceAtLeast(0), contentType, output)
                    }
                }

                // Tải trực tiếp file video quay trên điện thoại (streaming ra đĩa 64KB chunk, không ăn RAM)
                method == "POST" && rawPath.startsWith("/api/upload_video") -> {
                    handleUploadVideo(context, bis, rawPath, contentLength, output)
                }

                // Bắn link video (POST JSON)
                method == "POST" && rawPath.startsWith("/api/cast_video") -> {
                    val bytes = readExactBytes(bis, contentLength.toInt().coerceAtLeast(0))
                    if (bytes == null) {
                        sendPayloadTooLarge(output)
                    } else {
                        val json = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
                        val url = json.optString("url")
                        val title = json.optString("title", "Video từ điện thoại")
                        if (url.isNotBlank()) {
                            MainActivity.openCastVideo(url, title)
                            sendJsonResponse(output, 200, JSONObject().put("status", "ok"))
                        } else {
                            sendJsonResponse(output, 400, JSONObject().put("error", "Missing url"))
                        }
                    }
                }

                // Gửi thông báo gia đình (POST JSON - chống CSRF)
                method == "POST" && rawPath.startsWith("/api/notify") -> {
                    val bytes = readExactBytes(bis, contentLength.toInt().coerceAtLeast(0))
                    if (bytes == null) {
                        sendPayloadTooLarge(output)
                    } else {
                        val json = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
                        val message = json.optString("message").ifBlank { json.optString("msg") }
                        if (message.isNotBlank()) {
                            MainActivity.showFamilyNotification(message)
                            sendJsonResponse(output, 200, JSONObject().put("status", "ok"))
                        } else {
                            sendJsonResponse(output, 400, JSONObject().put("error", "Missing message"))
                        }
                    }
                }

                // Điều khiển nhanh / lệnh giọng nói từ Web (POST JSON - chống CSRF)
                method == "POST" && rawPath.startsWith("/api/command") -> {
                    val bytes = readExactBytes(bis, contentLength.toInt().coerceAtLeast(0))
                    if (bytes == null) {
                        sendPayloadTooLarge(output)
                    } else {
                        val json = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
                        val cmd = json.optString("command").ifBlank { json.optString("raw_text") }
                        if (cmd.isNotBlank()) {
                            MainActivity.executeRemoteCommand(cmd, context)
                            sendJsonResponse(output, 200, JSONObject().put("status", "ok").put("command", cmd))
                        } else {
                            sendJsonResponse(output, 400, JSONObject().put("error", "Missing command"))
                        }
                    }
                }

                // Chặn HTTP GET cho các lệnh thay đổi trạng thái TV (bảo vệ chống CSRF qua thẻ <img>)
                method == "GET" && (rawPath.startsWith("/api/command") || rawPath.startsWith("/api/notify")) -> {
                    sendJsonResponse(output, 405, JSONObject().put("error", "Method Not Allowed: use POST"))
                }

                else -> {
                    sendHttpResponse(output, 404, "text/plain; charset=utf-8", "Not Found".toByteArray(Charsets.UTF_8))
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun cleanupOldTempFiles(cacheDir: File, prefix: String, keepName: String) {
        try {
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith(prefix) && f.name != keepName) {
                    f.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleCastPhoto(
        context: Context,
        input: InputStream,
        length: Int,
        contentType: String,
        output: OutputStream
    ) {
        try {
            val rawBytes = readExactBytes(input, length)
            if (rawBytes == null) {
                sendPayloadTooLarge(output)
                return
            }
            val fileName = "cast_photo_${System.currentTimeMillis()}.jpg"
            val photoFile = File(context.cacheDir, fileName)

            if (contentType.contains("application/json", ignoreCase = true)) {
                val json = JSONObject(String(rawBytes, Charsets.UTF_8))
                val b64 = json.optString("base64").ifBlank { json.optString("image_base64") }
                val title = json.optString("title", "Ảnh từ điện thoại")
                if (b64.isNotBlank()) {
                    val cleanB64 = if (b64.contains(",")) b64.substringAfter(",") else b64
                    val decodedBytes = android.util.Base64.decode(cleanB64, android.util.Base64.DEFAULT)
                    FileOutputStream(photoFile).use { it.write(decodedBytes) }
                    cleanupOldTempFiles(context.cacheDir, "cast_photo_", fileName)
                    MainActivity.openCastPhoto(photoFile.absolutePath, title)
                    sendJsonResponse(output, 200, JSONObject().put("status", "ok"))
                    return
                }
            }

            // Raw binary upload trực tiếp không qua UTF-8 decoder -> giữ nguyên 100% byte gốc ảnh
            FileOutputStream(photoFile).use { fos -> fos.write(rawBytes) }
            cleanupOldTempFiles(context.cacheDir, "cast_photo_", fileName)
            MainActivity.openCastPhoto(photoFile.absolutePath, "Ảnh từ điện thoại")
            sendJsonResponse(output, 200, JSONObject().put("status", "ok"))
        } catch (e: Exception) {
            sendJsonResponse(output, 500, JSONObject().put("error", e.message ?: "Photo cast error"))
        }
    }

    private fun handleUploadVideo(
        context: Context,
        input: InputStream,
        rawPath: String,
        contentLength: Long,
        output: OutputStream
    ) {
        if (contentLength <= 0L) {
            sendJsonResponse(output, 400, JSONObject().put("error", "Empty video payload"))
            return
        }
        if (contentLength > MAX_VIDEO_UPLOAD_BYTES) {
            sendPayloadTooLarge(output)
            return
        }
        try {
            val query = rawPath.substringAfter("?", "")
            val params = if (query.isNotEmpty()) parseQueryParams(query) else emptyMap()
            val title = params["title"]?.takeIf { it.isNotBlank() } ?: "Video từ điện thoại"
            val fileName = "cast_video_${System.currentTimeMillis()}.mp4"
            val videoFile = File(context.cacheDir, fileName)

            // Stream 64KB chunk trực tiếp xuống đĩa -> 0 spike RAM trên Tivi 1.5GB
            FileOutputStream(videoFile).use { fos ->
                val buf = ByteArray(64 * 1024)
                var remaining = contentLength
                while (remaining > 0) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val r = input.read(buf, 0, toRead)
                    if (r == -1) break
                    fos.write(buf, 0, r)
                    remaining -= r
                }
                fos.flush()
            }
            cleanupOldTempFiles(context.cacheDir, "cast_video_", fileName)

            val localUrl = "http://127.0.0.1:$PORT/local_media/$fileName"
            MainActivity.openCastVideo(localUrl, title)
            sendJsonResponse(output, 200, JSONObject().put("status", "ok").put("url", localUrl))
        } catch (e: Exception) {
            sendJsonResponse(output, 500, JSONObject().put("error", e.message ?: "Video upload error"))
        }
    }

    private fun serveLocalMediaFile(
        context: Context,
        rawPath: String,
        rangeHeader: String?,
        headOnly: Boolean,
        output: OutputStream
    ) {
        val rawName = rawPath.removePrefix("/local_media/").substringBefore("?")
        // Chống Path Traversal tuyệt đối: chỉ cho phép ký tự an toàn của tên file cast_video_*
        if (!rawName.matches(Regex("^cast_video_\\d+\\.mp4$"))) {
            sendHttpResponse(output, 403, "text/plain", "Forbidden".toByteArray())
            return
        }
        val file = File(context.cacheDir, rawName)
        if (!file.exists()) {
            sendHttpResponse(output, 404, "text/plain", "Not Found".toByteArray())
            return
        }

        val totalLen = file.length()
        var start = 0L
        var end = totalLen - 1
        var isPartial = false

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val rangeVal = rangeHeader.removePrefix("bytes=").substringBefore(",")
            val dash = rangeVal.indexOf('-')
            if (dash >= 0) {
                val s = rangeVal.substring(0, dash).trim().toLongOrNull()
                val e = rangeVal.substring(dash + 1).trim().toLongOrNull()
                if (s != null) start = s.coerceIn(0, totalLen - 1)
                if (e != null) end = e.coerceIn(start, totalLen - 1)
                isPartial = true
            }
        }

        val contentLen = (end - start) + 1
        val statusLine = if (isPartial) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK"
        val header = buildString {
            append("$statusLine\r\n")
            append("Content-Type: video/mp4\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Content-Length: $contentLen\r\n")
            if (isPartial) {
                append("Content-Range: bytes $start-$end/$totalLen\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        if (!headOnly) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(start)
                val buf = ByteArray(64 * 1024)
                var remaining = contentLen
                while (remaining > 0) {
                    val r = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (r == -1) break
                    output.write(buf, 0, r)
                    remaining -= r
                }
            }
        }
        output.flush()
    }

    internal fun unescapeXml(raw: String): String {
        return raw
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
    }

    internal fun extractXmlTag(xml: String, tag: String): String {
        val open = "<$tag"
        val startIdx = xml.indexOf(open)
        if (startIdx == -1) return ""
        val gtIdx = xml.indexOf(">", startIdx)
        if (gtIdx == -1) return ""
        val close = "</$tag>"
        val endIdx = xml.indexOf(close, gtIdx)
        if (endIdx == -1) return ""
        return unescapeXml(xml.substring(gtIdx + 1, endIdx).trim())
    }

    private fun serveUpnpDeviceDescription(output: OutputStream) {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>DuyTV (Tivi Sharp)</friendlyName>
    <manufacturer>DuyTV Team</manufacturer>
    <manufacturerURL>https://github.com/Doanduy09/DuyTV</manufacturerURL>
    <modelDescription>DuyTV Android TV Media Renderer</modelDescription>
    <modelName>DuyTV Aquos</modelName>
    <modelNumber>${BuildConfig.VERSION_NAME}</modelNumber>
    <UDN>${DlnaMediaRenderer.UDN}</UDN>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <controlURL>/upnp/control/AVTransport</controlURL>
        <eventSubURL>/upnp/event/AVTransport</eventSubURL>
        <SCPDURL>/upnp/AVTransport.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <controlURL>/upnp/control/RenderingControl</controlURL>
        <eventSubURL>/upnp/event/RenderingControl</eventSubURL>
        <SCPDURL>/upnp/RenderingControl.xml</SCPDURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <controlURL>/upnp/control/ConnectionManager</controlURL>
        <eventSubURL>/upnp/event/ConnectionManager</eventSubURL>
        <SCPDURL>/upnp/ConnectionManager.xml</SCPDURL>
      </service>
    </serviceList>
  </device>
</root>""".trimIndent()
        sendHttpResponse(output, 200, "text/xml; charset=utf-8", xml.toByteArray(Charsets.UTF_8))
    }

    private fun serveUpnpAvTransportScpd(output: OutputStream) {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name></action>
    <action><name>Play</name></action>
    <action><name>Pause</name></action>
    <action><name>Stop</name></action>
    <action><name>GetTransportInfo</name></action>
    <action><name>GetPositionInfo</name></action>
  </actionList>
</scpd>""".trimIndent()
        sendHttpResponse(output, 200, "text/xml; charset=utf-8", xml.toByteArray(Charsets.UTF_8))
    }

    private fun serveUpnpRenderingControlScpd(output: OutputStream) {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetVolume</name></action>
    <action><name>SetVolume</name></action>
    <action><name>GetMute</name></action>
    <action><name>SetMute</name></action>
  </actionList>
</scpd>""".trimIndent()
        sendHttpResponse(output, 200, "text/xml; charset=utf-8", xml.toByteArray(Charsets.UTF_8))
    }

    private fun serveUpnpConnectionManagerScpd(output: OutputStream) {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetProtocolInfo</name></action>
    <action><name>GetCurrentConnectionIDs</name></action>
    <action><name>GetCurrentConnectionInfo</name></action>
  </actionList>
</scpd>""".trimIndent()
        sendHttpResponse(output, 200, "text/xml; charset=utf-8", xml.toByteArray(Charsets.UTF_8))
    }

    private fun handleUpnpAvTransportControl(soapAction: String, body: String, output: OutputStream) {
        when {
            soapAction.contains("SetAVTransportURI") || body.contains("SetAVTransportURI") -> {
                val uri = extractXmlTag(body, "CurrentURI")
                val meta = extractXmlTag(body, "CurrentURIMetaData")
                Log.i(TAG, "DLNA SetAVTransportURI: uri=$uri")
                if (uri.isNotBlank()) {
                    val lowerUri = uri.substringBefore("?").lowercase()
                    val isImage = lowerUri.endsWith(".jpg") || lowerUri.endsWith(".jpeg") ||
                            lowerUri.endsWith(".png") || lowerUri.endsWith(".webp") ||
                            lowerUri.endsWith(".heic") || lowerUri.endsWith(".gif") ||
                            meta.contains("imageItem", true) || meta.contains("image/", true)
                    lastTransportState = "PLAYING"
                    if (isImage) {
                        MainActivity.openCastPhoto(uri, "Ảnh từ điện thoại")
                    } else {
                        MainActivity.openCastVideo(uri, "Video từ điện thoại")
                    }
                }
                val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:SetAVTransportURIResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>
  </s:Body>
</s:Envelope>""".trimIndent()
                sendHttpResponse(output, 200, "text/xml; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
            }
            soapAction.contains("Play") || body.contains("<u:Play") -> {
                lastTransportState = "PLAYING"
                val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:PlayResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>
  </s:Body>
</s:Envelope>""".trimIndent()
                sendHttpResponse(output, 200, "text/xml; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
            }
            soapAction.contains("Stop") || body.contains("<u:Stop") -> {
                lastTransportState = "STOPPED"
                MainActivity.returnToHomeFromCast()
                val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:StopResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>
  </s:Body>
</s:Envelope>""".trimIndent()
                sendHttpResponse(output, 200, "text/xml; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
            }
            soapAction.contains("GetTransportInfo") || body.contains("GetTransportInfo") -> {
                val state = if (MainActivity.isVideoActivelyPlaying || lastTransportState == "PLAYING") "PLAYING" else "STOPPED"
                val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetTransportInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <CurrentTransportState>$state</CurrentTransportState>
      <CurrentTransportStatus>OK</CurrentTransportStatus>
      <CurrentSpeed>1</CurrentSpeed>
    </u:GetTransportInfoResponse>
  </s:Body>
</s:Envelope>""".trimIndent()
                sendHttpResponse(output, 200, "text/xml; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
            }
            soapAction.contains("GetPositionInfo") || body.contains("GetPositionInfo") -> {
                val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
      <Track>1</Track>
      <TrackDuration>00:00:00</TrackDuration>
      <RelTime>00:00:00</RelTime>
      <AbsTime>00:00:00</AbsTime>
      <RelCount>0</RelCount>
      <AbsCount>0</AbsCount>
    </u:GetPositionInfoResponse>
  </s:Body>
</s:Envelope>""".trimIndent()
                sendHttpResponse(output, 200, "text/xml; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
            }
            else -> {
                val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:GenericResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>
  </s:Body>
</s:Envelope>""".trimIndent()
                sendHttpResponse(output, 200, "text/xml; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun handleUpnpRenderingControl(context: Context, soapAction: String, body: String, output: OutputStream) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val maxVol = (am?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15).coerceAtLeast(1)

        val resp = when {
            soapAction.contains("SetVolume") || body.contains("SetVolume") -> {
                val desiredPct = extractXmlTag(body, "DesiredVolume").toIntOrNull()?.coerceIn(0, 100)
                if (desiredPct != null && am != null) {
                    val target = ((desiredPct / 100f) * maxVol).toInt().coerceIn(0, maxVol)
                    try { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI) } catch (_: Exception) {}
                }
                """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:SetVolumeResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"/>
  </s:Body>
</s:Envelope>""".trimIndent()
            }
            soapAction.contains("SetMute") || body.contains("SetMute") -> {
                val muteRaw = extractXmlTag(body, "DesiredMute")
                val mute = muteRaw == "1" || muteRaw.equals("true", ignoreCase = true)
                if (am != null) {
                    try {
                        am.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                            AudioManager.FLAG_SHOW_UI
                        )
                    } catch (_: Exception) {}
                }
                """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:SetMuteResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1"/>
  </s:Body>
</s:Envelope>""".trimIndent()
            }
            soapAction.contains("GetMute") || body.contains("GetMute") -> {
                val isMuted = try { am?.isStreamMute(AudioManager.STREAM_MUSIC) == true } catch (_: Exception) { false }
                """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:GetMuteResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
      <CurrentMute>${if (isMuted) 1 else 0}</CurrentMute>
    </u:GetMuteResponse>
  </s:Body>
</s:Envelope>""".trimIndent()
            }
            else -> {
                val curVol = am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: (maxVol / 2)
                val pct = ((curVol * 100f) / maxVol).toInt().coerceIn(0, 100)
                """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:GetVolumeResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
      <CurrentVolume>$pct</CurrentVolume>
    </u:GetVolumeResponse>
  </s:Body>
</s:Envelope>""".trimIndent()
            }
        }
        sendHttpResponse(output, 200, "text/xml; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
    }

    private fun handleUpnpConnectionManager(output: OutputStream) {
        val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:GetProtocolInfoResponse xmlns:u="urn:schemas-upnp-org:service:ConnectionManager:1">
      <Source></Source>
      <Sink>http-get:*:image/jpeg:*,http-get:*:image/png:*,http-get:*:image/webp:*,http-get:*:video/mp4:*,http-get:*:application/x-mpegURL:*</Sink>
    </u:GetProtocolInfoResponse>
  </s:Body>
</s:Envelope>""".trimIndent()
        sendHttpResponse(output, 200, "text/xml; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val eq = pair.indexOf("=")
            if (eq > 0) {
                val k = URLDecoder.decode(pair.substring(0, eq), "UTF-8")
                val v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
                result[k] = v
            }
        }
        return result
    }

    private fun sendPayloadTooLarge(output: OutputStream) {
        sendJsonResponse(output, 413, JSONObject().put("error", "Payload Too Large"))
    }

    private fun sendJsonResponse(output: OutputStream, statusCode: Int, json: JSONObject) {
        sendHttpResponse(output, statusCode, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))
    }

    private fun sendHttpResponse(output: OutputStream, statusCode: Int, contentType: String, data: ByteArray) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(data)
        output.flush()
    }

    private fun serveWebHubHtml(output: OutputStream) {
        val localIp = DlnaMediaRenderer.getLocalIpv4Address()
        val html = """
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>DuyTV LAN Cast & Remote</title>
    <style>
        :root {
            --bg-color: #0b0f19;
            --card-bg: #151d30;
            --accent-red: #e50914;
            --accent-cyan: #00e5ff;
            --text-main: #f8fafc;
            --text-sub: #94a3b8;
            --border-color: #1e293b;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
        body { background: var(--bg-color); color: var(--text-main); padding: 16px; display: flex; flex-direction: column; align-items: center; min-height: 100vh; }
        .container { width: 100%; max-width: 480px; }
        .header { text-align: center; margin-bottom: 16px; }
        .header h1 { font-size: 22px; font-weight: 900; letter-spacing: 0.5px; }
        .header h1 span { color: var(--accent-red); }
        .header p { font-size: 13px; color: var(--text-sub); margin-top: 4px; }
        .dlna-tip { background: rgba(0, 229, 255, 0.08); border: 1px solid rgba(0, 229, 255, 0.3); border-radius: 10px; padding: 12px; margin-bottom: 16px; font-size: 13px; line-height: 1.5; color: #e2e8f0; }
        .dlna-tip b { color: var(--accent-cyan); }
        .tabs { display: flex; background: var(--card-bg); border-radius: 12px; padding: 4px; margin-bottom: 16px; border: 1px solid var(--border-color); }
        .tab-btn { flex: 1; padding: 10px 4px; text-align: center; font-size: 13px; font-weight: 700; border-radius: 8px; border: none; background: transparent; color: var(--text-sub); cursor: pointer; transition: all 0.2s; }
        .tab-btn.active { background: var(--accent-red); color: #fff; box-shadow: 0 2px 8px rgba(229, 9, 20, 0.4); }
        .card { background: var(--card-bg); border-radius: 16px; padding: 18px; border: 1px solid var(--border-color); margin-bottom: 16px; }
        .card h2 { font-size: 16px; font-weight: 700; margin-bottom: 12px; display: flex; align-items: center; gap: 8px; }
        .btn-primary { width: 100%; padding: 14px; background: var(--accent-red); border: none; border-radius: 10px; color: #fff; font-size: 15px; font-weight: 800; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px; box-shadow: 0 4px 12px rgba(229, 9, 20, 0.3); }
        .btn-primary:active { transform: scale(0.98); }
        .input-box { width: 100%; padding: 12px; background: #0f172a; border: 1px solid var(--border-color); border-radius: 8px; color: #fff; font-size: 14px; margin-bottom: 12px; }
        .status-toast { margin-top: 10px; padding: 10px; border-radius: 8px; background: rgba(0, 229, 255, 0.15); border: 1px solid var(--accent-cyan); color: #fff; font-size: 13px; text-align: center; display: none; }
        .divider { border-top: 1px solid var(--border-color); margin: 16px 0; }
    </style>
</head>
<body>
    <div class="container">
        <div class="header">
            <h1>📺 <span>DUY</span>TV LAN CAST & REMOTE</h1>
            <p>Phát sóng Wi-Fi: "DuyTV (Tivi Sharp)" • $localIp</p>
        </div>

        <div class="dlna-tip">
            💡 <b>Truyền trực tiếp từ Thư viện ảnh điện thoại</b>:<br>
            Mở ảnh/video trong <b>Bộ sưu tập Samsung / Xiaomi / Android</b> &rarr; Bấm nút <b>Truyền / Smart View</b> &rarr; Chọn <b>DuyTV (Tivi Sharp)</b>!
        </div>

        <div class="tabs">
            <button class="tab-btn active" onclick="switchTab(this, 'photo')">📷 Chiếu Ảnh</button>
            <button class="tab-btn" onclick="switchTab(this, 'video')">🎬 Chiếu Video</button>
            <button class="tab-btn" onclick="switchTab(this, 'notify')">🔔 Nhắc Việc</button>
            <button class="tab-btn" onclick="switchTab(this, 'remote')">🎮 Remote</button>
        </div>

        <!-- TAB 1: CHIẾU ẢNH -->
        <div id="tab-photo" class="card tab-content">
            <h2>📷 Bắn ảnh chụp điện thoại lên TV</h2>
            <input type="file" id="photoFile" accept="image/*" class="input-box" onchange="previewSelectedPhoto()">
            <img id="photoPreview" style="width: 100%; max-height: 220px; object-fit: contain; border-radius: 8px; margin-bottom: 12px; display: none; background: #000;">
            <button class="btn-primary" onclick="uploadPhoto()">⚡ Chiếu Lên Màn Hình TV</button>
            <div id="photoStatus" class="status-toast"></div>
        </div>

        <!-- TAB 2: CHIẾU VIDEO -->
        <div id="tab-video" class="card tab-content" style="display: none;">
            <h2>🎬 Chiếu Video Quay Từ Điện Thoại</h2>
            <input type="file" id="videoFile" accept="video/*" class="input-box">
            <button class="btn-primary" onclick="uploadLocalVideo()">📤 Gửi Video Từ Máy Lên TV</button>
            <div class="divider"></div>
            <h2>🔗 Hoặc Dán Link Video MP4 / M3U8</h2>
            <input type="url" id="videoUrlInput" class="input-box" placeholder="Dán link video MP4 / M3U8 tại đây...">
            <button class="btn-primary" style="background: #1e40af;" onclick="castVideo()">▶️ Phát Link Trên TV</button>
            <div id="videoStatus" class="status-toast"></div>
        </div>

        <!-- TAB 3: LỜI NHẮC GIA ĐÌNH -->
        <div id="tab-notify" class="card tab-content" style="display: none;">
            <h2>🔔 Lời Nhắc Gia Đình Không Gián Đoạn</h2>
            <input type="text" id="notifyTextInput" class="input-box" placeholder="Ví dụ: Mẹ ơi tới giờ uống thuốc rồi...">
            <button class="btn-primary" onclick="sendCustomNotify()">📢 Gửi Lời Nhắc Lên TV</button>
            <div id="hudStatus" class="status-toast"></div>
        </div>

        <!-- TAB 4: REMOTE WEB -->
        <div id="tab-remote" class="card tab-content" style="display: none;">
            <h2>🎮 Bàn Điều Khiển Nhanh</h2>
            <input type="text" id="commandInput" class="input-box" placeholder="Gõ lệnh giọng nói (ví dụ: mở vtv1, bật chế độ đi ngủ)...">
            <button class="btn-primary" style="margin-bottom: 10px;" onclick="sendCommand()">🚀 Gửi Lệnh</button>
            <div style="display: flex; gap: 8px;">
                <button class="btn-primary" style="background: #334155;" onclick="sendDirectCmd('quay lại')">↩️ Quay Lại</button>
                <button class="btn-primary" style="background: #334155;" onclick="sendDirectCmd('tăng âm lượng')">🔊 Tăng Vol</button>
                <button class="btn-primary" style="background: #334155;" onclick="sendDirectCmd('giảm âm lượng')">🔉 Giảm Vol</button>
            </div>
            <div id="remoteStatus" class="status-toast"></div>
        </div>
    </div>

    <script>
        function switchTab(btn, name) {
            document.querySelectorAll('.tab-content').forEach(el => el.style.display = 'none');
            document.querySelectorAll('.tab-btn').forEach(el => el.classList.remove('active'));
            document.getElementById('tab-' + name).style.display = 'block';
            btn.classList.add('active');
        }

        function showToast(id, msg) {
            const el = document.getElementById(id);
            el.innerText = msg;
            el.style.display = 'block';
            setTimeout(() => { el.style.display = 'none'; }, 3500);
        }

        function previewSelectedPhoto() {
            const input = document.getElementById('photoFile');
            const preview = document.getElementById('photoPreview');
            if (input.files && input.files[0]) {
                const reader = new FileReader();
                reader.onload = e => { preview.src = e.target.result; preview.style.display = 'block'; };
                reader.readAsDataURL(input.files[0]);
            }
        }

        function compressImageToJpegBlob(file, maxDim, quality) {
            return new Promise((resolve, reject) => {
                const img = new Image();
                const objUrl = URL.createObjectURL(file);
                img.onload = () => {
                    URL.revokeObjectURL(objUrl);
                    let w = img.naturalWidth || img.width;
                    let h = img.naturalHeight || img.height;
                    if (w > maxDim || h > maxDim) {
                        const scale = maxDim / Math.max(w, h);
                        w = Math.round(w * scale);
                        h = Math.round(h * scale);
                    }
                    const canvas = document.createElement('canvas');
                    canvas.width = w;
                    canvas.height = h;
                    const ctx = canvas.getContext('2d');
                    ctx.drawImage(img, 0, 0, w, h);
                    canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('Compress failed')), 'image/jpeg', quality);
                };
                img.onerror = () => { URL.revokeObjectURL(objUrl); reject(new Error('Invalid image')); };
                img.src = objUrl;
            });
        }

        async function uploadPhoto() {
            const input = document.getElementById('photoFile');
            if (!input.files || !input.files[0]) {
                alert('Vui lòng chọn ảnh trước!');
                return;
            }
            const file = input.files[0];
            showToast('photoStatus', '⏳ Đang nén siêu tốc & truyền ảnh lên Tivi...');
            try {
                const jpegBlob = await compressImageToJpegBlob(file, 1920, 0.86);
                const resp = await fetch('/api/cast_photo', {
                    method: 'POST',
                    headers: { 'Content-Type': 'image/jpeg' },
                    body: jpegBlob
                });
                if (resp.ok) showToast('photoStatus', '✅ Đã chiếu ảnh Full HD lên Tivi thành công!');
                else showToast('photoStatus', '❌ Lỗi truyền ảnh: ' + resp.status);
            } catch (err) {
                showToast('photoStatus', '❌ Lỗi truyền ảnh: ' + err.message);
            }
        }

        async function uploadLocalVideo() {
            const input = document.getElementById('videoFile');
            if (!input.files || !input.files[0]) {
                alert('Vui lòng chọn video từ điện thoại!');
                return;
            }
            const file = input.files[0];
            if (file.size > 150 * 1024 * 1024) {
                alert('Video vượt quá 150MB. Hãy dùng nút Truyền / Smart View trong Bộ sưu tập!');
                return;
            }
            showToast('videoStatus', '⏳ Đang gửi video (' + Math.round(file.size / 1048576) + 'MB) lên Tivi...');
            try {
                const resp = await fetch('/api/upload_video?title=' + encodeURIComponent(file.name || 'Video từ điện thoại'), {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/octet-stream' },
                    body: file
                });
                if (resp.ok) showToast('videoStatus', '✅ Tivi đang phát video từ điện thoại!');
                else showToast('videoStatus', '❌ Lỗi tải video: ' + resp.status);
            } catch (e) {
                showToast('videoStatus', '❌ Lỗi mạng: ' + e.message);
            }
        }

        async function castVideo() {
            const url = document.getElementById('videoUrlInput').value.trim();
            if (!url) { alert('Vui lòng nhập link video!'); return; }
            showToast('videoStatus', '⏳ Đang gửi link video sang Tivi...');
            try {
                const resp = await fetch('/api/cast_video', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ url: url, title: 'Video chia sẻ' })
                });
                if (resp.ok) showToast('videoStatus', '✅ Tivi đang mở video!');
                else showToast('videoStatus', '❌ Lỗi: ' + resp.status);
            } catch (e) { showToast('videoStatus', '❌ Lỗi: ' + e.message); }
        }

        async function sendCustomNotify() {
            const text = document.getElementById('notifyTextInput').value.trim();
            if (!text) return;
            try {
                const resp = await fetch('/api/notify', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ message: text })
                });
                if (resp.ok) showToast('hudStatus', '✅ Đã hiện thông báo lên Tivi 4s!');
                else showToast('hudStatus', '❌ Lỗi: ' + resp.status);
            } catch (e) { showToast('hudStatus', '❌ Lỗi: ' + e.message); }
        }

        async function sendCommand() {
            const text = document.getElementById('commandInput').value.trim();
            if (!text) return;
            await sendDirectCmd(text);
        }

        async function sendDirectCmd(cmd) {
            try {
                const resp = await fetch('/api/command', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ command: cmd })
                });
                if (resp.ok) showToast('remoteStatus', '✅ Đã gửi: ' + cmd);
            } catch (e) { showToast('remoteStatus', '❌ Lỗi: ' + e.message); }
        }
    </script>
</body>
</html>
        """.trimIndent()

        sendHttpResponse(output, 200, "text/html; charset=utf-8", html.toByteArray(Charsets.UTF_8))
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverJob?.cancel()
        serverJob = null
    }
}
