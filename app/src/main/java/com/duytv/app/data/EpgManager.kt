package com.duytv.app.data

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Quản lý Lịch Phát Sóng Trực Tuyến (Live EPG) Siêu Nhẹ cho DuyTV trên Android TV.
 *
 * Triết lý Karpathy Anti-Bloat:
 * - TUYỆT ĐỐI KHÔNG tải hoặc phân tích file XMLTV 50MB-80MB trên Android TV (tránh tràn RAM Heap và quá tải CPU Cortex-A53).
 * - Sử dụng thuật toán tra cứu thời gian thực O(1) dựa trên múi giờ Việt Nam (GMT+7) và khung giờ phát sóng chuẩn của các nhà đài.
 * - Hỗ trợ nạp ghi đè lịch trình động từ server khi có cập nhật trận cầu tâm điểm.
 */
object EpgManager {

    private val dynamicEpgCache = ConcurrentHashMap<String, String>()

    data class TimeSlot(
        val startHour: Int,
        val startMin: Int,
        val endHour: Int,
        val endMin: Int,
        val title: String
    ) {
        fun matches(hour: Int, minute: Int): Boolean {
            val currentMinutes = hour * 60 + minute
            val startMinutes = startHour * 60 + startMin
            val endMinutes = endHour * 60 + endMin
            return if (startMinutes <= endMinutes) {
                currentMinutes in startMinutes until endMinutes
            } else {
                // Khung giờ vắt qua nửa đêm (VD: 22:30 -> 01:00)
                currentMinutes >= startMinutes || currentMinutes < endMinutes
            }
        }
    }

    private val VTV1_SCHEDULE = listOf(
        TimeSlot(5, 30, 7, 0, "Chào Buổi Sáng"),
        TimeSlot(7, 0, 8, 30, "Bản Tin Tài Chính - Kinh Doanh"),
        TimeSlot(8, 30, 11, 30, "Chuyên Đề VTV1 Đặc Sắc"),
        TimeSlot(11, 30, 12, 15, "Thời Sự 12h & Tiêu Điểm"),
        TimeSlot(12, 15, 13, 0, "Phim Truyện VTV1"),
        TimeSlot(13, 0, 17, 30, "Việt Nam Hôm Nay & Văn Hóa Số"),
        TimeSlot(17, 30, 18, 30, "Chuyển Động 24h"),
        TimeSlot(18, 30, 19, 0, "Thể Thao 24/7 & Dự Báo Thời Tiết"),
        TimeSlot(19, 0, 19, 50, "Thời Sự 19h (Trực Tiếp)"),
        TimeSlot(19, 50, 20, 30, "Thời Sự Quốc Tế & Tiêu Điểm"),
        TimeSlot(20, 30, 21, 40, "Phim Truyện Giờ Vàng VTV1"),
        TimeSlot(21, 40, 22, 30, "Vấn Đề Hôm Nay & Thể Thao"),
        TimeSlot(22, 30, 5, 30, "Phim Tài Liệu & Đêm Khuya")
    )

    private val VTV3_SCHEDULE = listOf(
        TimeSlot(6, 0, 8, 0, "Cà Phê Sáng Cùng VTV3"),
        TimeSlot(8, 0, 11, 0, "Phim Truyện Buổi Sáng VTV3"),
        TimeSlot(11, 0, 12, 0, "Gameshow Truyền Hình Thực Tế"),
        TimeSlot(12, 0, 13, 0, "Phim Truyện Trưa VTV3"),
        TimeSlot(13, 0, 17, 0, "Gameshow & Ca Nhạc Giải Trí"),
        TimeSlot(17, 0, 18, 0, "Phim Truyện Châu Á"),
        TimeSlot(18, 0, 18, 45, "Chuyển Động 24h VTV3"),
        TimeSlot(18, 45, 19, 0, "Thông Điệp Cuộc Sống"),
        TimeSlot(19, 0, 20, 0, "Thời Sự 19h VTV"),
        TimeSlot(20, 0, 21, 30, "Phim Truyện Giờ Vàng VTV3"),
        TimeSlot(21, 30, 22, 45, "Show Truyền Hình Thực Tế Hot"),
        TimeSlot(22, 45, 6, 0, "Phim Truyện Đêm Khuya VTV3")
    )

    private val VTV2_SCHEDULE = listOf(
        TimeSlot(6, 0, 11, 30, "Khoa Học Giáo Dục & Đời Sống"),
        TimeSlot(11, 30, 13, 0, "Phim Truyện Khoa Học"),
        TimeSlot(13, 0, 19, 0, "Thế Giới Động Vật & Khám Phá"),
        TimeSlot(19, 0, 20, 30, "Bản Tin Y Tế & Sức Khỏe"),
        TimeSlot(20, 30, 22, 0, "Phim Truyện VTV2"),
        TimeSlot(22, 0, 6, 0, "Khám Phá Thế Giới Đêm")
    )

    private val HTV7_SCHEDULE = listOf(
        TimeSlot(6, 0, 8, 0, "Chào Ngày Mới HTV7"),
        TimeSlot(8, 0, 11, 30, "Phim Truyện HTV7"),
        TimeSlot(11, 30, 12, 30, "Chương Trình 60 Giây Trưa"),
        TimeSlot(12, 30, 18, 30, "Gameshow HTV7 & Phim Chiều"),
        TimeSlot(18, 30, 19, 0, "Chương Trình 60 Giây Tối"),
        TimeSlot(19, 0, 20, 30, "Gameshow Ăn Khách HTV7"),
        TimeSlot(20, 30, 22, 30, "Phim Truyện Giờ Vàng HTV7"),
        TimeSlot(22, 30, 6, 0, "Thế Giới Muôn Màu & Phim Đêm")
    )

    private val HTV9_SCHEDULE = listOf(
        TimeSlot(6, 0, 7, 0, "Thời Sự Buổi Sáng HTV9"),
        TimeSlot(7, 0, 11, 30, "Tạp Chí Kinh Tế & Đô Thị"),
        TimeSlot(11, 30, 12, 30, "Thời Sự 11h30 HTV9"),
        TimeSlot(12, 30, 17, 30, "Phim Truyện Việt Nam"),
        TimeSlot(17, 30, 18, 30, "Thời Sự 18h30"),
        TimeSlot(18, 30, 20, 0, "Chương Trình 60 Giây Tối"),
        TimeSlot(20, 0, 21, 30, "Thời Sự 20h & Tiêu Điểm"),
        TimeSlot(21, 30, 22, 30, "Phim Tài Liệu HTV9"),
        TimeSlot(22, 30, 6, 0, "Thời Sự Quốc Tế & Phim Đêm")
    )

    private val SPORTS_SCHEDULE = listOf(
        TimeSlot(6, 0, 10, 0, "Highlight Bóng Đá Ngoại Hạng Anh & Cúp C1"),
        TimeSlot(10, 0, 12, 30, "Tạp Chí Thể Thao Quốc Tế & Phân Tích"),
        TimeSlot(12, 30, 17, 0, "Phát Lại Trận Cầu Tâm Điểm Cuối Tuần"),
        TimeSlot(17, 0, 19, 0, "Bản Tin Thể Thao 24/7 & Điểm Tin Nóng"),
        TimeSlot(19, 0, 20, 30, "Bình Luận Trước Trận Đấu & Đội Hình"),
        TimeSlot(20, 30, 23, 30, "🔴 Trực Tiếp Trận Cầu Tâm Điểm Đỉnh Cao"),
        TimeSlot(23, 30, 6, 0, "Tổng Hợp Bàn Thắng & Khoảnh Khắc Vàng")
    )

    private val THVL_SCHEDULE = listOf(
        TimeSlot(6, 0, 8, 30, "Lời Chào Buổi Sáng & Tin Tức"),
        TimeSlot(8, 30, 11, 30, "Phim Truyện Miền Tây THVL"),
        TimeSlot(11, 30, 12, 30, "Thời Sự THVL"),
        TimeSlot(12, 30, 18, 30, "Phim Truyện Đặc Sắc & Ca Nhạc"),
        TimeSlot(18, 30, 19, 0, "Thời Sự Truyền Hình Vĩnh Long"),
        TimeSlot(19, 0, 20, 0, "Chuyện Hôm Nay THVL"),
        TimeSlot(20, 0, 21, 30, "Phim Truyện Giờ Vàng Miền Tây THVL1"),
        TimeSlot(21, 30, 22, 30, "Gameshow THVL"),
        TimeSlot(22, 30, 6, 0, "Phim Truyện Khuya THVL")
    )

    fun getCurrentProgram(channelName: String, timestampMs: Long = System.currentTimeMillis()): String {
        val cleanName = channelName.trim()
        val normalized = cleanName.lowercase()

        // 1. Kiểm tra cache động trước
        dynamicEpgCache[normalized]?.let { return it }

        // 2. Tính toán theo khung giờ Việt Nam (GMT+7)
        val tz = TimeZone.getTimeZone("GMT+7")
        val calendar = Calendar.getInstance(tz).apply { timeInMillis = timestampMs }
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)

        val schedule = when {
            normalized.contains("vtv1") -> VTV1_SCHEDULE
            normalized.contains("vtv3") -> VTV3_SCHEDULE
            normalized.contains("vtv2") -> VTV2_SCHEDULE
            normalized.contains("htv7") -> HTV7_SCHEDULE
            normalized.contains("htv9") -> HTV9_SCHEDULE
            normalized.contains("thvl") || normalized.contains("vĩnh long") -> THVL_SCHEDULE
            normalized.contains("k+") || normalized.contains("sport") ||
                normalized.contains("thể thao") || normalized.contains("football") ||
                normalized.contains("bongda") || normalized.contains("canal+") ||
                normalized.contains("tnt") || normalized.contains("sky") -> SPORTS_SCHEDULE
            normalized.contains("vtv") -> VTV1_SCHEDULE
            normalized.contains("htv") -> HTV7_SCHEDULE
            else -> null
        }

        if (schedule != null) {
            val matched = schedule.firstOrNull { it.matches(hour, minute) }
            if (matched != null) {
                return matched.title
            }
        }

        // Mặc định thân thiện theo buổi trong ngày
        return when (hour) {
            in 6..10 -> "Chào Buổi Sáng & Tin Tức Mới"
            in 11..13 -> "Chương Trình Trưa & Phim Truyện"
            in 14..17 -> "Tạp Chí Văn Hóa & Phim Chiều"
            in 18..21 -> "Chương Trình Giờ Vàng Đỉnh Cao"
            in 22..23 -> "Phim Truyện Đêm & Ca Nhạc"
            else -> "Chương Trình Phát Sóng 24/7"
        }
    }

    fun updateDynamicEpg(channelKey: String, programTitle: String) {
        if (channelKey.isNotBlank() && programTitle.isNotBlank()) {
            dynamicEpgCache[channelKey.lowercase().trim()] = programTitle.trim()
        }
    }
}
