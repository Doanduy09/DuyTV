package com.duytv.app.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class WatchHistoryItem(
    val slug: String,
    val movieTitle: String,
    val episodeName: String,
    val streamUrl: String,
    val posterUrl: String?,
    val positionMs: Long,
    val durationMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

object WatchHistoryManager {
    private const val PREFS_NAME = "duytv_watch_history"
    private const val KEY_HISTORY = "history_items"
    private val gson = Gson()
    @Volatile
    private var memoryCache: List<WatchHistoryItem>? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveProgress(
        context: Context,
        slug: String,
        movieTitle: String,
        episodeName: String,
        streamUrl: String,
        posterUrl: String?,
        positionMs: Long,
        durationMs: Long,
        forceSave: Boolean = false
    ) {
        if (!forceSave && positionMs < 3000) return // Don't save if watched less than 3s unless forced

        val safePos = maxOf(1000L, positionMs)
        val list = getHistory(context).toMutableList()
        list.removeAll { it.slug == slug }
        list.add(
            0,
            WatchHistoryItem(
                slug = slug,
                movieTitle = movieTitle,
                episodeName = episodeName,
                streamUrl = streamUrl,
                posterUrl = posterUrl,
                positionMs = safePos,
                durationMs = maxOf(safePos, durationMs)
            )
        )
        // Keep max 20 recent items
        val trimmed = if (list.size > 20) list.subList(0, 20).toList() else list.toList()
        memoryCache = trimmed
        val json = gson.toJson(trimmed)
        getPrefs(context).edit().putString(KEY_HISTORY, json).apply()
    }

    fun getHistoryForMovie(context: Context, slug: String): WatchHistoryItem? {
        return getHistory(context).firstOrNull { it.slug == slug }
    }

    fun getHistory(context: Context): List<WatchHistoryItem> {
        memoryCache?.let { return it }
        val json = getPrefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<WatchHistoryItem>>() {}.type
            val parsed: List<WatchHistoryItem> = gson.fromJson(json, type) ?: emptyList()
            memoryCache = parsed
            parsed
        } catch (_: Exception) {
            emptyList()
        }
    }
}
