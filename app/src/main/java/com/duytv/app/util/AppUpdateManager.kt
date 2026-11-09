package com.duytv.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class AppVersionInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val apkUrlLan: String = "http://192.168.1.189:8888/DuyTV-latest.apk",
    val apkUrlCloud: String = "https://raw.githubusercontent.com/Doanduy09/DuyTV/main/release/DuyTV-latest.apk",
    val releaseNotes: String = ""
)

object AppUpdateManager {
    private const val LAN_VERSION_URL = "http://192.168.1.189:8888/version.json"
    private const val CLOUD_VERSION_URL = "https://raw.githubusercontent.com/Doanduy09/DuyTV/main/config/version.json"

    private val gson = Gson()

    private val lanClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .build()

    private val cloudClient = OkHttpClient.Builder()
        .dns(HlsAdBlockEngine.ipv4PreferredDns)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .dns(HlsAdBlockEngine.ipv4PreferredDns)
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            1
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.0"
        } catch (_: Exception) {
            "1.0.0"
        }
    }

    // [✅ ĐÃ CHUẨN - CẤM SỬA]: Ưu tiên kiểm tra GitHub Cloud trước để luôn lấy bản mới nhất tức thì,
    // tránh nghẽn 1.5s-4s khi máy chủ LAN (192.168.1.189:8888) đang tắt hoặc chứa bản cũ.
    suspend fun checkForUpdate(): AppVersionInfo? = withContext(Dispatchers.IO) {
        var cloudVersion: AppVersionInfo? = null
        var lanVersion: AppVersionInfo? = null

        // 1. Kiểm tra GitHub Cloud
        try {
            val req = Request.Builder()
                .url("$CLOUD_VERSION_URL?t=${System.currentTimeMillis()}")
                .header("Cache-Control", "no-cache")
                .build()
            cloudClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string()
                    if (!body.isNullOrBlank()) {
                        val parsed = gson.fromJson(body, AppVersionInfo::class.java)
                        if (parsed != null && parsed.versionCode > 0) {
                            cloudVersion = parsed
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Kiểm tra máy chủ LAN nội bộ (tránh trễ cache CDN 5 phút của GitHub Raw)
        try {
            val req = Request.Builder()
                .url("$LAN_VERSION_URL?t=${System.currentTimeMillis()}")
                .header("Cache-Control", "no-cache")
                .build()
            lanClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string()
                    if (!body.isNullOrBlank()) {
                        val parsed = gson.fromJson(body, AppVersionInfo::class.java)
                        if (parsed != null && parsed.versionCode > 0) {
                            lanVersion = parsed
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Luôn chọn bản có versionCode cao nhất giữa Cloud và LAN
        when {
            lanVersion != null && cloudVersion != null ->
                if (lanVersion!!.versionCode >= cloudVersion!!.versionCode) lanVersion else cloudVersion
            lanVersion != null -> lanVersion
            else -> cloudVersion
        }
    }

    suspend fun downloadAndInstallApk(
        context: Context,
        versionInfo: AppVersionInfo,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        val outFile = File(context.cacheDir, "DuyTV-ota-update.apk")
        try {
            if (outFile.exists()) outFile.delete()
        } catch (_: Exception) {}

        val candidateUrls = listOfNotNull(
            versionInfo.apkUrlCloud.takeIf { it.isNotBlank() }?.let { "$it?t=${System.currentTimeMillis()}" },
            versionInfo.apkUrlLan.takeIf { it.isNotBlank() }?.let { "$it?t=${System.currentTimeMillis()}" }
        )

        var downloadedSuccess = false
        for (url in candidateUrls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .build()
                downloadClient.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) return@use
                    val body = res.body ?: return@use
                    val totalBytes = body.contentLength()
                    body.byteStream().use { input ->
                        FileOutputStream(outFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var bytesRead: Int
                            var totalRead = 0L
                            var lastProgress = -1
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead
                                if (totalBytes > 0) {
                                    val pct = ((totalRead * 100L) / totalBytes).toInt().coerceIn(0, 100)
                                    if (pct != lastProgress) {
                                        lastProgress = pct
                                        withContext(Dispatchers.Main) { onProgress(pct) }
                                    }
                                }
                            }
                            output.flush()
                        }
                    }
                    if (outFile.exists() && outFile.length() > 1_000_000L) {
                        downloadedSuccess = true
                    }
                }
                if (downloadedSuccess) break
            } catch (_: Exception) {
                // Tự động chuyển sang URL dự phòng tiếp theo (Cloud)
            }
        }

        if (!downloadedSuccess || !outFile.exists()) {
            withContext(Dispatchers.Main) {
                onError("Không thể tải gói cập nhật. Vui lòng kiểm tra kết nối mạng Wi-Fi.")
            }
            return@withContext
        }

        withContext(Dispatchers.Main) {
            onProgress(100)
            triggerInstallIntent(context, outFile, onError)
        }
    }

    fun triggerInstallIntent(context: Context, apkFile: File, onError: (String) -> Unit) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    try {
                        val permIntent = Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(permIntent)
                        onError("Vui lòng bật 'Cho phép cài đặt từ nguồn này' 1 lần duy nhất rồi bấm Cập nhật lại.")
                        return
                    } catch (_: Exception) {
                        // Một số Android TV không có màn hình Settings riêng, tiếp tục gọi bộ cài trực tiếp
                    }
                }
            }

            val apkUri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            @Suppress("DEPRECATION")
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = apkUri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, false)
            }

            try {
                context.startActivity(installIntent)
            } catch (_: Exception) {
                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(apkUri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(viewIntent)
            }
        } catch (e: Exception) {
            onError("Không thể mở trình cài đặt: ${e.localizedMessage ?: "Lỗi hệ thống"}")
        }
    }
}
