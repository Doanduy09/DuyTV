package com.duytv.app.util

import android.content.Context
import android.util.Log
import com.duytv.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Máy chủ LAN Media, DLNA UPnP Digital Media Renderer & Web Control Hub nhúng siêu nhẹ (Port 8888).
 * Cho phép điện thoại Samsung, iPhone, Xiaomi, PC trong cùng mạng Wi-Fi:
 * 1. Tự động hiển thị tên "DuyTV (Tivi Sharp)" trên điện thoại (Samsung Smart View, Gallery "Truyền", DLNA).
 * 2. Bắn trực tiếp ảnh chụp (WebP/JPEG) lên màn hình TV với độ trễ < 30ms (0ms Internet, 0 tốn 4G).
 * 3. Bắn link video MP4/HLS phát trực tiếp bằng phần cứng MediaCodec trên Tivi Sharp.
 * 4. Gửi thông báo / lời nhắc việc gia đình HUD mờ 4s không gián đoạn video.
 * 5. Bàn điều khiển Web Remote mượt mà ngay trên trình duyệt điện thoại không cần cài app!
 */
object LanMediaServer {
    private const val TAG = "LanMediaServer"
    const val PORT = 8888

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start(context: Context) {
        if (serverJob != null && serverJob?.isActive == true) return

        serverJob = scope.launch {
            while (isActive) {
                try {
                    serverSocket = ServerSocket(PORT).apply {
                        reuseAddress = true
                    }
                    Log.i(TAG, "DuyTV LAN Media Server đang lắng nghe tại cổng HTTP $PORT")
                    while (isActive && serverSocket?.isClosed == false) {
                        val client = serverSocket?.accept() ?: break
                        launch { handleClient(context, client) }
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

    private fun handleClient(context: Context, socket: Socket) {
        try {
            socket.soTimeout = 8000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawPath = parts[1]

            // Đọc Headers
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val colonIdx = line!!.indexOf(":")
                if (colonIdx > 0) {
                    headers[line!!.substring(0, colonIdx).trim().lowercase()] =
                        line!!.substring(colonIdx + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val contentType = headers["content-type"] ?: ""
            val soapAction = headers["soapaction"] ?: ""

            when {
                // Trang Web Remote & Bắn Media chính
                method == "GET" && (rawPath == "/" || rawPath.startsWith("/index")) -> {
                    serveWebHubHtml(output)
                }

                // Trạng thái hệ thống
                method == "GET" && rawPath == "/api/status" -> {
                    val resp = "{\"status\":\"ok\",\"app\":\"DuyTV\",\"version\":\"2.6.4\",\"playing\":${MainActivity.isVideoActivelyPlaying}}"
                    sendHttpResponse(output, 200, "application/json; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
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
                    val body = readBodyStringFromReader(reader, contentLength)
                    handleUpnpAvTransportControl(soapAction, body, output)
                }

                method == "POST" && rawPath.startsWith("/upnp/control/RenderingControl") -> {
                    val body = readBodyStringFromReader(reader, contentLength)
                    handleUpnpRenderingControl(soapAction, body, output)
                }

                method == "POST" && rawPath.startsWith("/upnp/control/ConnectionManager") -> {
                    val body = readBodyStringFromReader(reader, contentLength)
                    handleUpnpConnectionManager(soapAction, body, output)
                }

                // Bắn ảnh (POST JSON Base64 hoặc Raw bytes)
                method == "POST" && rawPath.startsWith("/api/cast_photo") -> {
                    handleCastPhoto(context, reader, input, contentLength, contentType, output)
                }

                // Bắn link video (POST JSON)
                method == "POST" && rawPath.startsWith("/api/cast_video") -> {
                    val body = readBodyStringFromReader(reader, contentLength)
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val url = json.optString("url")
                    val title = json.optString("title", "Video từ điện thoại")
                    if (url.isNotBlank()) {
                        MainActivity.openCastVideo(url, title)
                        sendHttpResponse(output, 200, "application/json", "{\"status\":\"ok\"}".toByteArray())
                    } else {
                        sendHttpResponse(output, 400, "application/json", "{\"error\":\"Missing url\"}".toByteArray())
                    }
                }

                // Gửi thông báo gia đình (POST JSON)
                method == "POST" && rawPath.startsWith("/api/notify") -> {
                    val body = readBodyStringFromReader(reader, contentLength)
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val message = json.optString("message").ifBlank { json.optString("msg") }
                    if (message.isNotBlank()) {
                        MainActivity.showFamilyNotification(message)
                        sendHttpResponse(output, 200, "application/json", "{\"status\":\"ok\"}".toByteArray())
                    } else {
                        sendHttpResponse(output, 400, "application/json", "{\"error\":\"Missing message\"}".toByteArray())
                    }
                }

                // Điều khiển nhanh / lệnh giọng nói từ Web
                method == "POST" && rawPath.startsWith("/api/command") -> {
                    val body = readBodyStringFromReader(reader, contentLength)
                    val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
                    val cmd = json.optString("command").ifBlank { json.optString("raw_text") }
                    if (cmd.isNotBlank()) {
                        MainActivity.executeRemoteCommand(cmd, context)
                        sendHttpResponse(output, 200, "application/json", "{\"status\":\"ok\",\"command\":\"$cmd\"}".toByteArray())
                    } else {
                        sendHttpResponse(output, 400, "application/json", "{\"error\":\"Missing command\"}".toByteArray())
                    }
                }

                // GET API fallback tiện dụng
                method == "GET" && rawPath.startsWith("/api/notify?") -> {
                    val query = rawPath.substringAfter("?")
                    val params = parseQueryParams(query)
                    val msg = params["msg"] ?: params["message"] ?: ""
                    if (msg.isNotBlank()) {
                        MainActivity.showFamilyNotification(msg)
                        sendHttpResponse(output, 200, "application/json", "{\"status\":\"ok\"}".toByteArray())
                    } else {
                        sendHttpResponse(output, 400, "application/json", "{\"error\":\"Empty msg\"}".toByteArray())
                    }
                }

                method == "GET" && rawPath.startsWith("/api/command?") -> {
                    val query = rawPath.substringAfter("?")
                    val params = parseQueryParams(query)
                    val cmd = params["cmd"] ?: params["command"] ?: ""
                    if (cmd.isNotBlank()) {
                        MainActivity.executeRemoteCommand(cmd, context)
                        sendHttpResponse(output, 200, "application/json", "{\"status\":\"ok\"}".toByteArray())
                    } else {
                        sendHttpResponse(output, 400, "application/json", "{\"error\":\"Empty cmd\"}".toByteArray())
                    }
                }

                else -> {
                    sendHttpResponse(output, 404, "text/plain", "Not Found".toByteArray())
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun handleCastPhoto(
        context: Context,
        reader: BufferedReader,
        input: InputStream,
        length: Int,
        contentType: String,
        output: OutputStream
    ) {
        try {
            val photoFile = File(context.cacheDir, "cast_photo_latest.jpg")
            if (contentType.contains("application/json")) {
                val body = readBodyStringFromReader(reader, length)
                val json = JSONObject(body)
                val b64 = json.optString("base64").ifBlank { json.optString("image_base64") }
                val title = json.optString("title", "Ảnh từ điện thoại")
                if (b64.isNotBlank()) {
                    val cleanB64 = if (b64.contains(",")) b64.substringAfter(",") else b64
                    val decodedBytes = android.util.Base64.decode(cleanB64, android.util.Base64.DEFAULT)
                    FileOutputStream(photoFile).use { it.write(decodedBytes) }
                    MainActivity.openCastPhoto(photoFile.absolutePath, title)
                    sendHttpResponse(output, 200, "application/json", "{\"status\":\"ok\"}".toByteArray())
                    return
                }
            }

            // Raw bytes upload từ reader chars
            val body = readBodyStringFromReader(reader, length)
            FileOutputStream(photoFile).use { fos ->
                fos.write(body.toByteArray(Charsets.ISO_8859_1))
            }

            MainActivity.openCastPhoto(photoFile.absolutePath, "Ảnh từ điện thoại")
            sendHttpResponse(output, 200, "application/json", "{\"status\":\"ok\"}".toByteArray())
        } catch (e: Exception) {
            sendHttpResponse(output, 500, "application/json", "{\"error\":\"${e.message}\"}".toByteArray())
        }
    }

    private fun readBodyStringFromReader(reader: BufferedReader, length: Int): String {
        if (length <= 0) return ""
        val chars = CharArray(length)
        var total = 0
        while (total < length) {
            val r = reader.read(chars, total, length - total)
            if (r == -1) break
            total += r
        }
        return String(chars, 0, total)
    }

    private fun extractXmlTag(xml: String, tag: String): String {
        val open = "<$tag"
        val startIdx = xml.indexOf(open)
        if (startIdx == -1) return ""
        val gtIdx = xml.indexOf(">", startIdx)
        if (gtIdx == -1) return ""
        val close = "</$tag>"
        val endIdx = xml.indexOf(close, gtIdx)
        if (endIdx == -1) return ""
        return xml.substring(gtIdx + 1, endIdx).trim()
    }

    private fun serveUpnpDeviceDescription(output: OutputStream) {
        val localIp = DlnaMediaRenderer.getLocalIpv4Address()
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
    <modelNumber>2.6.4</modelNumber>
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
                    val isImage = uri.endsWith(".jpg", true) || uri.endsWith(".jpeg", true) ||
                            uri.endsWith(".png", true) || uri.endsWith(".webp", true) ||
                            uri.endsWith(".heic", true) || meta.contains("imageItem", true)
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
                val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:PlayResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>
  </s:Body>
</s:Envelope>""".trimIndent()
                sendHttpResponse(output, 200, "text/xml; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
            }
            soapAction.contains("Stop") || body.contains("<u:Stop") -> {
                val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:StopResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/>
  </s:Body>
</s:Envelope>""".trimIndent()
                sendHttpResponse(output, 200, "text/xml; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
            }
            soapAction.contains("GetTransportInfo") || body.contains("GetTransportInfo") -> {
                val state = if (MainActivity.isVideoActivelyPlaying) "PLAYING" else "STOPPED"
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

    private fun handleUpnpRenderingControl(soapAction: String, body: String, output: OutputStream) {
        val resp = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
  <s:Body>
    <u:GetVolumeResponse xmlns:u="urn:schemas-upnp-org:service:RenderingControl:1">
      <CurrentVolume>50</CurrentVolume>
    </u:GetVolumeResponse>
  </s:Body>
</s:Envelope>""".trimIndent()
        sendHttpResponse(output, 200, "text/xml; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
    }

    private fun handleUpnpConnectionManager(soapAction: String, body: String, output: OutputStream) {
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

    private fun sendHttpResponse(output: OutputStream, statusCode: Int, contentType: String, data: ByteArray) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Error"
        }
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${data.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
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
            Mở ảnh/video trong <b>Bộ sưu tập Samsung / Xiaomi / Android</b> $\rightarrow$ Bấm nút <b>Truyền / Smart View</b> $\rightarrow$ Chọn <b>DuyTV (Tivi Sharp)</b>!
        </div>

        <div class="tabs">
            <button class="tab-btn active" onclick="switchTab('photo')">📷 Chiếu Ảnh</button>
            <button class="tab-btn" onclick="switchTab('video')">🎬 Chiếu Video</button>
            <button class="tab-btn" onclick="switchTab('notify')">🔔 Nhắc Việc</button>
            <button class="tab-btn" onclick="switchTab('remote')">🎮 Remote</button>
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
            <h2>🎬 Bắn Link Video Phát Ngay</h2>
            <input type="url" id="videoUrlInput" class="input-box" placeholder="Dán link video MP4 / M3U8 tại đây...">
            <button class="btn-primary" onclick="castVideo()">▶️ Phát Trực Tiếp Trên TV</button>
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
        function switchTab(name) {
            document.querySelectorAll('.tab-content').forEach(el => el.style.display = 'none');
            document.querySelectorAll('.tab-btn').forEach(el => el.classList.remove('active'));
            document.getElementById('tab-' + name).style.display = 'block';
            event.target.classList.add('active');
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

        async function uploadPhoto() {
            const input = document.getElementById('photoFile');
            if (!input.files || !input.files[0]) {
                alert('Vui lòng chọn ảnh trước!');
                return;
            }
            const file = input.files[0];
            showToast('photoStatus', '⏳ Đang truyền ảnh sang Tivi...');
            const reader = new FileReader();
            reader.onload = async function(e) {
                try {
                    const base64Data = e.target.result;
                    const resp = await fetch('/api/cast_photo', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ base64: base64Data, title: file.name || 'Ảnh từ điện thoại' })
                    });
                    if (resp.ok) showToast('photoStatus', '✅ Đã chiếu ảnh lên Tivi thành công!');
                    else showToast('photoStatus', '❌ Lỗi truyền ảnh: ' + resp.status);
                } catch (err) {
                    showToast('photoStatus', '❌ Lỗi mạng: ' + err.message);
                }
            };
            reader.readAsDataURL(file);
        }

        async function castVideo() {
            const url = document.getElementById('videoUrlInput').value.trim();
            if (!url) { alert('Vui lòng nhập link video!'); return; }
            showToast('videoStatus', '⏳ Đang gửi video sang Tivi...');
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
