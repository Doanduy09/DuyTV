package com.duytv.launcher.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone

enum class MatchLiveStatus {
    LIVE,
    UPCOMING,
    FINISHED
}

data class LauncherMatchItem(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffTime: String,
    val tournament: String,
    val logoUrl: String?,
    val streamUrl: String,
    val backupUrls: List<String> = emptyList(),
    val commentator: String = "BLV Tiếng Việt"
) {
    fun getElapsedMinutes(): Int {
        return try {
            val parts = kickoffTime.split(":")
            if (parts.size != 2) return 0
            val matchHour = parts[0].trim().toInt()
            val matchMin = parts[1].trim().toInt()

            val cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+7"))
            val curHour = cal.get(Calendar.HOUR_OF_DAY)
            val curMin = cal.get(Calendar.MINUTE)

            val matchTotalMins = matchHour * 60 + matchMin
            val curTotalMins = curHour * 60 + curMin

            var diff = curTotalMins - matchTotalMins
            if (diff < -720) diff += 1440
            if (diff > 720) diff -= 1440
            diff
        } catch (_: Exception) {
            0
        }
    }

    fun getStatus(): MatchLiveStatus {
        val diff = getElapsedMinutes()
        return when {
            // Trận đấu bóng đá kéo dài tối đa ~115 phút (90p + 15p nghỉ giữa hiệp + 10p bù giờ).
            // Quá 115 phút kể từ giờ bóng lăn -> Đã kết thúc (FINISHED) -> Xóa khỏi danh sách!
            diff in -10..115 -> MatchLiveStatus.LIVE
            diff < -10 -> MatchLiveStatus.UPCOMING
            else -> MatchLiveStatus.FINISHED
        }
    }

    fun getSortWeight(): Int {
        val status = getStatus()
        val diff = getElapsedMinutes()
        return when (status) {
            // Trận đang LIVE: ưu tiên trận mới bắt đầu gần nhất lên đầu tiên (diff nhỏ hơn đứng trước)
            MatchLiveStatus.LIVE -> diff + 100
            // Trận SẮP ĐÁ: ưu tiên trận sắp diễn ra gần nhất lên trước (-diff nhỏ hơn đứng trước)
            MatchLiveStatus.UPCOMING -> 10_000 + (-diff)
            MatchLiveStatus.FINISHED -> 99_999
        }
    }
}

object SportsRepository {
    private const val CACHE_FILE = "duytv_launcher_sports_cache.json"
    private val KHOGA_API_URLS = listOf(
        "https://khoga1.live/api/matches/?has_commentator=true&page_size=50&ordering=smart",
        "https://khoga1.live/api/matches/?status=live&page_size=40",
        "https://khoga.live/api/matches/?has_commentator=true&page_size=50&ordering=smart"
    )
    private const val THAPCAM_URL = "https://thapcamtvt1.com"
    private val SPORTS_SOURCES = listOf(
        "https://paste.c-net.org/PostersMistrust",
        "https://dl.dropboxusercontent.com/s/o5vygit34v9ryly71gam4/coban66.m3u?rlkey=auyoon54hfubajt16nc7u7dbn&st=70gyvtcu&dl=0",
        "https://raw.githubusercontent.com/KevinNitroG/Entertainment/m3u/playlists/coocaa.m3u",
        "https://raw.githubusercontent.com/khanh71/All-In-One-IPTV/main/http-iptv.m3u"
    )

    fun filterAndSortActiveMatches(matches: List<LauncherMatchItem>): List<LauncherMatchItem> {
        return matches
            .filter { it.getStatus() != MatchLiveStatus.FINISHED }
            .sortedBy { it.getSortWeight() }
    }

    fun getCachedMatches(context: Context): List<LauncherMatchItem> {
        return try {
            val file = File(context.filesDir, CACHE_FILE)
            if (!file.exists()) return emptyList()
            val arr = JSONArray(file.readText())
            val result = mutableListOf<LauncherMatchItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val backups = mutableListOf<String>()
                val bArr = obj.optJSONArray("backupUrls")
                if (bArr != null) {
                    for (j in 0 until bArr.length()) {
                        val u = bArr.optString(j)
                        if (u.isNotBlank()) backups.add(u)
                    }
                }
                result.add(
                    LauncherMatchItem(
                        id = obj.optString("id"),
                        homeTeam = obj.optString("homeTeam"),
                        awayTeam = obj.optString("awayTeam"),
                        kickoffTime = obj.optString("kickoffTime"),
                        tournament = obj.optString("tournament", "Trực Tiếp Bóng Đá"),
                        logoUrl = obj.optString("logoUrl").takeIf { it.isNotBlank() },
                        streamUrl = obj.optString("streamUrl"),
                        backupUrls = backups,
                        commentator = obj.optString("commentator", "BLV Tiếng Việt")
                    )
                )
            }
            filterAndSortActiveMatches(result)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCache(context: Context, matches: List<LauncherMatchItem>) {
        try {
            val arr = JSONArray()
            for (m in matches) {
                val obj = JSONObject().apply {
                    put("id", m.id)
                    put("homeTeam", m.homeTeam)
                    put("awayTeam", m.awayTeam)
                    put("kickoffTime", m.kickoffTime)
                    put("tournament", m.tournament)
                    put("logoUrl", m.logoUrl ?: "")
                    put("streamUrl", m.streamUrl)
                    put("commentator", m.commentator)
                    val bArr = JSONArray()
                    m.backupUrls.forEach { bArr.put(it) }
                    put("backupUrls", bArr)
                }
                arr.put(obj)
            }
            File(context.filesDir, CACHE_FILE).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }

    suspend fun fetchLatestMatches(context: Context): List<LauncherMatchItem> = withContext(Dispatchers.IO) {
        val rawMatches = mutableListOf<LauncherMatchItem>()
        val nowMillis = System.currentTimeMillis()

        // 1. Ưu tiên số 1: Kho Gà & Xôi Chè Real-time JSON API (có cả HLS .m3u8 và FLV dự phòng)
        rawMatches.addAll(fetchKhoGaApiMatches(nowMillis))

        // 2. Ưu tiên số 2: Thập Cẩm TV / AntLive Real-time (4 Server Tencent & Alibaba CDN)
        rawMatches.addAll(fetchThapCamMatches(nowMillis))

        // 3. Nguồn dự phòng M3U (Kho Gà Paste, CoBan66, CooCaa, All-In-One IPTV)
        for (sourceUrl in SPORTS_SOURCES) {
            try {
                val conn = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 8.0; AQUOS-4KTVX17)")
                }
                if (conn.responseCode == 200) {
                    val text = conn.inputStream.bufferedReader().use { it.readText() }
                    val parsed = parseMatchesFromM3u(text)
                    rawMatches.addAll(parsed)
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }

        if (rawMatches.isEmpty()) {
            return@withContext getCachedMatches(context)
        }

        // Group duplicate matches (multi-server Kho Gà + Xôi Chè + Thập Cẩm) into primary + backup URLs
        val merged = rawMatches
            .groupBy {
                val h = it.homeTeam.lowercase().replace(Regex("\\b(fc|cf|sc|ac|utd|united|city)\\b"), "").replace(Regex("[^a-z0-9]"), "")
                val a = it.awayTeam.lowercase().replace(Regex("\\b(fc|cf|sc|ac|utd|united|city)\\b"), "").replace(Regex("[^a-z0-9]"), "")
                "${h}_vs_${a}_${it.kickoffTime}"
            }
            .map { (_, list) ->
                val primary = list.first()
                val allStreams = list.flatMap { listOf(it.streamUrl) + it.backupUrls }
                    .filter { it.isNotBlank() && !it.contains("example.com") }
                    .distinct()
                primary.copy(
                    streamUrl = allStreams.firstOrNull() ?: primary.streamUrl,
                    backupUrls = if (allStreams.size > 1) allStreams.drop(1) else emptyList()
                )
            }

        val activeSorted = filterAndSortActiveMatches(merged)
        saveCache(context, activeSorted)
        activeSorted
    }

    private fun fetchKhoGaApiMatches(nowMillis: Long): List<LauncherMatchItem> {
        val list = mutableListOf<LauncherMatchItem>()
        val tzVN = TimeZone.getTimeZone("GMT+7")
        val isoFormats = listOf(
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.US),
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
        )
        val hhmmFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).apply { timeZone = tzVN }

        for (apiUrl in KHOGA_API_URLS) {
            try {
                val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 7000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    setRequestProperty("Referer", "https://khoga1.live/")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode == 200) {
                    val jsonText = conn.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONObject(jsonText)
                    val results = root.optJSONArray("results") ?: continue
                    for (i in 0 until results.length()) {
                        val obj = results.optJSONObject(i) ?: continue
                        val statusStr = obj.optString("status", "").lowercase()
                        if (statusStr == "finished" || statusStr == "canceled" || statusStr == "postponed") continue

                        val homeTeam = obj.optString("home_team_name", "").trim()
                        val awayTeam = obj.optString("away_team_name", "").trim()
                        if (homeTeam.isEmpty() || awayTeam.isEmpty()) continue
                        val tournament = obj.optString("tournament_name", "Trực Tiếp Bóng Đá").trim()
                        val logoUrl = obj.optString("home_team_logo", "").trim().takeIf { it.isNotEmpty() }
                        val startTimeRaw = obj.optString("start_time", "").trim()

                        var startEpoch = 0L
                        for (fmt in isoFormats) {
                            try {
                                val d = fmt.parse(startTimeRaw)
                                if (d != null) {
                                    startEpoch = d.time
                                    break
                                }
                            } catch (_: Exception) {}
                        }
                        if (startEpoch > 0L) {
                            val diffMs = startEpoch - nowMillis
                            if (statusStr != "live" && (diffMs < -115 * 60 * 1000L || diffMs > 18 * 3600 * 1000L)) continue
                            if (statusStr == "live" && diffMs < -180 * 60 * 1000L) continue
                        }
                        val kickoffHHmm = if (startEpoch > 0L) {
                            val safeEpoch = if (statusStr == "live" && (nowMillis - startEpoch) > 100 * 60 * 1000L) {
                                nowMillis - 85 * 60 * 1000L
                            } else startEpoch
                            hhmmFormat.format(java.util.Date(safeEpoch))
                        } else {
                            Regex("T(\\d{2}:\\d{2})").find(startTimeRaw)?.groupValues?.getOrNull(1) ?: "20:00"
                        }

                        val allUrls = mutableListOf<String>()
                        var blvName = "Kho Gà TV"
                        val commArr = obj.optJSONArray("commentator_streams")
                        if (commArr != null) {
                            for (j in 0 until commArr.length()) {
                                val cObj = commArr.optJSONObject(j) ?: continue
                                val cName = cObj.optString("commentator_name", "").trim()
                                if (cName.isNotEmpty() && blvName == "Kho Gà TV") blvName = "BLV $cName"
                                val hls = cObj.optString("stream_url", "").trim()
                                val flv = cObj.optString("flv_stream_url", "").trim()
                                if (hls.startsWith("http") && !hls.contains("example.com")) allUrls.add(hls)
                                if (flv.startsWith("http") && !flv.contains("example.com")) allUrls.add(flv)
                            }
                        }
                        val pri = obj.optString("primary_stream_url", "").trim()
                        val bak = obj.optString("backup_stream_url", "").trim()
                        if (pri.startsWith("http") && !pri.contains("example.com")) allUrls.add(pri)
                        if (bak.startsWith("http") && !bak.contains("example.com")) allUrls.add(bak)

                        val distinctUrls = allUrls.distinct()
                        if (distinctUrls.isNotEmpty()) {
                            list.add(
                                LauncherMatchItem(
                                    id = "khoga_${homeTeam}_${awayTeam}_$kickoffHHmm".lowercase().replace(Regex("[^a-z0-9_]"), "_"),
                                    homeTeam = homeTeam,
                                    awayTeam = awayTeam,
                                    kickoffTime = kickoffHHmm,
                                    tournament = tournament,
                                    logoUrl = logoUrl,
                                    streamUrl = distinctUrls.first(),
                                    backupUrls = distinctUrls.drop(1),
                                    commentator = blvName
                                )
                            )
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}
        }
        return list
    }

    private fun fetchThapCamMatches(nowMillis: Long): List<LauncherMatchItem> {
        val list = mutableListOf<LauncherMatchItem>()
        try {
            val conn = (URL(THAPCAM_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 7000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }
            if (conn.responseCode != 200) return emptyList()
            val html = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val tzVN = TimeZone.getTimeZone("GMT+7")
            val todayStr = java.text.SimpleDateFormat("dd/MM", java.util.Locale.US).apply { timeZone = tzVN }.format(java.util.Date(nowMillis))
            val tomorrowStr = java.text.SimpleDateFormat("dd/MM", java.util.Locale.US).apply { timeZone = tzVN }.format(java.util.Date(nowMillis + 24 * 3600 * 1000L))

            val blocks = html.split("class=\"match-card ").drop(1)
            var count = 0
            for (block in blocks) {
                if (count >= 6) break
                val isLiveCard = block.take(80).contains("match-card--live")
                val href = Regex("href=\"(https://thapcamtv[^\"]+/truc-tiep/[^\"]+)\"").find(block)?.groupValues?.getOrNull(1) ?: continue
                val league = Regex("match-card__league[^>]*>\\s*([^<]+)").find(block)?.groupValues?.getOrNull(1)?.trim() ?: "Trực Tiếp Thể Thao"
                val timeMatch = Regex("match-card__time[^>]*>\\s*(\\d{1,2}:\\d{2})\\s+(\\d{1,2}/\\d{1,2})").find(block)
                val timeHHmm = timeMatch?.groupValues?.getOrNull(1)?.trim()?.padStart(5, '0') ?: continue
                val datePart = timeMatch.groupValues.getOrNull(2)?.trim() ?: ""
                if (!isLiveCard && datePart != todayStr && datePart != tomorrowStr) continue

                val teamsMatch = Regex("team--home[\\s\\S]*?team__name[^>]*>\\s*([^<]+)[\\s\\S]*?team--away[\\s\\S]*?team__name[^>]*>\\s*([^<]+)").find(block)
                val home = teamsMatch?.groupValues?.getOrNull(1)?.trim() ?: continue
                val away = teamsMatch.groupValues.getOrNull(2)?.trim() ?: continue
                val logo = Regex("team__logo[^>]*src=\"([^\"]+)\"").find(block)?.groupValues?.getOrNull(1)

                try {
                    val mConn = (URL(href).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 5000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                        setRequestProperty("Referer", "$THAPCAM_URL/")
                    }
                    if (mConn.responseCode == 200) {
                        val mHtml = mConn.inputStream.bufferedReader().use { it.readText() }
                        val hlsUrls = Regex("data-stream-url=\"(https://[^\"]+\\.m3u8[^\"]*)\"").findAll(mHtml).map { it.groupValues[1].trim() }.toList()
                        val flvUrls = Regex("data-stream-url-flv=\"(https://[^\"]+\\.flv[^\"]*)\"").findAll(mHtml).map { it.groupValues[1].trim() }.toList()
                        val allUrls = (hlsUrls + flvUrls).distinct()
                        if (allUrls.isNotEmpty()) {
                            list.add(
                                LauncherMatchItem(
                                    id = "thapcam_${home}_${away}_$timeHHmm".lowercase().replace(Regex("[^a-z0-9_]"), "_"),
                                    homeTeam = home,
                                    awayTeam = away,
                                    kickoffTime = timeHHmm,
                                    tournament = league,
                                    logoUrl = logo,
                                    streamUrl = allUrls.first(),
                                    backupUrls = allUrls.drop(1),
                                    commentator = "Thập Cẩm TV"
                                )
                            )
                            count++
                        }
                    }
                    mConn.disconnect()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        return list
    }

    private fun parseMatchesFromM3u(m3uContent: String): List<LauncherMatchItem> {
        val matches = mutableListOf<LauncherMatchItem>()
        val lines = m3uContent.lines()

        var currentTitle = ""
        var currentGroup = ""
        var currentLogo: String? = null

        val groupRegex = Regex("""group-title="([^"]*)"""")
        val logoRegex = Regex("""tvg-logo="([^"]*)"""")
        val bracketTimeRegex = Regex("""\[(\d{1,2}:\d{2})\]""")
        val textTimeRegex = Regex("""(?:vào|lúc)\s+(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTINF:")) {
                currentGroup = groupRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: ""
                currentLogo = logoRegex.find(trimmed)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                val commaIdx = trimmed.lastIndexOf(',')
                currentTitle = if (commaIdx != -1 && commaIdx < trimmed.length - 1) {
                    trimmed.substring(commaIdx + 1).trim()
                } else {
                    "Trận đấu trực tiếp"
                }
            } else if (!trimmed.startsWith("#") && (trimmed.startsWith("http://") || trimmed.startsWith("https://"))) {
                val cleanUrl = trimmed.split("|").first().trim()
                val isVsMatch = currentTitle.contains(" vs ", ignoreCase = true) ||
                    currentTitle.contains(" - ", ignoreCase = false) && currentGroup.contains("Trực Tiếp", ignoreCase = true)

                val bracketTime = bracketTimeRegex.find(currentTitle)?.groupValues?.get(1)
                val textTime = textTimeRegex.find(currentTitle)?.groupValues?.get(1)
                val kickoffTime = bracketTime ?: textTime

                if (isVsMatch && kickoffTime != null) {
                    // Clean title to extract Home & Away teams
                    val stripped = currentTitle
                        .replace(Regex("""\[.*?\]"""), "")
                        .replace(Regex("""^(?:Xem bóng đá|Link xem trực tiếp|Tường thuật trực tiếp|Tường thuật|Trực tiếp)\s+""", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("""\s+(?:vào|lúc)\s+\d{1,2}:\d{2}.*$""", RegexOption.IGNORE_CASE), "")
                        .trim()

                    val vsParts = stripped.split(Regex("""\s+vs\s+""", RegexOption.IGNORE_CASE), limit = 2)
                    val homeTeam = vsParts.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() } ?: stripped
                    val awayTeam = vsParts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: "Trực Tiếp"

                    // Skip obvious non-football or badminton court generic names if needed, or keep all live sports
                    val rawTour = currentGroup
                        .replace(Regex("""^(?:Trực Tiếp|Bóng Đá|Thể Thao)\s*[-|:]?\s*""", RegexOption.IGNORE_CASE), "")
                        .trim()
                        .ifEmpty { "Trực Tiếp Bóng Đá" }

                    val commentator = when {
                        currentTitle.contains("Kho Ga", ignoreCase = true) -> "Kho Gà TV"
                        currentTitle.contains("Thap Cam", ignoreCase = true) -> "Thập Cẩm TV"
                        else -> "BLV Tiếng Việt"
                    }

                    matches.add(
                        LauncherMatchItem(
                            id = "match_${homeTeam}_${awayTeam}_$kickoffTime".lowercase().replace(Regex("[^a-z0-9_]"), "_"),
                            homeTeam = homeTeam,
                            awayTeam = awayTeam,
                            kickoffTime = kickoffTime,
                            tournament = rawTour,
                            logoUrl = currentLogo,
                            streamUrl = cleanUrl,
                            backupUrls = emptyList(),
                            commentator = commentator
                        )
                    )
                }

                currentTitle = ""
                currentGroup = ""
                currentLogo = null
            }
        }

        return matches
    }
}
