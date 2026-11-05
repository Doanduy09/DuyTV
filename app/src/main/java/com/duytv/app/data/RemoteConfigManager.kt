package com.duytv.app.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object RemoteConfigManager {
    private const val REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/Doanduy09/DuyTV/main/config/sources.json"
    private const val RAW_DOT_CONFIG_URL = "https://raw.githubusercontent.com/Doanduy09/DuyTV/main/.config"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    @Volatile
    private var currentConfig = RemoteConfig()

    suspend fun fetchRemoteConfig(): RemoteConfig = withContext(Dispatchers.IO) {
        // 1. Thử nạp cấu hình JSON đầy đủ
        try {
            val request = Request.Builder()
                .url(REMOTE_CONFIG_URL)
                .header("User-Agent", "DuyTV-AndroidTV/1.0")
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                response.body?.string()?.let { json ->
                    val parsed = gson.fromJson(json, RemoteConfig::class.java)
                    if (parsed != null) {
                        currentConfig = parsed
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Tự động đọc file .config dạng dòng đơn giản của Anh Duy
        try {
            val req = Request.Builder()
                .url(RAW_DOT_CONFIG_URL)
                .header("User-Agent", "DuyTV-AndroidTV/1.0")
                .build()
            val res = httpClient.newCall(req).execute()
            if (res.isSuccessful) {
                val text = res.body?.string() ?: ""
                val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
                for (line in lines) {
                    var clean = line
                    if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                        clean = "https://$clean"
                    }
                    if (clean.endsWith("/")) clean = clean.dropLast(1)
                    val lower = clean.lowercase()

                    when {
                        lower.contains("ophim") -> {
                            currentConfig = currentConfig.copy(
                                sources = currentConfig.sources.copy(
                                    ophim = currentConfig.sources.ophim.copy(apiBase = clean)
                                )
                            )
                        }
                        lower.contains("phimapi") -> {
                            currentConfig = currentConfig.copy(
                                primaryDomain = clean,
                                sources = currentConfig.sources.copy(
                                    kkphim = currentConfig.sources.kkphim.copy(apiBase = clean)
                                )
                            )
                        }
                        lower.contains("nguonc") || lower.contains("phim.nguon") -> {
                            currentConfig = currentConfig.copy(
                                sources = currentConfig.sources.copy(
                                    nguonc = currentConfig.sources.nguonc.copy(apiBase = clean)
                                )
                            )
                        }
                        lower.contains("cobe") -> {
                            currentConfig = currentConfig.copy(
                                sources = currentConfig.sources.copy(
                                    cobephim = currentConfig.sources.cobephim.copy(apiBase = clean)
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        currentConfig
    }

    fun getBaseUrl(): String {
        val kk = currentConfig.sources.kkphim.apiBase
        if (kk.isNotBlank() && !kk.contains("mephim") && !kk.contains("rapphim")) {
            return kk
        }
        val pri = currentConfig.primaryDomain
        if (pri.isNotBlank() && !pri.contains("mephim") && !pri.contains("rapphim")) {
            return pri
        }
        return "https://phimapi.com"
    }

    fun getOphimBaseUrl(): String {
        val op = currentConfig.sources.ophim.apiBase
        if (op.isNotBlank() && !op.contains("mephim") && !op.contains("rapphim")) {
            return op
        }
        return "https://ophim.cc"
    }

    fun getNguonCBaseUrl(): String = currentConfig.sources.nguonc.apiBase.ifBlank { "https://phim.nguonc.com/api/film" }
    fun getCoBePhimBaseUrl(): String = currentConfig.sources.cobephim.apiBase.ifBlank { "https://cobephim.mom" }

    fun getSavedCustomSources(context: android.content.Context): Map<String, String> {
        val prefs = context.getSharedPreferences("duytv_sources", android.content.Context.MODE_PRIVATE)
        return mapOf(
            "Nguồn Chính (KKPhim)" to (prefs.getString("kkphim_url", null) ?: currentConfig.sources.kkphim.apiBase.ifBlank { currentConfig.primaryDomain }),
            "Nguồn OPhim" to (prefs.getString("ophim_url", null) ?: currentConfig.sources.ophim.apiBase.ifBlank { "https://ophim.cc" }),
            "Nguồn NguonC" to (prefs.getString("nguonc_url", null) ?: currentConfig.sources.nguonc.apiBase.ifBlank { "https://phim.nguonc.com/api/film" }),
            "Nguồn CoBePhim" to (prefs.getString("cobe_url", null) ?: currentConfig.sources.cobephim.apiBase.ifBlank { "https://cobephim.mom" })
        )
    }

    fun loadLocalCustomSources(context: android.content.Context) {
        val prefs = context.getSharedPreferences("duytv_sources", android.content.Context.MODE_PRIVATE)
        val customOphim = prefs.getString("ophim_url", null)
        val customKkphim = prefs.getString("kkphim_url", null)
        val customNguonC = prefs.getString("nguonc_url", null)
        val customCoBe = prefs.getString("cobe_url", null)

        var updatedSources = currentConfig.sources
        if (!customOphim.isNullOrBlank() && !customOphim.contains("rapphim") && !customOphim.contains("mephim")) {
            updatedSources = updatedSources.copy(ophim = updatedSources.ophim.copy(apiBase = customOphim))
        }
        if (!customKkphim.isNullOrBlank() && !customKkphim.contains("rapphim") && !customKkphim.contains("mephim")) {
            updatedSources = updatedSources.copy(kkphim = updatedSources.kkphim.copy(apiBase = customKkphim))
        }
        if (!customNguonC.isNullOrBlank()) {
            updatedSources = updatedSources.copy(nguonc = updatedSources.nguonc.copy(apiBase = customNguonC))
        }
        if (!customCoBe.isNullOrBlank()) {
            updatedSources = updatedSources.copy(cobephim = updatedSources.cobephim.copy(apiBase = customCoBe))
        }
        currentConfig = currentConfig.copy(sources = updatedSources)
    }

    fun saveCustomSource(context: android.content.Context, inputUrl: String): String {
        var clean = inputUrl.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://$clean"
        }
        if (clean.endsWith("/")) clean = clean.dropLast(1)
        val lower = clean.lowercase()

        val prefs = context.getSharedPreferences("duytv_sources", android.content.Context.MODE_PRIVATE).edit()
        val detectedName: String

        when {
            lower.contains("ophim") -> {
                prefs.putString("ophim_url", clean).apply()
                currentConfig = currentConfig.copy(sources = currentConfig.sources.copy(ophim = currentConfig.sources.ophim.copy(apiBase = clean)))
                detectedName = "OPhim ($clean)"
            }
            lower.contains("phimapi") -> {
                prefs.putString("kkphim_url", clean).apply()
                currentConfig = currentConfig.copy(primaryDomain = clean, sources = currentConfig.sources.copy(kkphim = currentConfig.sources.kkphim.copy(apiBase = clean)))
                detectedName = "KKPhim ($clean)"
            }
            lower.contains("nguon") -> {
                prefs.putString("nguonc_url", clean).apply()
                currentConfig = currentConfig.copy(sources = currentConfig.sources.copy(nguonc = currentConfig.sources.nguonc.copy(apiBase = clean)))
                detectedName = "NguonC ($clean)"
            }
            lower.contains("cobe") -> {
                prefs.putString("cobe_url", clean).apply()
                currentConfig = currentConfig.copy(sources = currentConfig.sources.copy(cobephim = currentConfig.sources.cobephim.copy(apiBase = clean)))
                detectedName = "CoBePhim ($clean)"
            }
            else -> {
                if (!lower.contains("mephim") && !lower.contains("rapphim")) {
                    prefs.putString("kkphim_url", clean).apply()
                    currentConfig = currentConfig.copy(primaryDomain = clean, sources = currentConfig.sources.copy(kkphim = currentConfig.sources.kkphim.copy(apiBase = clean)))
                    detectedName = "Nguồn tùy chỉnh ($clean)"
                } else {
                    detectedName = "Link web xem phim (App giữ API chuẩn để không lỗi tải phim)"
                }
            }
        }
        return detectedName
    }

    fun resetToDefaults(context: android.content.Context) {
        context.getSharedPreferences("duytv_sources", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        currentConfig = RemoteConfig()
    }

    fun resolveImageUrl(path: String?): String {
        if (path.isNullOrBlank()) return ""
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val cdn = currentConfig.sources.kkphim.imageCdn ?: currentConfig.imageCdn
        return "$cdn$cleanPath"
    }
}
