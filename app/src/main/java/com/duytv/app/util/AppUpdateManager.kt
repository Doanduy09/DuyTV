package com.duytv.app.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.duytv.app.BuildConfig
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppVersionInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    val apkUrlLan: String = "http://192.168.1.189:8888/DuyTV-latest.apk",
    val apkUrlCloud: String = "https://github.com/Doanduy09/DuyTV/releases/latest/download/DuyTV-Full-Latest.apk",
    val releaseNotes: String = "",
    val sha256: String? = null
)

object AppUpdateManager {
    private const val LAN_VERSION_URL = "http://192.168.1.189:8888/version.json"
    private const val CLOUD_VERSION_URL = "https://raw.githubusercontent.com/Doanduy09/DuyTV/main/config/version.json"
    private const val FALLBACK_RAW_APK_URL = "https://raw.githubusercontent.com/Doanduy09/DuyTV/main/release/DuyTV-latest.apk"

    private val gson = Gson()

    private val lanClient: OkHttpClient by lazy {
        SharedHttpClients.newClientBuilder()
            .connectTimeout(1200, TimeUnit.MILLISECONDS)
            .readTimeout(1200, TimeUnit.MILLISECONDS)
            .build()
    }

    private val cloudClient: OkHttpClient by lazy {
        SharedHttpClients.newClientBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private val downloadClient: OkHttpClient by lazy {
        SharedHttpClients.newClientBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    internal fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(16 * 1024)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun verifyOptionalSha256(file: File, expectedSha256: String?): Boolean {
        val cleanExpected = expectedSha256?.trim()?.lowercase()
        if (cleanExpected.isNullOrBlank()) return true
        return computeFileSha256(file).equals(cleanExpected, ignoreCase = true)
    }

    private const val PREFS_NAME = "duytv_update_prefs"
    private const val KEY_LAST_CHECK_TIME = "last_check_timestamp"
    private const val KEY_IGNORED_VERSION = "ignored_version_code"
    private const val COOLDOWN_HOURS_MS = 12 * 60 * 60 * 1000L // Tối thiểu 12 tiếng giữa 2 lần tự động kiểm tra

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun shouldAutoCheck(context: Context): Boolean {
        val lastCheck = getPrefs(context).getLong(KEY_LAST_CHECK_TIME, 0L)
        val now = System.currentTimeMillis()
        return (now - lastCheck) >= COOLDOWN_HOURS_MS
    }

    fun recordCheckSuccess(context: Context) {
        getPrefs(context).edit()
            .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
            .apply()
    }

    fun setIgnoredVersion(context: Context, versionCode: Int) {
        getPrefs(context).edit()
            .putInt(KEY_IGNORED_VERSION, versionCode)
            .apply()
    }

    fun isVersionIgnored(context: Context, versionCode: Int): Boolean {
        return getPrefs(context).getInt(KEY_IGNORED_VERSION, -1) == versionCode
    }

    fun getCurrentVersionCode(context: Context? = null): Int {
        return try {
            BuildConfig.VERSION_CODE
        } catch (_: Exception) {
            try {
                if (context != null) {
                    val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        pInfo.longVersionCode.toInt()
                    } else {
                        @Suppress("DEPRECATION")
                        pInfo.versionCode
                    }
                } else 73
            } catch (_: Exception) {
                73
            }
        }
    }

    fun getCurrentVersionName(context: Context? = null): String {
        return try {
            BuildConfig.VERSION_NAME
        } catch (_: Exception) {
            try {
                context?.packageManager?.getPackageInfo(context.packageName, 0)?.versionName ?: "2.5.7"
            } catch (_: Exception) {
                "2.5.7"
            }
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
            "$FALLBACK_RAW_APK_URL?t=${System.currentTimeMillis()}",
            versionInfo.apkUrlLan.takeIf { it.isNotBlank() }?.let { "$it?t=${System.currentTimeMillis()}" }
        ).distinct()

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
                    if (outFile.exists() && outFile.length() > 1_000_000L && verifyOptionalSha256(outFile, versionInfo.sha256)) {
                        downloadedSuccess = true
                    } else if (outFile.exists()) {
                        android.util.Log.w("AppUpdateManager", "OTA APK invalid size or SHA-256 mismatch from $url (size=${outFile.length()})")
                        outFile.delete()
                    }
                }
                if (downloadedSuccess) break
            } catch (e: Exception) {
                android.util.Log.w("AppUpdateManager", "OTA download failed from $url: ${e.message}")
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
