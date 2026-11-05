package com.duytv.app.data

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object RemoteConfigManager {
    // URL to remote JSON configuration (e.g., Anh Duy's GitHub Gist or Raw repo)
    // If unreachable or null, automatically uses fallback defaults.
    private const val REMOTE_CONFIG_URL = "https://raw.githubusercontent.com/Doanduy09/DuyTV/main/config/sources.json"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    @Volatile
    private var currentConfig = RemoteConfig()

    suspend fun fetchRemoteConfig(): RemoteConfig = withContext(Dispatchers.IO) {
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
        } catch (_: Exception) {
            // Keep safe fallback default
        }
        currentConfig
    }

    fun getBaseUrl(): String = currentConfig.sources.kkphim.apiBase.ifBlank { currentConfig.primaryDomain }
    fun getOphimBaseUrl(): String = currentConfig.sources.ophim.apiBase.ifBlank { "https://ophim.cc" }
    fun getNguonCBaseUrl(): String = currentConfig.sources.nguonc.apiBase.ifBlank { "https://phim.nguonc.com/api/film" }
    fun getCoBePhimBaseUrl(): String = currentConfig.sources.cobephim.apiBase.ifBlank { "https://cobephim.mom" }

    fun resolveImageUrl(path: String?): String {
        if (path.isNullOrBlank()) return ""
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val cdn = currentConfig.sources.kkphim.imageCdn ?: currentConfig.imageCdn
        return "$cdn$cleanPath"
    }
}
