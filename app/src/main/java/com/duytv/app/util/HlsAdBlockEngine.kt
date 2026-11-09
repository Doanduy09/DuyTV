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
import kotlinx.coroutines.async

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
        "intro_ad", "outro_ad", "preroll", "midroll", "scte35", "doubleclick", "popads", "adservice",
        "8xbet", "88bet", "i9bet", "789bet", "f8bet", "okvip", "jun88", "new88",
        "hi88", "shbet", "mb66", "casino", "nhacai", "v7bet", "kubet", "ku777",
        "bong88", "saba", "cmd368", "sv388", "rikvip", "sunwin", "go88", "hitclub",
        "b52", "fb88", "w88", "m88", "dafabet", "vn88", "fun88", "bk8", "188bet",
        "v9bet", "ok9", "gk88", "qq88", "8kbet", "bet88", "f168", "tx88", "ta88",
        "uk88", "debet", "zbet", "five88", "red88", "sin88", "sky88", "mibet", "lucky88", "one88"
    )

    private val BOOKMAKER_TEXT_REGEX = Regex(
        """(?i)\b(?:8xbet|88bet|i9bet|789bet|f8bet|okvip|jun88|new88|hi88|shbet|mb66|kubet|ku777|bong88|rikvip|sunwin|go88|hitclub|fb88|w88|m88|dafabet|vn88|fun88|bk8|188bet|v9bet|ok9|gk88|qq88|8kbet|bet88|f168|tx88|ta88|uk88|debet|zbet|five88|red88|sin88|sky88|mibet|lucky88|one88|nhà\s*cái|cá\s*cược|tài\s*trợ)\b[^\n|•]*"""
    )

    fun isAdOrBookmakerUrl(url: String): Boolean {
        val lower = url.lowercase()
        return AD_KEYWORDS.any { lower.contains(it) }
    }

    fun cleanBookmakerText(raw: String): String {
        val cleaned = raw.replace(BOOKMAKER_TEXT_REGEX, "")
            .replace(Regex("""\s*[-|•]\s*$"""), "")
            .replace(Regex("""^\s*[-|•]\s*"""), "")
            .trim()
        return cleaned.ifBlank { raw.trim() }
    }

    private fun prioritizeHighestQualityMasterPlaylist(rawM3u8: String): String {
        val lines = rawM3u8.replace("\r\n", "\n").split('\n')
        data class VariantBlock(val infLine: String, val uriLine: String, val score: Long)
        val headerAndMediaLines = mutableListOf<String>()
        val variants = mutableListOf<VariantBlock>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                i++
                continue
            }
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(line)
                val width = resMatch?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                val height = resMatch?.groupValues?.getOrNull(2)?.toLongOrNull() ?: 0L
                val pixels = width * height
                var nextIdx = i + 1
                while (nextIdx < lines.size && lines[nextIdx].trim().isEmpty()) {
                    nextIdx++
                }
                if (nextIdx < lines.size && !lines[nextIdx].trim().startsWith("#")) {
                    val uriLine = lines[nextIdx].trim()
                    if (!isAdOrBookmakerUrl(uriLine)) {
                        val score = pixels * 1_000_000L + bw
                        variants.add(VariantBlock(line, uriLine, score))
                    }
                    i = nextIdx + 1
                    continue
                }
            }
            headerAndMediaLines.add(line)
            i++
        }

        if (variants.size <= 1) return rawM3u8
        val sortedVariants = variants.sortedByDescending { it.score }
        val out = StringBuilder()
        headerAndMediaLines.forEach { out.append(it).append('\n') }
        sortedVariants.forEach { v ->
            out.append(v.infLine).append('\n')
            out.append(v.uriLine).append('\n')
        }
        return out.toString()
    }

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

    private fun stripInlineAdSegments(rawM3u8: String): Pair<String, Int> {
        val lines = rawM3u8.replace("\r\n", "\n").split('\n')
        val out = mutableListOf<String>()
        var removedCount = 0
        var pendingExtInf: String? = null
        var inCueOutBlock = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("#EXT-X-CUE-OUT")) {
                inCueOutBlock = true
                removedCount++
                continue
            }
            if (line.startsWith("#EXT-X-CUE-IN")) {
                inCueOutBlock = false
                continue
            }
            if (inCueOutBlock) {
                continue
            }
            if (line.startsWith("#EXT-X-DATERANGE") && isAdOrBookmakerUrl(line)) {
                removedCount++
                continue
            }

            if (line.startsWith("#EXTINF:")) {
                pendingExtInf = line
            } else if (!line.startsWith("#")) {
                val extInf = pendingExtInf
                pendingExtInf = null
                if (isAdOrBookmakerUrl(line) || (extInf != null && isAdOrBookmakerUrl(extInf))) {
                    removedCount++
                } else {
                    if (extInf != null) out.add(extInf)
                    out.add(line)
                }
            } else {
                if (pendingExtInf != null) {
                    out.add(pendingExtInf!!)
                    pendingExtInf = null
                }
                out.add(line)
            }
        }
        return if (removedCount > 0) Pair(out.joinToString("\n"), removedCount) else Pair(rawM3u8, 0)
    }

    fun sanitizeM3u8Manifest(rawM3u8: String, requestUrl: String): String {
        if (!rawM3u8.contains("#EXTM3U")) {
            return rawM3u8
        }
        // Nếu là Master Playlist (chứa #EXT-X-STREAM-INF): Sắp xếp ưu tiên luồng chất lượng cao nhất (4K / 1080p FHD) lên đầu
        if (rawM3u8.contains("#EXT-X-STREAM-INF")) {
            return prioritizeHighestQualityMasterPlaylist(rawM3u8)
        }
        if (!isEnabled) return rawM3u8

        val (inlineCleanedM3u8, inlineRemoved) = stripInlineAdSegments(rawM3u8)
        if (inlineRemoved > 0) {
            val totalNow = totalAdsBlocked.addAndGet(inlineRemoved)
            onAdBlockedCallback?.invoke(totalNow)
        }

        if (!inlineCleanedM3u8.contains("#EXT-X-DISCONTINUITY")) {
            return inlineCleanedM3u8
        }

        val isVod = inlineCleanedM3u8.contains("#EXT-X-ENDLIST") || inlineCleanedM3u8.contains("PLAYLIST-TYPE:VOD")
        val allLines = inlineCleanedM3u8.replace("\r\n", "\n").split('\n')

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

        if (sections.size <= 1) return inlineCleanedM3u8

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
            val isDifferentPrefix = dominantRatio >= 0.60 && secMainPrefix != dominantPrefix
            val isShortAdIsland = isVod && totalSegments > 60 && sec.segmentCount in 1..25 && sec.totalDurationSec in 2.0..95.0
            val isLiveShortForeignAd = !isVod && isDifferentPrefix && sec.segmentCount in 1..15 && sec.totalDurationSec <= 60.0

            val isAdSection = sec.hasAdKeyword ||
                (isDifferentPrefix && sec.totalDurationSec < 180.0) ||
                (sec.hasLeadingDiscontinuity && isShortAdIsland) ||
                isLiveShortForeignAd

            if (isAdSection) {
                removedSectionsCount++
                removedSegmentsCount += sec.segmentCount
            } else {
                keptSections.add(sec)
            }
        }

        if (removedSectionsCount == 0 || keptSections.isEmpty()) {
            return inlineCleanedM3u8
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

    fun preWarmStreamManifest(url: String) {
        if (url.isBlank() || !url.startsWith("http")) return
        try {
            val reqB = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 DuyTV/1.0")
            when {
                url.contains("cdnfastter") || url.contains("khoga") -> {
                    reqB.header("Referer", "https://khoga1.live/")
                    reqB.header("Origin", "https://khoga1.live")
                }
                url.contains("livetl") || url.contains("thapcam") -> {
                    reqB.header("Referer", "https://thapcamtvt1.com/")
                    reqB.header("Origin", "https://thapcamtvt1.com")
                }
            }
            sharedStreamOkHttpClient.newCall(reqB.build()).execute().use { res ->
                res.body?.source()?.request(2048)
            }
        } catch (_: Exception) {}
    }

    suspend fun raceFastestHealthyStream(candidates: List<String>, timeoutMs: Long = 1100L): String? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val valid = candidates.filter { it.startsWith("http") }.distinct().take(3)
            if (valid.size <= 1) return@withContext valid.firstOrNull()
            try {
                kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                    val jobs = valid.map { candidateUrl ->
                        async {
                            val startNs = System.nanoTime()
                            try {
                                val reqB = okhttp3.Request.Builder()
                                    .url(candidateUrl)
                                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 DuyTV/1.0")
                                when {
                                    candidateUrl.contains("cdnfastter") || candidateUrl.contains("khoga") -> {
                                        reqB.header("Referer", "https://khoga1.live/")
                                        reqB.header("Origin", "https://khoga1.live")
                                    }
                                    candidateUrl.contains("livetl") || candidateUrl.contains("thapcam") -> {
                                        reqB.header("Referer", "https://thapcamtvt1.com/")
                                        reqB.header("Origin", "https://thapcamtvt1.com")
                                    }
                                }
                                sharedStreamOkHttpClient.newCall(reqB.build()).execute().use { res ->
                                    if (res.isSuccessful) {
                                        res.body?.source()?.request(512)
                                        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
                                        Pair(candidateUrl, elapsedMs)
                                    } else null
                                }
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    val results = jobs.mapNotNull { it.await() }
                    results.minByOrNull { it.second }?.first
                }
            } catch (_: Exception) {
                null
            }
        }
}
