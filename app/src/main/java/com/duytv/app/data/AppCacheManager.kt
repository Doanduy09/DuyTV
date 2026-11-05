package com.duytv.app.data

import android.content.Context
import com.google.gson.Gson
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
    private var appContext: Context? = null

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

    private const val HOME_CACHE_FILE = "duytv_home_cache.json"
    private const val TV_SPORTS_CACHE_FILE = "duytv_tvsports_cache.json"

    fun init(context: Context, defaultOfficialChannels: List<TvChannel> = emptyList()) {
        appContext = context.applicationContext
        if (activeChannels.isEmpty() && defaultOfficialChannels.isNotEmpty()) {
            activeChannels = defaultOfficialChannels
        }

        // 1. Nạp L2 Disk Cache vào L1 RAM nếu RAM chưa có (tốn < 15ms)
        if (cachedHomeData == null) {
            cachedHomeData = readDiskCache(appContext!!, HOME_CACHE_FILE, HomeCacheData::class.java)
        }
        if (cachedTvSports == null) {
            val diskTv = readDiskCache(appContext!!, TV_SPORTS_CACHE_FILE, TvSportsData::class.java)
            if (diskTv != null) {
                cachedTvSports = diskTv
                if (diskTv.channels.isNotEmpty()) {
                    activeChannels = diskTv.channels
                }
            } else if (defaultOfficialChannels.isNotEmpty()) {
                activeChannels = defaultOfficialChannels
            }
        }
    }

    fun saveHomeData(data: HomeCacheData, ctx: Context? = null) {
        cachedHomeData = data
        val targetContext = ctx?.applicationContext ?: appContext ?: return
        writeDiskCache(targetContext, HOME_CACHE_FILE, data)
    }

    fun saveTvSports(data: TvSportsData, ctx: Context? = null) {
        cachedTvSports = data
        if (data.channels.isNotEmpty()) {
            activeChannels = data.channels
        }
        val targetContext = ctx?.applicationContext ?: appContext ?: return
        writeDiskCache(targetContext, TV_SPORTS_CACHE_FILE, data)
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

    private fun writeDiskCache(context: Context, fileName: String, obj: Any) {
        try {
            val file = File(context.cacheDir, fileName)
            file.writer().use { gson.toJson(obj, it) }
        } catch (_: Exception) {}
    }
}
