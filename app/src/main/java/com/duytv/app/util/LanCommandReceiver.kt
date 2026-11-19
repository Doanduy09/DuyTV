package com.duytv.app.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
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
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket

/**
 * Máy chủ LAN siêu nhẹ (Zero-Allocation / Anti-Bloat) lắng nghe cổng TCP 8990.
 * Tiếp nhận các câu lệnh giọng nói từ Cục Voice Puck ESP32 hoặc Điện Thoại Samsung gửi qua Wi-Fi.
 * Tích hợp Partial WakeLock + WifiLock + Watchdog tự phục hồi 24/7 + Đánh thức màn hình khi nhận lệnh.
 */
object LanCommandReceiver {
    private const val TAG = "LanCommandReceiver"
    const val PORT = 8990

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    fun start(context: Context) {
        if (serverJob != null && serverJob?.isActive == true) return

        // 1. Giữ CPU và Wi-Fi thức ở mức tiêu thụ điện cực thấp để nhận socket 24/7
        try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DuyTV:LanCommandWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L) // 24h
                }
            }
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiLock == null && wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DuyTV:LanCommandWifiLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể kích hoạt WakeLock/WifiLock: ${e.message}")
        }

        serverJob = scope.launch {
            while (isActive) {
                try {
                    serverSocket = ServerSocket(PORT).apply {
                        reuseAddress = true
                    }
                    Log.i(TAG, "DuyTV LAN Command Receiver đang lắng nghe tại cổng TCP $PORT (24/7 Watchdog Active)")
                    while (isActive && serverSocket?.isClosed == false) {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch { handleClient(context, clientSocket) }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "ServerSocket exception: ${e.message}. Khởi động lại sau 2.5s...")
                } finally {
                    try { serverSocket?.close() } catch (_: Exception) {}
                }
                delay(2500)
            }
        }
    }

    private fun handleClient(context: Context, socket: Socket) {
        try {
            socket.soTimeout = 3000
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
                    
                    // Đánh thức màn hình TV ngay lập tức nếu đang ngủ (Standby)
                    wakeUpScreenIfAsleep(context)

                    MainActivity.executeRemoteCommand(rawText, context)
                }
                writer.write("{\"status\":\"ok\",\"command\":\"$rawText\"}\n")
                writer.flush()
            }
            socket.close()
        } catch (_: Exception) {}
    }

    private fun wakeUpScreenIfAsleep(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            if (!pm.isInteractive) {
                @Suppress("DEPRECATION")
                val screenLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                    "DuyTV:ScreenInstantWakeUp"
                )
                screenLock.acquire(3000)
                screenLock.release()
                Log.i(TAG, "Đã đánh thức màn hình TV từ chế độ Standby!")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không thể đánh thức màn hình: ${e.message}")
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverJob?.cancel()
        serverJob = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
    }
}
