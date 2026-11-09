package com.duytv.app.data

import android.content.Context
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

data class HomeCacheData(
    val latestMovies: List<MovieItemDto> = emptyList(),
    val cinemaMovies: List<MovieItemDto> = emptyList(),
    val torrentMovies: List<MovieItemDto> = emptyList(),
    val seriesMovies: List<MovieItemDto> = emptyList(),
    val singleMovies: List<MovieItemDto> = emptyList(),
    val animeMovies: List<MovieItemDto> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

object AppCacheManager {
    private val gson = Gson()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null
    @Volatile
    private var isInitialized = false

    // L1 RAM Cache (Singleton in-memory)
    @Volatile
    var cachedHomeData: HomeCacheData? = null
        private set

    @Volatile
    var cachedTvSports: TvSportsData? = null
        private set

    // Danh sách kênh truyền hình trực tuyến dùng để nhảy kênh siêu tốc bằng phím lên/xuống
    @Volatile
    var activeChannels: List<TvChannel> = emptyList()

    @Volatile
    var favoriteChannelNames: Set<String> = emptySet()
        private set

    @Volatile
    var lastWatchedChannelName: String = ""
        private set

    @Volatile
    var favoriteMovies: List<MovieItemDto> = emptyList()
        private set

    private const val HOME_CACHE_FILE = "duytv_home_cache.json"
    private const val TV_SPORTS_CACHE_FILE = "duytv_tvsports_cache.json"
    private const val FAV_PREFS = "duytv_fav_channels"
    private const val KEY_FAV_NAMES = "fav_names"
    private const val KEY_LAST_CHANNEL = "last_watched_channel"
    private const val KEY_FAV_MOVIES = "fav_movies_json"

    fun saveLastWatchedChannel(context: Context, channelName: String) {
        val clean = channelName.trim()
        if (clean.isBlank()) return
        lastWatchedChannelName = clean
        context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_CHANNEL, clean)
            .apply()
    }

    fun isFavoriteMovie(slug: String): Boolean {
        return favoriteMovies.any { it.slug == slug }
    }

    fun toggleFavoriteMovie(context: Context, movie: MovieItemDto): Boolean {
        val current = favoriteMovies.toMutableList()
        val exists = current.any { it.slug == movie.slug }
        val isNowFav = if (exists) {
            current.removeAll { it.slug == movie.slug }
            false
        } else {
            current.add(0, movie)
            true
        }
        favoriteMovies = current.take(40)
        try {
            val json = gson.toJson(favoriteMovies)
            context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_FAV_MOVIES, json)
                .apply()
        } catch (_: Exception) {}
        return isNowFav
    }

    fun isFavoriteChannel(channelName: String): Boolean {
        return favoriteChannelNames.contains(channelName.trim().uppercase())
    }

    fun toggleFavoriteChannel(context: Context, channelName: String): Boolean {
        val key = channelName.trim().uppercase()
        val updated = favoriteChannelNames.toMutableSet()
        val isNowFav = if (updated.contains(key)) {
            updated.remove(key)
            false
        } else {
            updated.add(key)
            true
        }
        favoriteChannelNames = updated
        context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_FAV_NAMES, updated)
            .apply()
        if (activeChannels.isNotEmpty()) {
            activeChannels = sortChannelsWithFavoritesFirst(activeChannels)
        }
        return isNowFav
    }

    fun sortChannelsWithFavoritesFirst(channels: List<TvChannel>): List<TvChannel> {
        if (favoriteChannelNames.isEmpty()) return channels
        val (favs, others) = channels.partition { isFavoriteChannel(it.name) }
        return favs + others
    }

    fun init(context: Context, defaultOfficialChannels: List<TvChannel> = emptyList()) {
        appContext = context.applicationContext
        val prefs = appContext!!.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
        val savedFavs = prefs.getStringSet(KEY_FAV_NAMES, emptySet()) ?: emptySet()
        favoriteChannelNames = savedFavs
        lastWatchedChannelName = prefs.getString(KEY_LAST_CHANNEL, "") ?: ""
        try {
            val favMoviesJson = prefs.getString(KEY_FAV_MOVIES, null)
            if (!favMoviesJson.isNullOrBlank()) {
                val type = object : com.google.gson.reflect.TypeToken<List<MovieItemDto>>() {}.type
                favoriteMovies = gson.fromJson(favMoviesJson, type) ?: emptyList()
            }
        } catch (_: Exception) {}
        if (isInitialized && activeChannels.isNotEmpty()) return
        isInitialized = true
        if (activeChannels.isEmpty() && defaultOfficialChannels.isNotEmpty()) {
            activeChannels = sortChannelsWithFavoritesFirst(defaultOfficialChannels)
        }

        // 1. Nạp L2 Disk Cache vào L1 RAM nếu RAM chưa có (tốn < 15ms)
        if (cachedHomeData == null) {
            cachedHomeData = readDiskCache(appContext!!, HOME_CACHE_FILE, HomeCacheData::class.java)
        }
        if (cachedTvSports == null) {
            val diskTv = readDiskCache(appContext!!, TV_SPORTS_CACHE_FILE, TvSportsData::class.java)
            if (diskTv != null) {
                val mergedCh = MovieRepository.mergeAndDeduplicateChannels(defaultOfficialChannels + diskTv.channels)
                val activeMatches = diskTv.matches.filter {
                    MatchStateHelper.calculateMatchState(it.kickoffTime).status != MatchStatus.FINISHED
                }
                val cleaned = diskTv.copy(matches = activeMatches, channels = mergedCh)
                cachedTvSports = cleaned
                if (mergedCh.isNotEmpty()) {
                    activeChannels = mergedCh
                }
            } else if (defaultOfficialChannels.isNotEmpty()) {
                val dedupOfficial = MovieRepository.mergeAndDeduplicateChannels(defaultOfficialChannels)
                activeChannels = dedupOfficial
                cachedTvSports = TvSportsData(channels = dedupOfficial)
            }
        }
    }

    fun saveHomeData(data: HomeCacheData, ctx: Context? = null) {
        cachedHomeData = data
        val targetContext = ctx?.applicationContext ?: appContext ?: return
        ioScope.launch {
            writeDiskCache(targetContext, HOME_CACHE_FILE, data)
        }
    }

    fun saveTvSports(data: TvSportsData, ctx: Context? = null) {
        val sortedChannels = sortChannelsWithFavoritesFirst(data.channels)
        val sortedData = data.copy(channels = sortedChannels)
        cachedTvSports = sortedData
        if (sortedChannels.isNotEmpty()) {
            activeChannels = sortedChannels
        }
        val targetContext = ctx?.applicationContext ?: appContext ?: return
        ioScope.launch {
            writeDiskCache(targetContext, TV_SPORTS_CACHE_FILE, sortedData)
        }
    }

    private fun <T> readDiskCache(context: Context, fileName: String, clazz: Class<T>): T? {
        return try {
            val file = File(context.cacheDir, fileName)
            if (file.exists() && file.length() > 0) {
                file.reader().use { gson.fromJson(it, clazz) }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    private fun writeDiskCache(context: Context, fileName: String, obj: Any) {
        val tmpFile = File(context.cacheDir, "$fileName.tmp")
        val targetFile = File(context.cacheDir, fileName)
        try {
            java.io.FileOutputStream(tmpFile).use { fos ->
                fos.writer(Charsets.UTF_8).use { writer ->
                    gson.toJson(obj, writer)
                    writer.flush()
                }
                try { fos.fd.sync() } catch (_: Exception) {}
            }
            if (tmpFile.exists() && tmpFile.length() > 0) {
                if (targetFile.exists()) targetFile.delete()
                tmpFile.renameTo(targetFile)
            }
        } catch (_: Exception) {
            try { if (tmpFile.exists()) tmpFile.delete() } catch (_: Exception) {}
        }
    }

    // =========================================================================
    // 20 SMART UPGRADES (v1.9.8): Stream Health Memory, Player Prefs, L1 Detail Cache, Recent Searches
    // =========================================================================
    private val workingStreamByChannel = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val failedStreamUntilMillis = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val movieDetailL1Cache = java.util.concurrent.ConcurrentHashMap<String, MovieDetailResponse>()

    fun markStreamSuccess(channelName: String, url: String) {
        if (channelName.isNotBlank() && url.isNotBlank()) {
            workingStreamByChannel[channelName.trim().uppercase()] = url
            failedStreamUntilMillis.remove(url)
        }
    }

    fun markStreamFailed(url: String) {
        if (url.isNotBlank()) {
            failedStreamUntilMillis[url] = System.currentTimeMillis() + 15 * 60_000L // Cách ly 15 phút
        }
    }

    fun rankHealthyStreams(channelName: String, urls: List<String>): List<String> {
        val distinctUrls = urls.filter { it.isNotBlank() }.distinct()
        if (distinctUrls.size <= 1) return distinctUrls
        val now = System.currentTimeMillis()
        val bestUrl = workingStreamByChannel[channelName.trim().uppercase()]
        val healthy = distinctUrls.filter { url ->
            val until = failedStreamUntilMillis[url] ?: 0L
            until <= now
        }
        val baseList = healthy.ifEmpty { distinctUrls }
        return if (bestUrl != null && baseList.contains(bestUrl)) {
            listOf(bestUrl) + baseList.filterNot { it == bestUrl }
        } else {
            baseList
        }
    }

    fun getCachedMovieDetail(slug: String): MovieDetailResponse? = movieDetailL1Cache[slug]

    fun putCachedMovieDetail(slug: String, detail: MovieDetailResponse) {
        if (movieDetailL1Cache.size > 40) movieDetailL1Cache.clear()
        movieDetailL1Cache[slug] = detail
    }

    fun getPreferredAspectModeName(context: Context): String {
        return context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
            .getString("pref_aspect_mode_v2", "FILL") ?: "FILL"
    }

    fun savePreferredAspectModeName(context: Context, modeName: String) {
        context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
            .edit().putString("pref_aspect_mode_v2", modeName).apply()
    }

    fun getPreferredAudioBoost(context: Context): Int {
        return context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
            .getInt("pref_audio_boost", 100)
    }

    fun savePreferredAudioBoost(context: Context, boost: Int) {
        context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
            .edit().putInt("pref_audio_boost", boost).apply()
    }

    fun getPreferredNightMode(context: Context): Boolean {
        return context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
            .getBoolean("pref_night_warm", false)
    }

    fun savePreferredNightMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("pref_night_warm", enabled).apply()
    }

    fun getRecentSearches(context: Context): List<String> {
        val raw = context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
            .getString("recent_search_keywords", "") ?: ""
        return raw.split("|||").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun saveRecentSearch(context: Context, keyword: String) {
        val clean = keyword.trim()
        if (clean.length < 2) return
        val updated = (listOf(clean) + getRecentSearches(context).filterNot { it.equals(clean, ignoreCase = true) }).take(8)
        context.getSharedPreferences(FAV_PREFS, Context.MODE_PRIVATE)
            .edit().putString("recent_search_keywords", updated.joinToString("|||")).apply()
    }
}
