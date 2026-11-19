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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Máy chủ LAN Media & Web Control nhúng siêu nhẹ (Port 8888).
 * Cho phép điện thoại Samsung, iPhone, PC trong cùng mạng Wi-Fi:
 * 1. Bắn trực tiếp ảnh chụp (WebP/JPEG) lên màn hình TV với độ trễ < 30ms (0ms Internet, 0 tốn 4G).
 * 2. Bắn link video MP4/HLS phát trực tiếp bằng phần cứng MediaCodec trên Tivi Sharp.
 * 3. Gửi thông báo / lời nhắc việc gia đình HUD mờ 3s không gián đoạn video.
 * 4. Bàn điều khiển Web Remote mượt mà ngay trên trình duyệt điện thoại không cần cài app!
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

            when {
                // Trang Web Remote & Bắn Media chính
                method == "GET" && (rawPath == "/" || rawPath.startsWith("/index")) -> {
                    serveWebHubHtml(output)
                }

                // Trạng thái hệ thống
                method == "GET" && rawPath == "/api/status" -> {
                    val resp = "{\"status\":\"ok\",\"app\":\"DuyTV\",\"version\":\"2.6.3\",\"playing\":${MainActivity.isVideoActivelyPlaying}}"
                    sendHttpResponse(output, 200, "application/json; charset=utf-8", resp.toByteArray(Charsets.UTF_8))
                }

                // Bắn ảnh (POST raw bytes hoặc multipart / json base64)
                method == "POST" && rawPath.startsWith("/api/cast_photo") -> {
                    handleCastPhoto(context, input, contentLength, contentType, output)
                }

                // Bắn link video (POST JSON)
                method == "POST" && rawPath.startsWith("/api/cast_video") -> {
                    val body = readBodyString(input, contentLength)
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
                    val body = readBodyString(input, contentLength)
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
                    val body = readBodyString(input, contentLength)
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

    private fun handleCastPhoto(context: Context, input: InputStream, length: Int, contentType: String, output: OutputStream) {
        try {
            val photoFile = File(context.cacheDir, "cast_photo_latest.jpg")
            if (contentType.contains("application/json")) {
                val body = readBodyString(input, length)
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

            // Raw bytes upload
            val buffer = ByteArray(8192)
            var bytesRemaining = length
            FileOutputStream(photoFile).use { fos ->
                while (bytesRemaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, bytesRemaining))
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    bytesRemaining -= read
                }
            }

            MainActivity.openCastPhoto(photoFile.absolutePath, "Ảnh từ điện thoại")
            sendHttpResponse(output, 200, "application/json", "{\"status\":\"ok\"}".toByteArray())
        } catch (e: Exception) {
            sendHttpResponse(output, 500, "application/json", "{\"error\":\"${e.message}\"}".toByteArray())
        }
    }

    private fun readBodyString(input: InputStream, length: Int): String {
        if (length <= 0) return ""
        val bytes = ByteArray(length)
        var totalRead = 0
        while (totalRead < length) {
            val read = input.read(bytes, totalRead, length - totalRead)
            if (read == -1) break
            totalRead += read
        }
        return String(bytes, 0, totalRead, Charsets.UTF_8)
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
        val html = """
<!DOCTYPE html>
<html lang="vi">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>DuyTV LAN Cast & Remote</title>
    <style>
        * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
        body { background: #0b0f19; color: #f8fafc; padding: 16px; min-height: 100vh; }
        .header { display: flex; align-items: center; justify-content: space-between; padding-bottom: 14px; border-bottom: 1px solid #1e293b; margin-bottom: 16px; }
        .header h1 { font-size: 1.15rem; color: #38bdf8; font-weight: 700; display: flex; align-items: center; gap: 8px; }
        .live-badge { background: #10b981; color: #000; font-size: 0.7rem; font-weight: 800; padding: 2px 8px; border-radius: 12px; }
        .tabs { display: flex; gap: 8px; margin-bottom: 16px; background: #1e293b; padding: 4px; border-radius: 12px; }
        .tab-btn { flex: 1; padding: 10px; background: transparent; border: none; color: #94a3b8; font-size: 0.85rem; font-weight: 600; border-radius: 8px; cursor: pointer; transition: all 0.2s; }
        .tab-btn.active { background: #0284c7; color: #fff; box-shadow: 0 2px 8px rgba(2, 132, 199, 0.4); }
        .card { background: #131d31; border: 1px solid #1e293b; border-radius: 14px; padding: 16px; margin-bottom: 14px; box-shadow: 0 4px 12px rgba(0,0,0,0.3); }
        .card h2 { font-size: 0.95rem; color: #e2e8f0; margin-bottom: 12px; font-weight: 600; display: flex; align-items: center; gap: 6px; }
        .input-box { width: 100%; padding: 12px; background: #0f172a; border: 1px solid #334155; border-radius: 10px; color: #fff; font-size: 0.95rem; margin-bottom: 12px; outline: none; }
        .input-box:focus { border-color: #38bdf8; }
        .btn-primary { width: 100%; padding: 13px; background: linear-gradient(135deg, #0284c7, #0369a1); border: none; border-radius: 10px; color: #fff; font-size: 0.95rem; font-weight: 700; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px; }
        .btn-primary:active { transform: scale(0.98); }
        .btn-green { background: linear-gradient(135deg, #10b981, #059669); }
        .btn-purple { background: linear-gradient(135deg, #8b5cf6, #6d28d9); }
        .chips-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; margin-bottom: 12px; }
        .chip { background: #1e293b; border: 1px solid #334155; padding: 10px 8px; border-radius: 8px; color: #cbd5e1; font-size: 0.82rem; font-weight: 600; cursor: pointer; text-align: center; }
        .chip:active { background: #38bdf8; color: #000; }
        .dpad-container { display: flex; flex-direction: column; align-items: center; gap: 8px; margin: 16px 0; }
        .dpad-row { display: flex; gap: 8px; }
        .dpad-btn { width: 64px; height: 50px; background: #1e293b; border: 1px solid #334155; border-radius: 10px; color: #fff; font-size: 1.1rem; font-weight: 700; cursor: pointer; display: flex; align-items: center; justify-content: center; }
        .dpad-btn.ok { width: 68px; background: #0284c7; border-color: #38bdf8; }
        .dpad-btn:active { background: #38bdf8; color: #000; }
        .status-toast { padding: 10px; background: #064e3b; border: 1px solid #059669; color: #a7f3d0; border-radius: 8px; font-size: 0.85rem; margin-top: 10px; text-align: center; display: none; }
        .preview-img { max-width: 100%; max-height: 200px; border-radius: 8px; margin-bottom: 10px; display: none; object-fit: contain; }
    </style>
</head>
<body>
    <div class="header">
        <h1>📺 DuyTV LAN Hub</h1>
        <span class="live-badge">SẴN SÀNG</span>
    </div>

    <div class="tabs">
        <button class="tab-btn active" onclick="switchTab('photo')">📷 Bắn Ảnh</button>
        <button class="tab-btn" onclick="switchTab('video')">🎬 Bắn Video</button>
        <button class="tab-btn" onclick="switchTab('hud')">🔔 Lời Nhắc</button>
        <button class="tab-btn" onclick="switchTab('remote')">🎙️ Remote</button>
    </div>

    <!-- TAB 1: BẮN ẢNH -->
    <div id="tab-photo" class="tab-content">
        <div class="card">
            <h2>📷 Bắn Ảnh Lên Tivi Tức Thì (< 30ms)</h2>
            <p style="font-size: 0.82rem; color: #94a3b8; margin-bottom: 12px;">Chọn bất kỳ ảnh nào trong máy để chiếu lên màn hình Tivi Sharp mà không cần Internet.</p>
            <input type="file" id="photoFile" accept="image/*" class="input-box" onchange="previewSelectedPhoto()">
            <img id="photoPreview" class="preview-img">
            <button class="btn-primary btn-green" onclick="uploadPhoto()">🚀 BẮN LÊN TIVI NGAY</button>
            <div id="photoStatus" class="status-toast"></div>
        </div>
    </div>

    <!-- TAB 2: BẮN VIDEO -->
    <div id="tab-video" class="tab-content" style="display: none;">
        <div class="card">
            <h2>🎬 Bắn Link Video MP4 / M3U8</h2>
            <p style="font-size: 0.82rem; color: #94a3b8; margin-bottom: 12px;">Dán link video từ điện thoại để Tivi phát giải mã phần cứng siêu mượt.</p>
            <input type="text" id="videoUrlInput" class="input-box" placeholder="http://.../video.mp4 hoặc .m3u8">
            <button class="btn-primary" onclick="castVideo()">▶️ PHÁT TRÊN TIVI</button>
            <div id="videoStatus" class="status-toast"></div>
        </div>
    </div>

    <!-- TAB 3: LỜI NHẮC GIA ĐÌNH -->
    <div id="tab-hud" class="tab-content" style="display: none;">
        <div class="card">
            <h2>🔔 Gửi Lời Nhắc / Thông Báo Gia Đình</h2>
            <p style="font-size: 0.82rem; color: #94a3b8; margin-bottom: 12px;">Thông báo sẽ nổi mờ 3 giây trên góc màn hình TV, không gián đoạn xem phim.</p>
            <input type="text" id="notifyTextInput" class="input-box" placeholder="Ví dụ: 8h tối nhớ uống thuốc mẹ nhé...">
            <div class="chips-grid">
                <div class="chip" onclick="quickNotify('💊 Mẹ ơi, 8h nhớ uống thuốc nhé')">💊 Mẹ uống thuốc</div>
                <div class="chip" onclick="quickNotify('🍚 Ăn cơm thôi cả nhà ơi!')">🍚 Ăn cơm thôi</div>
                <div class="chip" onclick="quickNotify('🌙 Đến giờ đi ngủ rồi cả nhà')">🌙 Đến giờ ngủ</div>
                <div class="chip" onclick="quickNotify('⚡ Có bão/mưa to, nhớ đóng cửa sổ')">⚡ Đóng cửa sổ</div>
            </div>
            <button class="btn-primary btn-purple" onclick="sendCustomNotify()">🔔 GỬI LÊN TIVI (3s)</button>
            <div id="hudStatus" class="status-toast"></div>
        </div>
    </div>

    <!-- TAB 4: REMOTE & GIỌNG NÓI -->
    <div id="tab-remote" class="tab-content" style="display: none;">
        <div class="card">
            <h2>🎙️ Điều Khiển & Ra Lệnh Cho TV</h2>
            <input type="text" id="commandInput" class="input-box" placeholder="Gõ lệnh: Bật VTV3, Phim Châu Tinh Trì, Đi ngủ...">
            <button class="btn-primary" onclick="sendCommand()" style="margin-bottom: 14px;">GỬI LỆNH</button>

            <div class="chips-grid">
                <div class="chip" onclick="sendDirectCmd('bật vtv3')">📺 VTV3 HD</div>
                <div class="chip" onclick="sendDirectCmd('bật vtv1')">📺 VTV1 HD</div>
                <div class="chip" onclick="sendDirectCmd('mở đài vĩnh long')">📺 Vĩnh Long 1</div>
                <div class="chip" onclick="sendDirectCmd('mở phim châu tinh trì')">🎬 Châu Tinh Trì</div>
                <div class="chip" onclick="sendDirectCmd('đi ngủ')">🌙 Chế Độ Đi Ngủ</div>
                <div class="chip" onclick="sendDirectCmd('về trang chủ')">🏠 Trang Chủ</div>
            </div>

            <div class="dpad-container">
                <button class="dpad-btn" onclick="sendDirectCmd('lên')">▲</button>
                <div class="dpad-row">
                    <button class="dpad-btn" onclick="sendDirectCmd('trái')">◀</button>
                    <button class="dpad-btn ok" onclick="sendDirectCmd('chọn')">OK</button>
                    <button class="dpad-btn" onclick="sendDirectCmd('phải')">▶</button>
                </div>
                <button class="dpad-btn" onclick="sendDirectCmd('xuống')">▼</button>
            </div>

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
            try {
                const resp = await fetch('/api/cast_photo', {
                    method: 'POST',
                    headers: { 'Content-Type': file.type || 'image/jpeg' },
                    body: file
                });
                if (resp.ok) showToast('photoStatus', '✅ Đã chiếu ảnh lên Tivi thành công!');
                else showToast('photoStatus', '❌ Lỗi truyền ảnh: ' + resp.status);
            } catch (err) {
                showToast('photoStatus', '❌ Lỗi mạng: ' + err.message);
            }
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

        async function quickNotify(text) {
            document.getElementById('notifyTextInput').value = text;
            await sendCustomNotify();
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
                if (resp.ok) showToast('hudStatus', '✅ Đã hiện thông báo lên Tivi 3s!');
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
