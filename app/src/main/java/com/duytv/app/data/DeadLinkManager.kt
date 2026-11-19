package com.duytv.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Quản lý Link Chết Thông Minh Bền Vững (Persistent Dead Link & Circuit Breaker Manager)
 * - Tự động ghi nhớ link chết vĩnh viễn hoặc cách ly dài hạn qua SharedPreferences.
 * - Khi link gặp lỗi Fatal (403, 404, 410, Malformed) hoặc lỗi liên tiếp 2 lần:
 *   Đánh dấu CHẾT VĨNH VIỄN để các lần sau KHÔNG BAO GIỜ mở lại link đó nữa.
 * - Ghi nhớ đường link phát thành công (Working Stream Memory) để lần sau mở tức thì 0ms.
 */
object DeadLinkManager {
    private const val TAG = "DeadLinkManager"
    private const val PREFS_DEAD_LINKS = "duytv_dead_links_prefs"
    private const val PREFS_WORKING_STREAMS = "duytv_working_streams_prefs"
    private const val KEY_DEAD_SET = "dead_url_hashes_v1"
    private const val KEY_DEAD_UNTIL_PREFIX = "dead_until_"
    private const val KEY_FAIL_COUNT_PREFIX = "fail_count_"

    // RAM Caches (Zero-Latency Lookup)
    private val deadUntilTimestamp = ConcurrentHashMap<String, Long>()
    private val failureCounts = ConcurrentHashMap<String, Int>()
    private val workingStreamByChannel = ConcurrentHashMap<String, String>()

    private var deadPrefs: SharedPreferences? = null
    private var workingPrefs: SharedPreferences? = null
    private val ioScope = CoroutineScope(Dispatchers.IO)
    @Volatile
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true
        val appContext = context.applicationContext
        deadPrefs = appContext.getSharedPreferences(PREFS_DEAD_LINKS, Context.MODE_PRIVATE)
        workingPrefs = appContext.getSharedPreferences(PREFS_WORKING_STREAMS, Context.MODE_PRIVATE)

        ioScope.launch {
            try {
                // 1. Nạp danh sách link chết bền vững vào RAM
                deadPrefs?.all?.forEach { (key, value) ->
                    if (key.startsWith(KEY_DEAD_UNTIL_PREFIX) && value is Long) {
                        val urlHash = key.removePrefix(KEY_DEAD_UNTIL_PREFIX)
                        deadUntilTimestamp[urlHash] = value
                    } else if (key.startsWith(KEY_FAIL_COUNT_PREFIX) && value is Int) {
                        val urlHash = key.removePrefix(KEY_FAIL_COUNT_PREFIX)
                        failureCounts[urlHash] = value
                    }
                }

                // 2. Nạp danh sách working streams đã ghi nhớ vào RAM
                workingPrefs?.all?.forEach { (channelKey, url) ->
                    if (url is String && url.isNotBlank()) {
                        workingStreamByChannel[channelKey] = url
                    }
                }
                Log.i(TAG, "Đã khởi tạo: ${deadUntilTimestamp.size} link chết được cách ly, ${workingStreamByChannel.size} link ưa thích.")
            } catch (e: Exception) {
                Log.w(TAG, "Khởi tạo DeadLinkManager lỗi nhẹ: ${e.message}")
            }
        }
    }

    private fun normalizeKey(rawUrl: String): String {
        return rawUrl.trim().lowercase()
    }

    /**
     * Kiểm tra xem đường link có phải link chết không
     * Trả về TRUE nếu link bị đánh dấu chết vĩnh viễn hoặc còn trong thời gian cách ly.
     */
    fun isDead(url: String): Boolean {
        if (url.isBlank()) return true
        val key = normalizeKey(url)
        val until = deadUntilTimestamp[key] ?: return false
        val now = System.currentTimeMillis()
        return until > now
    }

    /**
     * Đánh dấu link bị lỗi:
     * - isFatal = true (403, 404, 410, HLS Malformed) -> Chết vĩnh viễn (Long.MAX_VALUE)
     * - Thử lần 1 -> Cách ly 24 giờ
     * - Thử lần 2 trở lên -> Chết vĩnh viễn (Long.MAX_VALUE)
     */
    fun markStreamFailed(url: String, isFatal: Boolean = false) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return
        val key = normalizeKey(cleanUrl)
        val currentCount = (failureCounts[key] ?: 0) + 1
        failureCounts[key] = currentCount

        val isPermanent = isFatal || currentCount >= 2
        val untilTimestamp = if (isPermanent) {
            Long.MAX_VALUE // Đánh dấu vĩnh viễn không bao giờ mở lại
        } else {
            System.currentTimeMillis() + 24 * 3600 * 1000L // Cách ly 24 tiếng
        }

        deadUntilTimestamp[key] = untilTimestamp
        Log.w(TAG, "🚫 ĐÁNH DẤU LINK CHẾT [Permanent=$isPermanent, count=$currentCount]: $cleanUrl")

        // Lưu bền vững vào Disk SharedPreferences chạy ngầm (Non-blocking IO)
        ioScope.launch {
            deadPrefs?.edit()?.apply {
                putLong(KEY_DEAD_UNTIL_PREFIX + key, untilTimestamp)
                putInt(KEY_FAIL_COUNT_PREFIX + key, currentCount)
                apply()
            }
        }
    }

    /**
     * Ghi nhận link đã phát mượt thành công:
     * Xóa khỏi danh sách lỗi, đồng thời đặt làm link ưu tiên số 1 cho kênh/phim này.
     */
    fun markStreamSuccess(channelOrTitleKey: String, url: String) {
        val cleanUrl = url.trim()
        val cleanChannel = channelOrTitleKey.trim().uppercase()
        if (cleanUrl.isBlank() || cleanChannel.isBlank()) return
        val key = normalizeKey(cleanUrl)

        failureCounts.remove(key)
        deadUntilTimestamp.remove(key)
        workingStreamByChannel[cleanChannel] = cleanUrl

        ioScope.launch {
            deadPrefs?.edit()?.apply {
                remove(KEY_DEAD_UNTIL_PREFIX + key)
                remove(KEY_FAIL_COUNT_PREFIX + key)
                apply()
            }
            workingPrefs?.edit()?.putString(cleanChannel, cleanUrl)?.apply()
        }
    }

    /**
     * Lọc bỏ link chết và sắp xếp luồng mượt nhất lên đầu:
     * 1. Link đã lưu phát thành công (Working Stream) -> Ưu tiên số 1
     * 2. Các link lành mạnh còn lại (loại trừ 100% link chết đã đánh dấu)
     * 3. Fallback: Nếu tất cả link đều bị đánh dấu chết (trường hợp hiếm), trả về link ít lỗi nhất.
     */
    fun rankHealthyStreams(channelOrTitleKey: String, urls: List<String>): List<String> {
        val distinctUrls = urls.filter { it.isNotBlank() }.distinct()
        if (distinctUrls.size <= 1) return distinctUrls

        val cleanChannel = channelOrTitleKey.trim().uppercase()
        val preferredUrl = workingStreamByChannel[cleanChannel]

        val healthy = distinctUrls.filterNot { isDead(it) }
        val baseList = if (healthy.isNotEmpty()) {
            healthy
        } else {
            // Nếu tất cả link trong playlist đều bị đánh dấu lỗi, lấy link có fail count thấp nhất
            Log.w(TAG, "Tất cả link của $cleanChannel đều bị đánh dấu lỗi! Kích hoạt chế độ fallback thử lại.")
            distinctUrls.sortedBy { failureCounts[normalizeKey(it)] ?: 0 }
        }

        return if (preferredUrl != null && baseList.contains(preferredUrl)) {
            listOf(preferredUrl) + baseList.filterNot { it == preferredUrl }
        } else {
            baseList
        }
    }

    fun getDeadLinksCount(): Int = deadUntilTimestamp.size

    fun clearAllDeadLinks() {
        deadUntilTimestamp.clear()
        failureCounts.clear()
        ioScope.launch {
            deadPrefs?.edit()?.clear()?.apply()
        }
    }
}
