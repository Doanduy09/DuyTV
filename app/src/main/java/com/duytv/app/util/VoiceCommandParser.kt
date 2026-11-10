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
    
    // 🌐 Lệnh điều khiển toàn năng hệ thống TV & Mở mọi ứng dụng
    data class OpenApp(val target: String) : VoiceCommand()
    data class AdjustVolume(val isIncrease: Boolean) : VoiceCommand()
    object ToggleMute : VoiceCommand()
    enum class SystemActionType { HOME, BACK, POWER }
    data class SystemAction(val type: SystemActionType) : VoiceCommand()
    
    data class Unknown(val rawText: String) : VoiceCommand()
}

object VoiceCommandParser {

    private val VIETNAMESE_NUMBERS = mapOf(
        "mười" to "10", "chín" to "9", "tám" to "8", "bảy" to "7", "bẩy" to "7",
        "sáu" to "6", "năm" to "5", "bốn" to "4", "tư" to "4", "ba" to "3",
        "hai" to "2", "một" to "1", "mốt" to "1", "không" to "0"
    )

    private val PHONETIC_ALIASES = listOf(
        // HTV TP.HCM (mở rộng đầy đủ mọi cách phát âm tự nhiên)
        "hát tê vê" to "htv",
        "hat te ve" to "htv",
        "hắt tê vê" to "htv",
        "hát tivi" to "htv",
        "hắt tivi" to "htv",
        "hát tv" to "htv",
        "h t v" to "htv",

        // VTV Đài Truyền hình Việt Nam
        "vê tê vê" to "vtv",
        "ve te ve" to "vtv",
        "vê tivi" to "vtv",
        "vê tv" to "vtv",
        "v t v" to "vtv",

        // VTC Kỹ thuật số
        "vê tê xê" to "vtc",
        "ve te xe" to "vtc",
        "v t c" to "vtc",

        // THVL Truyền hình Vĩnh Long
        "tê hát vê lờ" to "thvl",
        "te hat ve lo" to "thvl",
        "t h v l" to "thvl",
        "th v l" to "thvl",
        "vĩnh long" to "thvl",
        "vinh long" to "thvl",

        // SCTV
        "ét xê tê vê" to "sctv",
        "ét xê tivi" to "sctv",
        "s c t v" to "sctv",

        // K+ Thể thao
        "k cộng" to "k+",
        "ca cộng" to "k+",
        "k plus" to "k+",

        // Kênh Giải trí khác
        "tu đây" to "today",
        "thô đây" to "today",
        "bê tê vê" to "btv"
    )

    // Tiền tố câu nói thông thường của người Việt
    private val PREFIX_CLEANUP = listOf(
        "chuyển sang kênh", "chuyển qua kênh", "chuyển đến kênh", "chuyển tới kênh",
        "chuyển sang đài", "chuyển qua đài", "chuyển kênh", "chuyển đài",
        "mở kênh", "mở đài", "mở cho anh kênh", "mở cho anh đài", "mở cho anh", "mở cho em kênh", "mở cho em đài", "mở cho em", "mở",
        "bật kênh", "bật đài", "bật cho anh kênh", "bật cho anh đài", "bật cho anh", "bật cho em kênh", "bật cho em đài", "bật cho em", "bật",
        "xem kênh", "xem đài", "xem cho anh kênh", "xem cho anh đài", "xem cho anh", "xem cho em", "xem",
        "cho xem kênh", "cho xem đài", "cho xem", "cho anh xem", "cho em xem", "kênh số", "kênh", "đài"
    )

    // Hậu tố câu nói giao tiếp thân mật của người Việt
    private val SUFFIX_CLEANUP = listOf(
        "cho anh với", "cho anh nhé", "cho anh nha", "cho anh đi", "cho anh xem", "cho anh",
        "cho em với", "cho em nhé", "cho em nha", "cho em đi", "cho em xem", "cho em",
        "cho tôi với", "cho tôi nhé", "cho tôi nha", "cho tôi đi", "cho tôi xem", "cho tôi",
        "cho tao với", "cho tao xem", "cho tao",
        "giúp anh với", "giúp anh nhé", "giúp anh", "giúp em với", "giúp em nhé", "giúp em",
        "giùm anh với", "giùm anh nhé", "giùm anh", "giùm em với", "giùm em nhé", "giùm em", "giùm với", "giùm",
        "hộ anh với", "hộ anh nhé", "hộ anh", "hộ em với", "hộ em nhé", "hộ em", "hộ tôi", "hộ",
        "đi mà", "đi nào", "đi nhé", "đi nha", "đi",
        "nhé anh", "nhé em", "nhé", "nha anh", "nha em", "nha", "nào", "với", "ơi"
    )

    fun normalizeQuery(raw: String): String {
        var text = raw.lowercase().trim()

        // 1. Thay thế phiên âm đài tiếng Việt (vê tê vê -> vtv, hát tê vê -> htv...)
        for ((phonetic, standard) in PHONETIC_ALIASES) {
            text = text.replace(phonetic, standard)
        }

        // 2. Chuyển đổi chữ số tiếng Việt sang số (chín -> 9, một -> 1, hai -> 2...)
        for ((word, digit) in VIETNAMESE_NUMBERS) {
            text = text.replace(Regex("\\b$word\\b"), digit)
        }

        // 3. Gom chữ viết tắt đài với chữ số liền nhau ("htv 9" -> "htv9", "vtv 1" -> "vtv1")
        text = text.replace(Regex("([a-z]+)\\s+(\\d+)"), "$1$2")

        return text.trim()
    }

    fun parse(rawText: String, availableChannels: List<TvChannel>): VoiceCommand {
        val query = normalizeQuery(rawText)
        if (query.isBlank()) return VoiceCommand.Unknown(rawText)

        // I. LỆNH ĐIỀU KHIỂN ÂM LƯỢNG & LOA TIVI
        if (query.contains("tăng âm") || query.contains("tăng loa") || query.contains("lớn hơn") || query.contains("to hơn") || query.contains("tăng volume") || query == "to lên") {
            return VoiceCommand.AdjustVolume(isIncrease = true)
        }
        if (query.contains("giảm âm") || query.contains("giảm loa") || query.contains("nhỏ hơn") || query.contains("nhỏ lại") || query.contains("giảm volume") || query == "nhỏ xuống") {
            return VoiceCommand.AdjustVolume(isIncrease = false)
        }
        if (query.contains("tắt tiếng") || query.contains("câm tiếng") || query.contains("tắt âm") || query.contains("mute") || query.contains("bật tiếng") || query.contains("mở tiếng")) {
            return VoiceCommand.ToggleMute
        }

        // II. LỆNH ĐIỀU KHIỂN HỆ THỐNG TIVI (HOME, BACK, POWER)
        if (query.contains("về trang chủ") || query.contains("về màn hình chính") || query.contains("màn hình chính") || query.contains("về home") || query == "home" || query == "trang chủ") {
            return VoiceCommand.SystemAction(VoiceCommand.SystemActionType.HOME)
        }
        if (query.contains("quay lại") || query.contains("trở lại") || query == "lùi lại" || query == "thoát") {
            return VoiceCommand.SystemAction(VoiceCommand.SystemActionType.BACK)
        }
        if (query.contains("tắt tivi") || query.contains("tắt màn hình") || query.contains("tắt tv") || query.contains("đi ngủ")) {
            return VoiceCommand.SystemAction(VoiceCommand.SystemActionType.POWER)
        }

        // III. LỆNH MỞ ỨNG DỤNG BẤT KỲ TRÊN TIVI (YouTube, Netflix, Settings...)
        val openAppPrefixes = listOf("mở ứng dụng ", "bật ứng dụng ", "mở app ", "bật app ", "mở ", "bật ")
        for (pref in openAppPrefixes) {
            if (query.startsWith(pref)) {
                val candidate = query.removePrefix(pref).trim()
                val knownApps = listOf("youtube", "netflix", "cài đặt", "settings", "xplore", "x-plore", "ch play", "google play", "cửa hàng", "ott navigator", "trình duyệt", "browser")
                if (knownApps.any { candidate.contains(it) }) {
                    return VoiceCommand.OpenApp(candidate)
                }
            }
        }

        // IV. LỆNH ĐIỀU KHIỂN PHÁT (Playback Controls)
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

        // V. LỆNH TÌM KIẾM PHIM
        if (query.startsWith("tìm phim ") || query.startsWith("tìm kiếm ") || query.startsWith("xem phim ")) {
            val movieQuery = query.removePrefix("tìm phim ").removePrefix("tìm kiếm ").removePrefix("xem phim ").trim()
            if (movieQuery.isNotBlank()) {
                return VoiceCommand.SearchMovie(movieQuery)
            }
        }

        // VI. CHUYỂN KÊNH TRUYỀN HÌNH DUYTV
        if (availableChannels.isNotEmpty()) {
            // 1. Loại bỏ tiền tố nói chuyện thông thường
            var cleanedTarget = query
            for (prefix in PREFIX_CLEANUP) {
                if (cleanedTarget.startsWith(prefix)) {
                    cleanedTarget = cleanedTarget.removePrefix(prefix).trim()
                    break
                }
            }

            // 2. Loại bỏ hậu tố thân mật giao tiếp tiếng Việt ("cho anh", "giúp em", "hộ tôi"...)
            for (suffix in SUFFIX_CLEANUP) {
                if (cleanedTarget.endsWith(suffix)) {
                    cleanedTarget = cleanedTarget.removeSuffix(suffix).trim()
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

            // C. Khớp theo chuỗi con rút gọn không dấu, không khoảng trắng (SO KHỚP ĐA CHIỀU)
            val compactTarget = cleanedTarget.replace(Regex("[^a-z0-9]"), "")
            if (compactTarget.isNotBlank()) {
                val fuzzyIdx = availableChannels.indexOfFirst { ch ->
                    val cleanCh = MovieRepository.normalizeChannelKey(ch.name).replace(Regex("[^a-z0-9]"), "")
                    cleanCh.isNotBlank() && (cleanCh == compactTarget || cleanCh.contains(compactTarget) || compactTarget.contains(cleanCh))
                }
                if (fuzzyIdx >= 0) {
                    return VoiceCommand.SwitchChannel(fuzzyIdx, availableChannels[fuzzyIdx])
                }

                // Thử khớp theo tên gốc không dấu
                val targetNoAccent = removeAccents(cleanedTarget)
                val nameMatchIdx = availableChannels.indexOfFirst { ch ->
                    val chNoAccent = removeAccents(ch.name)
                    chNoAccent.contains(targetNoAccent, ignoreCase = true) || targetNoAccent.contains(chNoAccent, ignoreCase = true)
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
