package com.duytv.app.util

import android.content.Context
import android.util.Log
import com.duytv.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Máy chủ LAN siêu nhẹ (Zero-Allocation / Anti-Bloat) lắng nghe cổng TCP 8990.
 * Tiếp nhận các câu lệnh giọng nói từ Cục Voice Puck ESP32 hoặc Điện Thoại Samsung gửi qua Wi-Fi.
 */
object LanCommandReceiver {
    private const val TAG = "LanCommandReceiver"
    const val PORT = 8990

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start(context: Context) {
        if (serverJob != null && serverJob?.isActive == true) return
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(PORT).apply {
                    reuseAddress = true
                }
                Log.i(TAG, "DuyTV LAN Command Receiver đang lắng nghe tại cổng TCP $PORT")
                while (serverSocket?.isClosed == false) {
                    val clientSocket = serverSocket?.accept() ?: break
                    launch { handleClient(context, clientSocket) }
                }
            } catch (e: Exception) {
                Log.d(TAG, "ServerSocket exception: ${e.message}")
            }
        }
    }

    private fun handleClient(context: Context, socket: Socket) {
        try {
            socket.soTimeout = 2500
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            val line = reader.readLine()
            if (!line.isNullOrBlank()) {
                val json = try { JSONObject(line) } catch (_: Exception) { null }
                val rawText = json?.optString("raw_text")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("command")?.takeIf { it.isNotBlank() }
                    ?: line.trim()

                if (rawText.isNotBlank()) {
                    Log.i(TAG, "Nhận lệnh Wi-Fi nội bộ: '$rawText'")
                    MainActivity.executeRemoteCommand(rawText, context)
                }
                writer.write("{\"status\":\"ok\",\"command\":\"$rawText\"}\n")
                writer.flush()
            }
            socket.close()
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverJob?.cancel()
        serverJob = null
    }
}
