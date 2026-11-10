package com.duytv.app.util

import com.duytv.app.data.TvChannel
import com.duytv.app.data.MovieRepository
import java.text.Normalizer
import java.util.regex.Pattern

sealed class VoiceCommand {
    data class SwitchChannel(val index: Int, val channel: TvChannel) : VoiceCommand()
    object NextChannel : VoiceCommand()
    object PreviousChannel : VoiceCommand()
    object Pause : VoiceCommand()
    object Resume : VoiceCommand()
    object SwitchBackupSource : VoiceCommand()
    object ToggleStretch : VoiceCommand()
    data class SearchMovie(val query: String) : VoiceCommand()
    data class Unknown(val rawText: String) : VoiceCommand()
}

object VoiceCommandParser {

    private val VIETNAMESE_NUMBERS = mapOf(
        "mười" to "10", "chín" to "9", "tám" to "8", "bảy" to "7", "bẩy" to "7",
        "sáu" to "6", "năm" to "5", "bốn" to "4", "tư" to "4", "ba" to "3",
        "hai" to "2", "một" to "1", "mốt" to "1", "không" to "0"
    )

    private val PHONETIC_ALIASES = listOf(
        "vê tê vê" to "vtv",
        "ve te ve" to "vtv",
        "hát tê vê" to "htv",
        "hat te ve" to "htv",
        "vê tê xê" to "vtc",
        "ve te xe" to "vtc",
        "tê hát vê lờ" to "thvl",
        "te hat ve lo" to "thvl",
        "vĩnh long" to "thvl",
        "vinh long" to "thvl",
        "tu đây" to "today",
        "thô đây" to "today",
        "k cộng" to "k+",
        "ca cộng" to "k+",
        "ét xê tê vê" to "sctv",
        "bê tê vê" to "btv"
    )

    private val PREFIX_CLEANUP = listOf(
        "chuyển sang kênh", "chuyển qua kênh", "chuyển đến kênh", "chuyển tới kênh",
        "chuyển sang đài", "chuyển qua đài", "chuyển kênh", "chuyển đài",
        "mở kênh", "mở đài", "mở cho anh kênh", "mở cho anh đài", "mở cho anh", "mở",
        "bật kênh", "bật đài", "bật cho anh kênh", "bật cho anh đài", "bật cho anh", "bật",
        "xem kênh", "xem đài", "xem cho anh", "xem",
        "cho xem kênh", "cho xem đài", "cho xem", "cho anh xem", "kênh số", "kênh", "đài"
    )

    fun normalizeQuery(raw: String): String {
        var text = raw.lowercase().trim()

        // 1. Thay thế phiên âm đài tiếng Việt (vê tê vê -> vtv, hát tê vê -> htv...)
        for ((phonetic, standard) in PHONETIC_ALIASES) {
            text = text.replace(phonetic, standard)
        }

        // 2. Chuyển đổi chữ số tiếng Việt sang số (một -> 1, hai -> 2...)
        for ((word, digit) in VIETNAMESE_NUMBERS) {
            text = text.replace(Regex("\\b$word\\b"), digit)
        }

        return text.trim()
    }

    fun parse(rawText: String, availableChannels: List<TvChannel>): VoiceCommand {
        val query = normalizeQuery(rawText)
        if (query.isBlank()) return VoiceCommand.Unknown(rawText)

        // I. LỆNH ĐIỀU KHIỂN PHÁT (Playback Controls)
        when {
            query.contains("kênh tiếp") || query.contains("tiếp theo") || query.contains("chuyển tiếp") || query.contains("kênh sau") -> {
                return VoiceCommand.NextChannel
            }
            query.contains("kênh trước") || query.contains("kênh lùi") || query.contains("quay lại") || query.contains("kênh vừa xem") -> {
                return VoiceCommand.PreviousChannel
            }
            query.contains("tạm dừng") || query == "dừng" || query == "pause" -> {
                return VoiceCommand.Pause
            }
            query.contains("phát tiếp") || query.contains("tiếp tục") || query == "play" || query == "phát" -> {
                return VoiceCommand.Resume
            }
            query.contains("đổi nguồn") || query.contains("đổi server") || query.contains("server dự phòng") || query.contains("đổi link") -> {
                return VoiceCommand.SwitchBackupSource
            }
            query.contains("tràn viền") || query.contains("toàn màn hình") || query.contains("tỷ lệ") || query.contains("co giãn") -> {
                return VoiceCommand.ToggleStretch
            }
        }

        // II. LỆNH TÌM KIẾM PHIM
        if (query.startsWith("tìm phim ") || query.startsWith("tìm kiếm ") || query.startsWith("xem phim ")) {
            val movieQuery = query.removePrefix("tìm phim ").removePrefix("tìm kiếm ").removePrefix("xem phim ").trim()
            if (movieQuery.isNotBlank()) {
                return VoiceCommand.SearchMovie(movieQuery)
            }
        }

        // III. CHUYỂN KÊNH TRUYỀN HÌNH
        if (availableChannels.isNotEmpty()) {
            // 1. Loại bỏ các tiền tố nói chuyện thông thường ("mở kênh", "bật cho anh kênh", "chuyển sang"...)
            var cleanedTarget = query
            for (prefix in PREFIX_CLEANUP) {
                if (cleanedTarget.startsWith(prefix)) {
                    cleanedTarget = cleanedTarget.removePrefix(prefix).trim()
                    break
                }
            }

            // A. Khớp theo số kênh trực tiếp ("kênh 5", "số 10")
            val numberMatch = Regex("^\\s*(\\d{1,3})\\s*$").find(cleanedTarget)
            if (numberMatch != null) {
                val channelNumber = numberMatch.groupValues[1].toIntOrNull()
                if (channelNumber != null && channelNumber in 1..availableChannels.size) {
                    val targetIdx = channelNumber - 1
                    return VoiceCommand.SwitchChannel(targetIdx, availableChannels[targetIdx])
                }
            }

            // B. Khớp theo mã định danh kênh chuẩn (MovieRepository.normalizeChannelKey)
            val normalizedTargetKey = MovieRepository.normalizeChannelKey(cleanedTarget)
            val directKeyMatchIdx = availableChannels.indexOfFirst { ch ->
                val chKey = MovieRepository.normalizeChannelKey(ch.name)
                chKey == normalizedTargetKey && normalizedTargetKey.isNotBlank()
            }
            if (directKeyMatchIdx >= 0) {
                return VoiceCommand.SwitchChannel(directKeyMatchIdx, availableChannels[directKeyMatchIdx])
            }

            // C. Khớp theo chuỗi con rút gọn không dấu, không khoảng trắng
            val compactTarget = cleanedTarget.replace(Regex("[^a-z0-9]"), "")
            if (compactTarget.isNotBlank()) {
                val fuzzyIdx = availableChannels.indexOfFirst { ch ->
                    val cleanCh = MovieRepository.normalizeChannelKey(ch.name).replace(Regex("[^a-z0-9]"), "")
                    cleanCh == compactTarget || (cleanCh.isNotBlank() && cleanCh.contains(compactTarget))
                }
                if (fuzzyIdx >= 0) {
                    return VoiceCommand.SwitchChannel(fuzzyIdx, availableChannels[fuzzyIdx])
                }

                // Thử khớp theo tên gốc không dấu
                val targetNoAccent = removeAccents(cleanedTarget)
                val nameMatchIdx = availableChannels.indexOfFirst { ch ->
                    val chNoAccent = removeAccents(ch.name)
                    chNoAccent.contains(targetNoAccent, ignoreCase = true)
                }
                if (nameMatchIdx >= 0) {
                    return VoiceCommand.SwitchChannel(nameMatchIdx, availableChannels[nameMatchIdx])
                }
            }

            // D. Khớp từ khóa thể thao / bóng đá
            if (query.contains("bóng đá") || query.contains("thể thao") || query.contains("ngoại hạng")) {
                val sportsIdx = availableChannels.indexOfFirst { ch ->
                    val group = ch.groupTitle?.uppercase() ?: ""
                    val name = ch.name.uppercase()
                    group.contains("THỂ THAO") || group.contains("SPORTS") || name.contains("SPORTS") || name.contains("ON FOOTBALL") || name.contains("ON SPORTS")
                }
                if (sportsIdx >= 0) {
                    return VoiceCommand.SwitchChannel(sportsIdx, availableChannels[sportsIdx])
                }
            }
        }

        return VoiceCommand.Unknown(rawText)
    }

    private fun removeAccents(text: String): String {
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        return Pattern.compile("\\p{InCombiningDiacriticalMarks}+").matcher(nfd)
            .replaceAll("")
            .replace('đ', 'd').replace('Đ', 'D')
            .lowercase()
    }
}
