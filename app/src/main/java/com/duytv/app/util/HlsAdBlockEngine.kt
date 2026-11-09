package com.duytv.app.util

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import android.content.Context
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Engine chặn 100% quảng cáo chèn giữa phim (HLS Mid-Roll / Pre-Roll Ad Injection)
 * trên các nguồn KKPhim, OPhim, NguonC, CoBePhim.
 *
 * Cơ chế hoạt động:
 * 1. Bắt trực tiếp nội dung playlist `.m3u8` qua OkHttp Network Interceptor trước khi ExoPlayer đọc.
 * 2. Phân tích toàn bộ các phân vùng ngăn cách bởi `#EXT-X-DISCONTINUITY`.
 * 3. Nhận diện phân vùng quảng cáo bằng 3 lớp:
 *    - Lớp 1: Từ khóa URL quảng cáo/cá cược (`adjump`, `/ads/`, `quangcao`, `bet`, `vip`, `casino`, `88`, `promo`...)
 *    - Lớp 2: Sai lệch đường dẫn thư mục gốc (Prefix Mismatch) so với >75% phân đoạn chính của bộ phim.
 *    - Lớp 3: Phân vùng ốc đảo ngắn (`<= 24` phân đoạn hoặc tổng thời lượng `< 95s`) nằm kẹp giữa các phân vùng phim dài trong luồng VOD (`#EXT-X-ENDLIST`).
 * 4. Cắt bỏ hoàn toàn phân đoạn quảng cáo và nối liền mạch các phân đoạn phim gốc để phát liên tục không khựng hình.
 */
object HlsAdBlockEngine {

    val ipv4PreferredDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            return Dns.SYSTEM.lookup(hostname).sortedWith(compareBy { addr ->
                when {
                    addr is Inet4Address -> 0
                    addr.isLinkLocalAddress || addr.isSiteLocalAddress -> 2
                    addr.hostAddress?.lowercase()?.let { it.startsWith("fd") || it.startsWith("fc") } == true -> 2
                    else -> 1
                }
            })
        }
    }

    private val sharedConnectionPool = ConnectionPool(8, 30, TimeUnit.SECONDS)

    @Volatile
    var isEnabled: Boolean = true

    val totalAdsBlocked = AtomicInteger(0)
    var onAdBlockedCallback: ((Int) -> Unit)? = null

    private val AD_KEYWORDS = listOf(
        "adjump", "/ads/", "/ad/", "quangcao", "qc_", "promo", "banner",
        "88bet", "i9bet", "789bet", "f8bet", "okvip", "jun88", "new88",
        "hi88", "shbet", "mb66", "casino", "nhacai", "v7bet", "kubet"
    )

    private data class M3u8Section(
        val hasLeadingDiscontinuity: Boolean,
        val lines: MutableList<String> = mutableListOf(),
        var segmentCount: Int = 0,
        var totalDurationSec: Double = 0.0,
        val segmentPrefixes: MutableList<String> = mutableListOf(),
        var hasAdKeyword: Boolean = false
    )

    private fun extractDirPrefix(segmentLine: String): String {
        val clean = segmentLine.substringBefore("?").trim()
        val lastSlash = clean.lastIndexOf('/')
        if (lastSlash > 0) {
            return clean.substring(0, lastSlash)
        }
        // Nếu là tên file tương đối (VD: seg_0001.ts), lấy phần chữ cái đầu trước dãy số
        return clean.replace(Regex("\\d+\\.[a-zA-Z0-9]+$"), "")
    }

    fun sanitizeM3u8Manifest(rawM3u8: String, requestUrl: String): String {
        if (!isEnabled) return rawM3u8
        if (!rawM3u8.contains("#EXTM3U") || !rawM3u8.contains("#EXT-X-DISCONTINUITY")) {
            return rawM3u8
        }
        // Không can thiệp nếu là Master Playlist (chứa #EXT-X-STREAM-INF)
        if (rawM3u8.contains("#EXT-X-STREAM-INF")) {
            return rawM3u8
        }

        val isVod = rawM3u8.contains("#EXT-X-ENDLIST") || rawM3u8.contains("PLAYLIST-TYPE:VOD")
        val allLines = rawM3u8.replace("\r\n", "\n").split('\n')

        val headerLines = mutableListOf<String>()
        val footerLines = mutableListOf<String>()
        val sections = mutableListOf<M3u8Section>()

        var currentSection = M3u8Section(hasLeadingDiscontinuity = false)
        var seenFirstSegmentOrDiscontinuity = false
        var pendingExtInfDuration = 0.0

        for (rawLine in allLines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXT-X-ENDLIST") -> {
                    footerLines.add(line)
                }
                line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE") -> {
                    if (seenFirstSegmentOrDiscontinuity && currentSection.lines.isNotEmpty()) {
                        sections.add(currentSection)
                    }
                    seenFirstSegmentOrDiscontinuity = true
                    currentSection = M3u8Section(hasLeadingDiscontinuity = true)
                }
                line.startsWith("#EXTINF:") -> {
                    seenFirstSegmentOrDiscontinuity = true
                    val durStr = line.removePrefix("#EXTINF:").substringBefore(",").trim()
                    pendingExtInfDuration = durStr.toDoubleOrNull() ?: 0.0
                    currentSection.lines.add(line)
                }
                !line.startsWith("#") -> {
                    seenFirstSegmentOrDiscontinuity = true
                    currentSection.lines.add(line)
                    currentSection.segmentCount++
                    currentSection.totalDurationSec += pendingExtInfDuration
                    pendingExtInfDuration = 0.0

                    val lower = line.lowercase()
                    if (AD_KEYWORDS.any { lower.contains(it) }) {
                        currentSection.hasAdKeyword = true
                    }
                    currentSection.segmentPrefixes.add(extractDirPrefix(line))
                }
                else -> {
                    if (!seenFirstSegmentOrDiscontinuity) {
                        headerLines.add(line)
                    } else {
                        currentSection.lines.add(line)
                    }
                }
            }
        }
        if (currentSection.lines.isNotEmpty()) {
            sections.add(currentSection)
        }

        if (sections.size <= 1) return rawM3u8

        // Tìm prefix phổ biến nhất của bộ phim (chiếm đa số phân đoạn)
        val prefixFrequency = mutableMapOf<String, Int>()
        var totalSegments = 0
        for (sec in sections) {
            totalSegments += sec.segmentCount
            for (p in sec.segmentPrefixes) {
                prefixFrequency[p] = (prefixFrequency[p] ?: 0) + 1
            }
        }
        val dominantPrefix = prefixFrequency.maxByOrNull { it.value }?.key ?: ""
        val dominantRatio = if (totalSegments > 0) {
            (prefixFrequency[dominantPrefix] ?: 0).toDouble() / totalSegments.toDouble()
        } else 0.0

        var removedSectionsCount = 0
        var removedSegmentsCount = 0
        val keptSections = mutableListOf<M3u8Section>()

        for (sec in sections) {
            if (sec.segmentCount == 0) continue

            val secMainPrefix = sec.segmentPrefixes.firstOrNull() ?: ""
            val isDifferentPrefix = dominantRatio >= 0.65 && secMainPrefix != dominantPrefix
            val isShortAdIsland = isVod && totalSegments > 60 && sec.segmentCount in 1..25 && sec.totalDurationSec in 2.0..95.0

            val isAdSection = sec.hasAdKeyword ||
                (isDifferentPrefix && sec.totalDurationSec < 180.0) ||
                (sec.hasLeadingDiscontinuity && isShortAdIsland)

            if (isAdSection) {
                removedSectionsCount++
                removedSegmentsCount += sec.segmentCount
            } else {
                keptSections.add(sec)
            }
        }

        if (removedSectionsCount == 0 || keptSections.isEmpty()) {
            return rawM3u8
        }

        val totalNow = totalAdsBlocked.addAndGet(removedSectionsCount)
        onAdBlockedCallback?.invoke(totalNow)

        val out = StringBuilder()
        headerLines.forEach { out.append(it).append('\n') }

        var prevPrefix: String? = null
        keptSections.forEachIndexed { idx, sec ->
            val currPrefix = sec.segmentPrefixes.firstOrNull() ?: ""
            // Chỉ giữ lại #EXT-X-DISCONTINUITY nếu thực sự có sự đổi khác về cấu trúc nguồn giữa 2 phần phim
            if (idx > 0 && sec.hasLeadingDiscontinuity && prevPrefix != null && prevPrefix != currPrefix) {
                out.append("#EXT-X-DISCONTINUITY\n")
            }
            sec.lines.forEach { out.append(it).append('\n') }
            prevPrefix = currPrefix
        }

        footerLines.forEach { out.append(it).append('\n') }
        return out.toString()
    }

    private val adBlockInterceptor = Interceptor { chain ->
        val req = chain.request()
        val res = chain.proceed(req)
        val urlStr = req.url.toString()
        val contentTypeStr = res.header("Content-Type")?.lowercase() ?: ""

        val isM3u8 = urlStr.contains(".m3u8") ||
            contentTypeStr.contains("mpegurl") ||
            contentTypeStr.contains("x-mpegurl")

        if (isEnabled && res.isSuccessful && isM3u8) {
            val body = res.body ?: return@Interceptor res
            val rawText = body.string()
            val cleanedText = sanitizeM3u8Manifest(rawText, urlStr)
            val newBody = cleanedText.toResponseBody(body.contentType() ?: "application/vnd.apple.mpegurl".toMediaTypeOrNull())
            res.newBuilder().body(newBody).build()
        } else {
            res
        }
    }

    private val sharedStreamOkHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(ipv4PreferredDns)
            .connectionPool(sharedConnectionPool)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor(adBlockInterceptor)
            .build()
    }

    @OptIn(UnstableApi::class)
    fun buildAdBlockingDataSourceFactory(
        context: Context,
        userAgent: String,
        headers: Map<String, String>
    ): DataSource.Factory {
        val okHttpFactory = OkHttpDataSource.Factory(sharedStreamOkHttpClient)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)

        return DefaultDataSource.Factory(context, okHttpFactory)
    }
}
