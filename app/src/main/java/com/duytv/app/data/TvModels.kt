package com.duytv.app.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class FootballMatch(
    val id: String,
    val title: String,
    val homeTeam: String,
    val awayTeam: String,
    val homeLogo: String? = null,
    val awayLogo: String? = null,
    val tournament: String = "",
    val kickoffTime: String = "",
    val commentator: String = "",
    val streamUrl: String = "",
    val groupTitle: String = ""
)

data class FootballMatchGroup(
    val id: String,
    val homeTeam: String,
    val awayTeam: String,
    val kickoffTime: String,
    val tournament: String,
    val logo: String? = null,
    val awayLogo: String? = null,
    val streams: List<MatchStreamItem> = emptyList()
)

data class MatchStreamItem(
    val title: String,
    val commentator: String,
    val streamUrl: String
)

data class TvChannel(
    val id: String,
    val name: String,
    val logoUrl: String? = null,
    val streamUrl: String,
    val groupTitle: String = "Truyền Hình",
    val backupUrls: List<String> = emptyList()
) {
    fun allStreamUrls(): List<String> =
        (listOf(streamUrl) + backupUrls).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

data class TvSportsData(
    val matches: List<FootballMatchGroup> = emptyList(),
    val channels: List<TvChannel> = emptyList()
)

enum class MatchStatus {
    UPCOMING, // Sắp diễn ra (đếm ngược thời gian)
    LIVE,     // Đang trực tiếp (bấm vào xem ngay)
    FINISHED  // Đã kết thúc
}

data class MatchStateInfo(
    val status: MatchStatus,
    val label: String,
    val remainingMillis: Long
)

object MatchStateHelper {
    /**
     * Tính toán trạng thái trận đấu thời gian thực dựa trên giờ đá (HH:mm)
     * Đồng bộ theo múi giờ Việt Nam (UTC+7 / GMT+7)
     */
    fun calculateMatchState(kickoffTimeStr: String, nowMillis: Long = System.currentTimeMillis()): MatchStateInfo {
        val parts = kickoffTimeStr.split(":")
        if (parts.size != 2) return MatchStateInfo(MatchStatus.LIVE, "🔴 LIVE", 0L)
        val matchHour = parts[0].trim().toIntOrNull() ?: return MatchStateInfo(MatchStatus.LIVE, "🔴 LIVE", 0L)
        val matchMin = parts[1].trim().toIntOrNull() ?: return MatchStateInfo(MatchStatus.LIVE, "🔴 LIVE", 0L)

        val tz = TimeZone.getTimeZone("GMT+7")
        val calendar = Calendar.getInstance(tz).apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, matchHour)
            set(Calendar.MINUTE, matchMin)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        var matchMillis = calendar.timeInMillis
        // Nếu giờ đá rạng sáng (ví dụ 01:00, 02:00, 03:00) mà hiện tại đang là tối (>= 12:00 trưa):
        // Trận đá vào rạng sáng ngày hôm sau
        val currentHour = Calendar.getInstance(tz).apply { timeInMillis = nowMillis }.get(Calendar.HOUR_OF_DAY)
        if (matchHour < 6 && currentHour >= 12) {
            matchMillis += 24 * 3600 * 1000L
        }

        val diffMs = matchMillis - nowMillis
        val matchDurationMs = 115 * 60 * 1000L // Thời lượng trận đấu tối đa: 115 phút (xóa ngay trận đã hết live)

        return when {
            diffMs > 0 -> {
                // SẮP DIỄN RA (Đếm ngược)
                val totalSeconds = diffMs / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                val countdownText = if (hours > 0) {
                    String.format(Locale.US, "%02dh %02dp", hours, minutes)
                } else {
                    String.format(Locale.US, "%02d:%02d", minutes, seconds)
                }
                MatchStateInfo(MatchStatus.UPCOMING, "⏳ CÒN $countdownText", diffMs)
            }
            diffMs >= -matchDurationMs -> {
                // ĐANG TRỰC TIẾP
                val elapsedMinutes = (-diffMs / (60 * 1000L)).coerceIn(1, 120)
                val progressText = if (elapsedMinutes <= 45) {
                    "H1 (${elapsedMinutes}')"
                } else if (elapsedMinutes <= 60) {
                    "Nghỉ giữa hiệp"
                } else {
                    "H2 (${elapsedMinutes - 15}')"
                }
                MatchStateInfo(MatchStatus.LIVE, "🔴 LIVE $progressText", 0L)
            }
            else -> {
                // ĐÃ KẾT THÚC
                MatchStateInfo(MatchStatus.FINISHED, "⏹️ ĐÃ KẾT THÚC", 0L)
            }
        }
    }
}
