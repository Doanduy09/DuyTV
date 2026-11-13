package com.duytv.app.util

import com.duytv.app.data.TvChannel
import com.duytv.app.data.MovieRepository
import com.duytv.app.data.AppCacheManager
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
    data class OpenMovie(val query: String) : VoiceCommand()
    data class BrowseCategory(val categoryId: String, val categoryName: String) : VoiceCommand()
    
    // 🎬 Điều khiển video chuyên sâu (Deep Playback NLP)
    data class SeekTo(val positionMs: Long, val label: String) : VoiceCommand()
    data class SeekDelta(val deltaSeconds: Int, val label: String) : VoiceCommand()
    data class SwitchEpisode(val episodeNumber: Int) : VoiceCommand()
    object NextEpisode : VoiceCommand()
    object PreviousEpisode : VoiceCommand()
    data class SetPlaybackSpeed(val speed: Float, val label: String) : VoiceCommand()
    object ToggleClearDialog : VoiceCommand()
    object ToggleSubtitle : VoiceCommand()
    object SwitchAudioTrack : VoiceCommand()
    object SkipIntro : VoiceCommand()

    // 💡 Tiện ích thông minh (Smart Utilities)
    data class SetSleepTimer(val minutes: Int, val label: String) : VoiceCommand()
    object ToggleNightMode : VoiceCommand()
    object ActivateScreenOffAudio : VoiceCommand()
    object ToggleTouchLock : VoiceCommand()

    // 🌐 Lệnh điều khiển toàn năng hệ thống TV & Mở mọi ứng dụng
    data class OpenApp(val target: String) : VoiceCommand()
    enum class SettingType { WIFI, BLUETOOTH, SOUND, DISPLAY, APPS, SYSTEM, DATE }
    data class OpenSetting(val settingType: SettingType) : VoiceCommand()
    data class YouTubeSearch(val query: String) : VoiceCommand()
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
        "hát cover" to "htv",
        "hát sever" to "htv",
        "hát sevo" to "htv",
        "hát cever" to "htv",
        "hát cevo" to "htv",
        "hát xever" to "htv",
        "hát xevo" to "htv",
        "hát tơ ve" to "htv",
        "hát te ve" to "htv",
        "hát ti vi" to "htv",
        "hát tôi vô" to "htv",

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
        "bê tê vê" to "btv",

        // Phim & Hoạt hình quốc tế phổ biến (Bilingual Phonetic Movie Mapping)
        "người nhện" to "spider man",
        "nguoi nhen" to "spider man",
        "spiderman" to "spider man",
        "người dơi" to "batman",
        "nguoi doi" to "batman",
        "biệt đội siêu anh hùng" to "avengers",
        "avenger" to "avengers",
        "đại chiến vô cực" to "avengers infinity war",
        "hồi kết" to "avengers endgame",
        "chú bé thám tử" to "conan",
        "thám tử lừng danh" to "conan",
        "thám tử conan" to "conan",
        "vua hải tặc" to "one piece",
        "đảo hải tặc" to "one piece",
        "bảy viên ngọc rồng" to "dragon ball",
        "ngọc rồng" to "dragon ball",
        "quá nhanh quá nguy hiểm" to "fast and furious",
        "fast & furious" to "fast and furious",
        "chiến tranh giữa các vì sao" to "star wars",
        "harry potter" to "harry potter",
        "chúa tể những chiếc nhẫn" to "lord of the rings",
        "lưỡi gươm diệt quỷ" to "kimetsu no yaiba",
        "thanh gươm diệt quỷ" to "kimetsu no yaiba",
        "chú thuật hồi chiến" to "jujutsu kaisen"
    )

    // Tiền tố câu chào giao tiếp tự nhiên của người Việt
    private val CONVERSATIONAL_SALUTATIONS = listOf(
        "em ơi hãy ", "em hãy ", "em ơi ", "ơi em ", "anh ơi ", "chị ơi ", "bạn ơi ",
        "trợ lý ơi ", "duy ơi ", "duytv ơi ", "ok google ", "ê duy ", "này em ", "này duy ",
        "làm ơn ", "vui lòng "
    )

    // Tiền tố hành động phong phú
    private val ACTION_PREFIXES = listOf(
        "em hãy mở cho anh kênh ", "em hãy mở cho em kênh ", "em hãy mở cho anh đài ", "em hãy mở cho em đài ",
        "em hãy mở cho anh ứng dụng ", "em hãy mở ứng dụng ", "em hãy mở app ", "em hãy mở cho anh ", "em hãy mở cho em ",
        "em hãy mở đài ", "em hãy mở kênh ", "em hãy mở phim ", "em hãy mở ",
        "hãy mở cho anh kênh ", "hãy mở cho em kênh ", "hãy mở cho anh đài ", "hãy mở cho em đài ",
        "hãy mở cho anh ứng dụng ", "hãy mở ứng dụng ", "hãy mở app ", "hãy mở cho anh ", "hãy mở cho em ",
        "hãy mở đài ", "hãy mở kênh ", "hãy mở phim ", "hãy mở ",
        "em mở cho anh kênh ", "em mở cho anh đài ", "em mở cho anh ứng dụng ", "em mở ứng dụng ", "em mở app ",
        "em mở cho anh ", "em mở đài ", "em mở kênh ", "em mở phim ", "em mở ",
        "anh muốn xem kênh ", "anh muốn xem đài ", "anh muốn xem phim ", "anh muốn xem ", "anh muốn mở ",
        "cho anh xem phim ", "cho anh xem kênh ", "cho anh xem đài ", "cho anh xem ", "cho em xem ",
        "chuyển sang kênh ", "chuyển qua kênh ", "chuyển đến kênh ", "chuyển tới kênh ",
        "chuyển sang đài ", "chuyển qua đài ", "chuyển kênh ", "chuyển đài ",
        "mở ứng dụng ", "mở app ", "bật ứng dụng ", "bật app ", "chạy ứng dụng ", "chạy app ", "vào ứng dụng ", "vào app ",
        "mở kênh ", "mở đài ", "mở phim ", "bật phim ", "chiếu phim ", "xem phim ", "tìm phim ", "coi phim ",
        "mở cho anh kênh ", "mở cho anh đài ", "mở cho anh ", "mở cho em kênh ", "mở cho em đài ", "mở cho em ",
        "bật cho anh kênh ", "bật cho anh đài ", "bật cho anh ", "bật cho em kênh ", "bật cho em đài ", "bật cho em ",
        "bật kênh ", "bật đài ", "bật ",
        "xem cho anh kênh ", "xem cho anh đài ", "xem cho anh ", "xem cho em ",
        "xem kênh ", "xem đài ", "xem ",
        "mở ", "chiếu ", "tìm kiếm ", "tìm ", "kênh số ", "kênh ", "đài "
    )

    // Hậu tố chỉ định mở trên YouTube
    private val YOUTUBE_MARKERS = listOf(
        "trên ứng dụng youtube cho anh", "trên ứng dụng youtube cho em", "trên ứng dụng youtube",
        "ở ứng dụng youtube cho anh", "ở ứng dụng youtube cho em", "ở ứng dụng youtube",
        "trong ứng dụng youtube", "vào ứng dụng youtube",
        "trên youtube cho anh", "trên youtube cho em", "trên youtube",
        "ở youtube cho anh", "ở youtube cho em", "ở youtube",
        "trong youtube", "qua youtube", "bằng youtube"
    )

    // Hậu tố câu nói giao tiếp thân mật của người Việt
    private val GENERAL_SUFFIXES = listOf(
        "cho anh với", "cho anh nhé", "cho anh nha", "cho anh đi", "cho anh xem", "cho anh",
        "cho em với", "cho em nhé", "cho em nha", "cho em đi", "cho em xem", "cho em",
        "cho tôi với", "cho tôi nhé", "cho tôi nha", "cho tôi đi", "cho tôi xem", "cho tôi",
        "cho tao với", "cho tao xem", "cho tao",
        "giúp anh với", "giúp anh nhé", "giúp anh nha", "giúp anh đi", "giúp anh",
        "giúp em với", "giúp em nhé", "giúp em nha", "giúp em đi", "giúp em",
        "giùm anh với", "giùm anh nhé", "giùm anh nha", "giùm anh đi", "giùm anh",
        "giùm em với", "giùm em nhé", "giùm em nha", "giùm em đi", "giùm em", "giùm với", "giùm",
        "hộ anh với", "hộ anh nhé", "hộ anh nha", "hộ anh đi", "hộ anh",
        "hộ em với", "hộ em nhé", "hộ em nha", "hộ em đi", "hộ em", "hộ tôi", "hộ",
        "lên tivi", "lên tv", "lên", "đi mà", "đi nào", "đi nhé", "đi nha", "đi",
        "nhé anh", "nhé em", "nhé", "nha anh", "nha em", "nha", "nào", "với", "ơi"
    )

    fun normalizeQuery(raw: String): String {
        var text = raw.lowercase().trim()

        // 1. Tự động sửa lỗi phát âm và nhận diện sai của SpeechRecognizer (Phonetic Auto-Correction)
        val corrections = listOf(
            "than tóc đỏ" to "thanh tóc đỏ",
            "than tóc" to "thanh tóc",
            "em hải mở" to "em hãy mở",
            "hải mở" to "hãy mở",
            "phim long bát bộ" to "thiên long bát bộ",
            "thiên long bát bộc" to "thiên long bát bộ",
            "thiên long bát bộ của" to "thiên long bát bộ",
            "hát tôi vô bạn" to "htv7",
            "hát tôi vô" to "htv7",
            "mở bài hát tôi vô bạn" to "mở đài htv7",
            "mở bài hát cover 7" to "mở đài htv7",
            "mở bài hát sever 7" to "mở đài htv7",
            "mở bài hát sevo 7" to "mở đài htv7",
            "mở bài hát bolero 7" to "mở đài htv7",
            "bài hát cover 7" to "đài htv7",
            "bài hát sever 7" to "đài htv7",
            "bài hát sevo 7" to "đài htv7",
            "bài hát cover" to "đài htv7",
            "bài hát sever" to "đài htv7",
            "hát cover 7" to "htv7",
            "hát sever 7" to "htv7",
            "hát sevo 7" to "htv7",
            "mở bài htv" to "mở đài htv",
            "mở bài vtv" to "mở đài vtv",
            "mở bài vtc" to "mở đài vtc",
            "mở bài thvl" to "mở đài thvl",
            "mở bài k+" to "mở đài k+",
            "mở bài đồng tháp" to "mở đài đồng tháp",
            "mở bài vĩnh long" to "mở đài vĩnh long"
        )
        for ((wrong, right) in corrections) {
            text = text.replace(wrong, right)
        }

        // Tự động sửa lỗi Google STT nhận nhầm "mở đài" thành "ở đài" hoặc "ở kênh" (dùng ranh giới Unicode tránh hỏng "mở đài" -> "mmở đài")
        text = text.replace(Regex("(?<!\\p{L})(em\\s+)?ở\\s+(đài|kênh)(?!\\p{L})"), "mở $2")

        // Tự động sửa lỗi Google STT nhận nhầm "đài" thành "bài" khi đi cùng tên kênh truyền hình
        text = text.replace(Regex("\\b(mở|bật|xem|chiếu|chuyển|nghe)?\\s*(bài|bài\\s+hát)\\s+(htv|vtv|vtc|thvl|sctv|k\\+|thdt|đồng tháp|vĩnh long|today|btv|kênh)"), "mở đài $3")

        // Tự động sửa lỗi Google STT nhận nhầm "hãy" thành "hải" hoặc "tải" trước các động từ
        text = text.replace(Regex("\\b(hải|tải|thử)\\s+(mở|bật|tăng|giảm|tìm|xem|chiếu|phát|chuyển|chạy|vào)\\b"), "hãy $2")
        text = text.replace(Regex("^\\s*(hải|tải|thử)\\s+"), "hãy ")

        // 2. Thay thế phiên âm đài tiếng Việt (vê tê vê -> vtv, hát tê vê -> htv...)
        for ((phonetic, standard) in PHONETIC_ALIASES) {
            text = text.replace(phonetic, standard)
        }

        // 3. Chuyển đổi chữ số tiếng Việt sang số (chín -> 9, một -> 1, hai -> 2...)
        for ((word, digit) in VIETNAMESE_NUMBERS) {
            text = text.replace(Regex("(?<!\\p{L})$word(?!\\p{L})"), digit)
        }

        // 4. Gom chữ viết tắt đài với chữ số liền nhau ("htv 9" -> "htv9", "vtv 1" -> "vtv1") — không gom chữ tiếng Việt như "tập 12"
        text = text.replace(Regex("(?<!\\p{L})(vtv|htv|vtc|thvl|sctv|btv|thdt|on|k)\\s+(\\d+)"), "$1$2")

        // 5. Chuẩn hóa tên ứng dụng thông dụng
        text = text.replace("you tube", "youtube")
            .replace("du tu be", "youtube")
            .replace("chplay", "ch play")
            .replace("x plore", "xplore")

        return text.trim()
    }

    /**
     * Kiểm tra xem câu nói có phải là khẩu lệnh ngắn cần kích hoạt tức thì (0ms Zero-Latency Ear-Trigger) hay không.
     */
    fun isQuickTriggerCommand(raw: String): Boolean {
        val t = normalizeQuery(raw).trim()
        if (t.contains("vtv") || t.contains("htv") || t.contains("vtc") || t.contains("thvl") ||
            t.contains("sctv") || t.contains("k+") || t.contains("bóng đá") || t.contains("kênh") || t.contains("đài")) {
            return true
        }
        return t == "dừng" || t == "tạm dừng" || t == "pause" ||
               t == "tiếp tục" || t == "phát tiếp" || t == "play" || t == "phát" ||
               t.startsWith("tăng âm") || t.startsWith("tăng loa") || t == "to hơn" || t == "to lên" || t == "lớn hơn" ||
               t.startsWith("giảm âm") || t.startsWith("giảm loa") || t == "nhỏ hơn" || t == "nhỏ lại" || t == "nhỏ xuống" ||
               t == "tắt tiếng" || t == "bật tiếng" || t == "câm tiếng" || t == "mute" ||
               t == "kênh tiếp" || t == "tiếp theo" || t == "kênh sau" ||
               t == "kênh trước" || t == "kênh lùi" ||
               t == "tập tiếp" || t == "tập tiếp theo" || t == "tập trước" ||
               t == "tràn viền" || t == "toàn màn hình" ||
               t == "quay lại" || t == "thoát" ||
               t == "bỏ qua mở đầu" || t == "skip intro" || t == "qua mở đầu" ||
               t == "lọc giọng nói" || t == "rõ lời thoại" || t == "bật phụ đề" || t == "tắt phụ đề" ||
               t == "nhanh hơn" || t == "chậm lại" || t == "tốc độ bình thường"
    }

    /**
     * Giải mã lưới âm tiết đa ứng viên (N-Candidate Speech Lattice):
     * Duyệt qua toàn bộ mảng EXTRA_RESULTS từ SpeechRecognizer, chấm điểm cấu trúc lệnh
     * để tự động chọn ra ứng viên chính xác nhất thay vì chỉ lấy cứng candidate đầu tiên!
     */
    fun selectBestCandidate(candidates: List<String>, availableChannels: List<TvChannel>): String {
        if (candidates.isEmpty()) return ""
        if (candidates.size == 1) return candidates[0].trim()

        val channels = if (availableChannels.isNotEmpty()) availableChannels else MovieRepository.getVerifiedOfficialChannels()
        var bestCandidate = candidates[0].trim()
        var bestScore = -100

        for (cand in candidates) {
            val raw = cand.trim()
            if (raw.isBlank()) continue
            val norm = normalizeQuery(raw)
            var score = 0

            // 0. Khớp chuẩn kênh truyền hình (Ưu tiên tối cao)
            if (matchTvChannel(norm, channels) != null) {
                score += 250
            }

            // 1. Khẩu lệnh nhanh 0ms (Ưu tiên tuyệt đối)
            if (isQuickTriggerCommand(norm)) {
                score += 150
            }

            // 2. Khớp kênh truyền hình theo tên
            val matchesChannel = channels.any { ch ->
                val chNorm = normalizeQuery(ch.name)
                norm == chNorm || norm.startsWith("mở $chNorm") || norm.startsWith("bật $chNorm") || norm.contains(chNorm)
            }
            if (matchesChannel) {
                score += 100
            }

            // 3. Chứa động từ hành động điều khiển chuẩn
            val actionVerbs = listOf("mở", "bật", "xem", "tăng", "giảm", "dừng", "phát", "tua", "tập", "kênh", "tìm", "chiếu")
            for (verb in actionVerbs) {
                if (norm.startsWith("$verb ") || norm.contains(" $verb ")) {
                    score += 15
                }
            }

            // 4. Khớp ý định YouTube / Âm nhạc / Karaoke
            if (norm.contains("nhạc") || norm.contains("karaoke") || norm.contains("ca sĩ") || norm.contains("bài hát")) {
                score += 25
            }

            // 5. Trừ điểm nếu bị Google STT nhét từ rác đầu câu
            if (raw.startsWith("tải ") || raw.startsWith("hải ") || raw.startsWith("thử ")) {
                score -= 10
            }

            if (score > bestScore) {
                bestScore = score
                bestCandidate = raw
            }
        }
        return bestCandidate
    }

    fun parse(rawText: String, availableChannels: List<TvChannel>): VoiceCommand {
        val query = normalizeQuery(rawText)
        if (query.isBlank()) return VoiceCommand.Unknown(rawText)

        // Tách bỏ lời chào giao tiếp thân mật đầu câu ("Em ơi hãy...", "Em ơi...", "Em hãy...", "Duy ơi...")
        var core = query
        for (salutation in CONVERSATIONAL_SALUTATIONS) {
            if (core.startsWith(salutation)) {
                core = core.removePrefix(salutation).trim()
                break
            }
        }

        // ⚡ 0. ƯU TIÊN TUYỆT ĐỐI: KHỚP KÊNH TRUYỀN HÌNH & NHÀ ĐÀI TỨC THÌ 0ms
        // Miễn là thấy tên các nhà đài (VTV, HTV, VTC, THVL, SCTV, K+,...) -> MỞ NGAY LẬP TỨC 0ms!
        if (availableChannels.isNotEmpty()) {
            val channelMatch = matchTvChannel(core, availableChannels)
            if (channelMatch != null) {
                return channelMatch
            }
        }

        // I. LỆNH ĐIỀU KHIỂN ÂM LƯỢNG & LOA TIVI
        if (core.contains("tăng âm") || core.contains("tăng loa") || core.contains("lớn hơn") || core.contains("to hơn") || core.contains("tăng volume") || core == "to lên") {
            return VoiceCommand.AdjustVolume(isIncrease = true)
        }
        if (core.contains("giảm âm") || core.contains("giảm loa") || core.contains("nhỏ hơn") || core.contains("nhỏ lại") || core.contains("giảm volume") || core == "nhỏ xuống") {
            return VoiceCommand.AdjustVolume(isIncrease = false)
        }
        if (core.contains("tắt tiếng") || core.contains("câm tiếng") || core.contains("tắt âm") || core.contains("mute") || core.contains("bật tiếng") || core.contains("mở tiếng")) {
            return VoiceCommand.ToggleMute
        }

        // II. LỆNH ĐIỀU KHIỂN HỆ THỐNG TIVI (HOME, BACK, POWER) & TIỆN ÍCH MÀN HÌNH
        if (core.contains("tắt màn hình nghe nhạc") || core.contains("tắt màn hình chỉ nghe tiếng") || core.contains("chỉ nghe tiếng") ||
            core.contains("nghe nhạc tắt màn hình") || core.contains("tắt màn hình nghe âm thanh")) {
            return VoiceCommand.ActivateScreenOffAudio
        }
        if (core.contains("về trang chủ") || core.contains("về màn hình chính") || core.contains("màn hình chính") || core.contains("về home") || core == "home" || core == "trang chủ") {
            return VoiceCommand.SystemAction(VoiceCommand.SystemActionType.HOME)
        }
        if (core.contains("quay lại") || core.contains("trở lại") || core == "lùi lại" || core == "thoát") {
            return VoiceCommand.SystemAction(VoiceCommand.SystemActionType.BACK)
        }
        if (core.contains("tắt tivi") || core.contains("tắt màn hình") || core.contains("tắt tv") || core.contains("đi ngủ")) {
            return VoiceCommand.SystemAction(VoiceCommand.SystemActionType.POWER)
        }
        if (core.contains("mở tivi") || core.contains("bật tivi") || core.contains("xem tivi") || core.contains("bật tv") || core.contains("mở tv")) {
            return VoiceCommand.Resume
        }

        // III. LỆNH ĐIỀU KHIỂN PHÁT (Playback Controls)
        when {
            core.contains("kênh tiếp") || core.contains("tiếp theo") || core.contains("chuyển tiếp") || core.contains("kênh sau") -> {
                return VoiceCommand.NextChannel
            }
            core.contains("kênh trước") || core.contains("kênh lùi") || core.contains("kênh vừa xem") -> {
                return VoiceCommand.PreviousChannel
            }
            core.contains("tạm dừng") || core == "dừng" || core == "pause" -> {
                return VoiceCommand.Pause
            }
            core.contains("phát tiếp") || core.contains("tiếp tục") || core == "play" || core == "phát" -> {
                return VoiceCommand.Resume
            }
            core.contains("đổi nguồn") || core.contains("đổi server") || core.contains("server dự phòng") || core.contains("đổi link") -> {
                return VoiceCommand.SwitchBackupSource
            }
            core.contains("tràn viền") || core.contains("toàn màn hình") || core.contains("tỷ lệ") || core.contains("co giãn") -> {
                return VoiceCommand.ToggleStretch
            }
        }

        // III.B. LỆNH ĐIỀU KHIỂN VIDEO CHUYÊN SÂU (Tua thời gian & Chọn tập phim)
        if (core.contains("tập tiếp") || core.contains("tập sau") || core.contains("qua tập mới")) {
            return VoiceCommand.NextEpisode
        }
        if (core.contains("tập trước") || core.contains("lùi tập") || core.contains("tập vừa rồi")) {
            return VoiceCommand.PreviousEpisode
        }

        val epMatch = Regex("(?:mở\\s+tập|xem\\s+tập|phát\\s+tập|chuyển\\s+tập|tập\\s+số|tập)\\s*(\\d+)").find(core)
        if (epMatch != null) {
            val epNum = epMatch.groupValues[1].toIntOrNull()
            if (epNum != null && epNum > 0) {
                return VoiceCommand.SwitchEpisode(epNum)
            }
        }

        val seekToMatch = Regex("(?:tua\\s+(?:tới|đến|qua)|nhảy\\s+(?:tới|đến))\\s*(?:(\\d+)\\s*(?:giờ|tiếng)\\s*(?:(\\d+)\\s*(?:phút|p)?)?|(?:phút\\s+(?:thứ\\s+)?|giây\\s+thứ\\s+)(\\d+))").find(core)
        if (seekToMatch != null && (core.contains("phút") || core.contains("giờ") || core.contains("tiếng"))) {
            val hours = seekToMatch.groupValues[1].toIntOrNull() ?: 0
            val minutes = seekToMatch.groupValues[2].toIntOrNull() ?: seekToMatch.groupValues[3].toIntOrNull() ?: 0
            if (hours > 0 || minutes > 0) {
                val totalMs = (hours * 3600L + minutes * 60L) * 1000L
                val label = if (hours > 0) "${hours}h${minutes}p" else "phút thứ $minutes"
                return VoiceCommand.SeekTo(totalMs, label)
            }
        }

        val seekForwardMatch = Regex("(?:tua\\s+(?:qua|nhanh|tới|thêm|đi)?|nhanh)\\s+(\\d+)\\s*(giây|s|phút|p)?").find(core)
        if (seekForwardMatch != null && (core.contains("tua") || core.contains("nhanh"))) {
            val num = seekForwardMatch.groupValues[1].toIntOrNull()
            val unit = seekForwardMatch.groupValues[2]
            if (num != null && num > 0) {
                val sec = if (unit == "phút" || unit == "p") num * 60 else num
                return VoiceCommand.SeekDelta(sec, "+${sec}s")
            }
        }

        val seekBackMatch = Regex("(?:tua\\s+lại|lùi\\s+lại|lùi|xem\\s+lại|quay\\s+lại)\\s+(\\d+)\\s*(giây|s|phút|p)?").find(core)
        if (seekBackMatch != null) {
            val num = seekBackMatch.groupValues[1].toIntOrNull()
            val unit = seekBackMatch.groupValues[2]
            if (num != null && num > 0) {
                val sec = if (unit == "phút" || unit == "p") num * 60 else num
                return VoiceCommand.SeekDelta(-sec, "-${sec}s")
            }
        }

        // III.C. LỆNH ĐIỀU KHIỂN TÍNH NĂNG XEM PHIM NÂNG CAO
        // 0. Tiện ích hẹn giờ ngủ (Sleep Timer)
        if (core.contains("hẹn giờ") || core.contains("tắt sau") || core.contains("tự tắt")) {
            val sleepMatch = Regex("(?:hẹn\\s+giờ\\s+tắt|hẹn\\s+giờ\\s+ngủ|tắt\\s+sau|hẹn\\s+giờ)\\s*(\\d+)?\\s*(phút|tiếng|giờ)?").find(core)
            val num = sleepMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            val unit = sleepMatch?.groupValues?.getOrNull(2)
            if (core.contains("tắt hẹn giờ") || core.contains("hủy hẹn giờ") || (num == 0)) {
                return VoiceCommand.SetSleepTimer(0, "Tắt hẹn giờ")
            } else if (num != null && num > 0) {
                val mins = if (unit == "tiếng" || unit == "giờ") num * 60 else num
                return VoiceCommand.SetSleepTimer(mins, "${mins} phút")
            } else if (core.contains("30 phút")) {
                return VoiceCommand.SetSleepTimer(30, "30 phút")
            } else if (core.contains("60 phút") || core.contains("1 tiếng") || core.contains("1 giờ")) {
                return VoiceCommand.SetSleepTimer(60, "60 phút")
            }
        }

        // 0.1 Chế độ ban đêm / Dịu mắt / Lọc ánh sáng xanh
        if (core.contains("chế độ ban đêm") || core.contains("dịu mắt") || core.contains("lọc ánh sáng xanh") || core.contains("chống mỏi mắt")) {
            return VoiceCommand.ToggleNightMode
        }

        // 0.2 Tắt màn hình chỉ nghe tiếng (Audio Only / Screen Off Saver)
        if (core.contains("tắt màn hình nghe nhạc") || core.contains("tắt màn hình chỉ nghe tiếng") || core.contains("chỉ nghe tiếng") ||
            core.contains("nghe nhạc tắt màn hình") || core.contains("tắt màn hình nghe âm thanh")) {
            return VoiceCommand.ActivateScreenOffAudio
        }

        // 0.3 Khóa trẻ em / Khóa cảm ứng
        if (core.contains("khóa màn hình") || core.contains("khóa trẻ em") || core.contains("khóa cảm ứng") || core.contains("chế độ trẻ em")) {
            return VoiceCommand.ToggleTouchLock
        }

        // 1. Bỏ qua mở đầu (Skip Intro)
        if (core.contains("bỏ qua mở đầu") || core.contains("skip intro") || core.contains("qua phần mở đầu") ||
            core.contains("bỏ qua giới thiệu") || core.contains("bỏ giới thiệu") || core.contains("qua mở đầu")) {
            return VoiceCommand.SkipIntro
        }

        // 2. Bật / Tắt phụ đề (Subtitles)
        if (core.contains("bật phụ đề") || core.contains("tắt phụ đề") || core == "phụ đề" || core == "subtitle") {
            return VoiceCommand.ToggleSubtitle
        }

        // 3. Lọc rõ lời thoại & tăng giọng nói (DRC Clear Voice Boost)
        if (core.contains("lọc giọng nói") || core.contains("rõ lời thoại") || core.contains("tăng giọng nói") ||
            core.contains("lọc âm đàm thoại") || core.contains("chế độ lời thoại") || core.contains("voice boost") ||
            core.contains("lọc tiếng") || core.contains("lọc thoại")) {
            return VoiceCommand.ToggleClearDialog
        }

        // 4. Chuyển kênh âm thanh / Thuyết minh / Audio Boost
        if (core.contains("đổi thuyết minh") || core.contains("đổi lồng tiếng") || core.contains("đổi ngôn ngữ") ||
            core.contains("chuyển thuyết minh") || core.contains("chuyển lồng tiếng")) {
            return VoiceCommand.SwitchAudioTrack
        }

        // 5. Điều chỉnh tốc độ phát video (Playback Speed)
        if (core.contains("tốc độ") || core.contains("nhanh hơn") || core.contains("chậm lại") || core.contains("phát nhanh") || core.contains("phát chậm")) {
            return when {
                core.contains("0.5") || core.contains("chậm một nửa") -> VoiceCommand.SetPlaybackSpeed(0.5f, "0.5x")
                core.contains("0.75") || core.contains("chậm") -> VoiceCommand.SetPlaybackSpeed(0.75f, "0.75x")
                core.contains("bình thường") || core.contains("1.0") || core.contains("1x") || core == "tốc độ chuẩn" -> VoiceCommand.SetPlaybackSpeed(1.0f, "1.0x")
                core.contains("1.25") || core.contains("nhanh một chút") || core.contains("nhanh hơn một chút") -> VoiceCommand.SetPlaybackSpeed(1.25f, "1.25x")
                core.contains("1.5") || core.contains("nhanh vừa") -> VoiceCommand.SetPlaybackSpeed(1.5f, "1.5x")
                core.contains("2.0") || core.contains("2x") || core.contains("nhanh gấp đôi") || core.contains("cực nhanh") -> VoiceCommand.SetPlaybackSpeed(2.0f, "2.0x")
                core.contains("nhanh hơn") -> VoiceCommand.SetPlaybackSpeed(1.25f, "1.25x")
                else -> VoiceCommand.SetPlaybackSpeed(1.0f, "1.0x")
            }
        }

        // IV. CÀI ĐẶT HỆ THỐNG TIVI (Wifi, Bluetooth, Cài đặt ứng dụng, Âm thanh, Màn hình...)
        if (core.contains("wifi") || core.contains("mạng internet") || core.contains("cài đặt mạng")) {
            return VoiceCommand.OpenSetting(VoiceCommand.SettingType.WIFI)
        }
        if (core.contains("bluetooth")) {
            return VoiceCommand.OpenSetting(VoiceCommand.SettingType.BLUETOOTH)
        }
        if (core.contains("cài đặt âm thanh") || core.contains("chỉnh âm thanh")) {
            return VoiceCommand.OpenSetting(VoiceCommand.SettingType.SOUND)
        }
        if (core.contains("cài đặt màn hình") || core.contains("cài đặt hiển thị")) {
            return VoiceCommand.OpenSetting(VoiceCommand.SettingType.DISPLAY)
        }
        if (core.contains("quản lý ứng dụng") || core.contains("cài đặt ứng dụng") || core.contains("danh sách ứng dụng")) {
            return VoiceCommand.OpenSetting(VoiceCommand.SettingType.APPS)
        }
        if (core.contains("cài đặt ngày giờ") || core.contains("chỉnh giờ")) {
            return VoiceCommand.OpenSetting(VoiceCommand.SettingType.DATE)
        }
        if (core.contains("cài đặt hệ thống") || core.contains("cài đặt tivi") || core == "cài đặt" || core == "settings" ||
            core.startsWith("mở cài đặt") || core.startsWith("vào cài đặt")) {
            return VoiceCommand.OpenSetting(VoiceCommand.SettingType.SYSTEM)
        }

        // V. KIỂM TRA PHIM & DUYỆT THỂ LOẠI / DANH MỤC (BrowseCategory)
        val isCategoryIntent = core.contains("kiểm tra ") || core.contains("danh mục ") || core.contains("thể loại ") ||
                               core.contains("danh sách ") || core.startsWith("xem danh mục") || core.startsWith("chọn thể loại")

        if (core.contains("phim mới") || (isCategoryIntent && core.contains("mới"))) {
            return VoiceCommand.BrowseCategory("all", "Phim Mới Cập Nhật")
        }
        if (core.contains("phim lẻ") || (isCategoryIntent && core.contains("lẻ"))) {
            return VoiceCommand.BrowseCategory("phim-le", "Phim Lẻ")
        }
        if (core.contains("phim bộ") || (isCategoryIntent && core.contains("bộ"))) {
            return VoiceCommand.BrowseCategory("phim-bo", "Phim Bộ")
        }
        if (core.contains("hoạt hình") || core.contains("anime")) {
            return VoiceCommand.BrowseCategory("hoat-hinh", "Phim Hoạt Hình")
        }
        if (core.contains("phim chiếu rạp") || core.contains("chiếu rạp") || core.contains("phim rạp")) {
            return VoiceCommand.BrowseCategory("phim-chieu-rap", "Phim Chiếu Rạp")
        }
        if (core.contains("cổ trang") || core.contains("tvb") || core.contains("phim tvb")) {
            return VoiceCommand.BrowseCategory("co-trang", "Cổ Trang & TVB")
        }
        if (core.contains("phim 4k") || core.contains("4k cinema") || core.contains("torrent")) {
            return VoiceCommand.BrowseCategory("torrent-4k", "Phim 4K Cinema")
        }
        if (isCategoryIntent && (core.contains("bóng đá") || core.contains("thể thao"))) {
            return VoiceCommand.BrowseCategory("tv-live", "Bóng Đá & TV")
        }
        if (core.contains("phim hành động") || (isCategoryIntent && core.contains("hành động"))) {
            return VoiceCommand.BrowseCategory("hanh-dong", "Hành Động")
        }
        if (core.contains("phim kinh dị") || (isCategoryIntent && core.contains("kinh dị"))) {
            return VoiceCommand.BrowseCategory("kinh-di", "Kinh Dị")
        }
        if (core.contains("phim viễn tưởng") || (isCategoryIntent && core.contains("viễn tưởng"))) {
            return VoiceCommand.BrowseCategory("vien-tuong", "Viễn Tưởng")
        }
        if (core.contains("phim hài") || (isCategoryIntent && core.contains("hài hước"))) {
            return VoiceCommand.BrowseCategory("hai-huoc", "Hài Hước")
        }
        if (core.contains("tv show") || (isCategoryIntent && core.contains("shows"))) {
            return VoiceCommand.BrowseCategory("tv-shows", "TV Shows")
        }

        // VI. LỆNH TÌM KIẾM & PHÁT NHẠC / VIDEO CHUYÊN SÂU TRÊN YOUTUBE
        val isYoutubeExplicit = core.contains("youtube")
        val isMusicOrKaraoke = core.contains("nhạc") || core.contains("karaoke") ||
                               core.contains("bài hát") || core.contains("ca khúc") ||
                               core.contains("ca sĩ") || core.contains("hài kịch") ||
                               core.contains("hài ") || core.contains("bolero") ||
                               core.contains("remix") || core.contains("acoustic") ||
                               core.contains("cover") || core.contains("live music") ||
                               core.contains("mv ") || core.contains("clip ")

        if (isYoutubeExplicit || isMusicOrKaraoke) {
            // Kiểm tra nếu người dùng chỉ muốn mở ứng dụng YouTube (không tìm kiếm video cụ thể)
            var justAppCheck = core
            for (p in listOf(
                "mở ứng dụng ", "bật ứng dụng ", "chạy ứng dụng ", "vào ứng dụng ",
                "mở app ", "bật app ", "chạy app ", "vào app ",
                "mở ", "bật ", "chạy ", "vào "
            )) {
                if (justAppCheck.startsWith(p)) {
                    justAppCheck = justAppCheck.removePrefix(p).trim()
                    break
                }
            }
            for (s in GENERAL_SUFFIXES) {
                if (justAppCheck.endsWith(s)) {
                    justAppCheck = justAppCheck.removeSuffix(s).trim()
                    break
                }
            }
            if (justAppCheck == "youtube") {
                return VoiceCommand.OpenApp("youtube")
            }

            // Người dùng tìm bài hát, ca sĩ, video:
            // 1. Tách bỏ các chỉ định YouTube
            var searchTarget = core
            for (ym in YOUTUBE_MARKERS) {
                if (searchTarget.contains(ym)) {
                    searchTarget = searchTarget.replace(ym, "").trim()
                }
            }
            searchTarget = searchTarget.replace("youtube", "").trim()

            // 2. Multi-Pass Query Sanitizer: Gọt sạch toàn bộ từ rác, tiền tố hành động & từ đệm
            val noisePrefixes = listOf(
                "em hãy ", "hãy ", "tải ", "hải ", "thử ", "làm ơn ", "vui lòng ",
                "mở bài hát ", "bật bài hát ", "nghe bài hát ", "tìm bài hát ",
                "mở ca khúc ", "bật ca khúc ", "nghe ca khúc ", "tìm ca khúc ",
                "mở ca sĩ ", "bật ca sĩ ", "nghe ca sĩ ", "tìm ca sĩ ", "ca sĩ ",
                "mở bài ", "bật bài ", "nghe bài ", "tìm bài ",
                "mở nhạc ", "bật nhạc ", "nghe nhạc ", "tìm nhạc ",
                "mở karaoke ", "bật karaoke ", "hát karaoke ", "karaoke ",
                "mở hài ", "xem hài ", "bật hài ", "hài kịch ", "hài ",
                "mở ", "bật ", "nghe ", "tìm kiếm ", "tìm ", "xem ", "chiếu ", "phát "
            )
            var cleanedMore = true
            while (cleanedMore) {
                cleanedMore = false
                for (p in noisePrefixes) {
                    if (searchTarget.startsWith(p)) {
                        searchTarget = searchTarget.removePrefix(p).trim()
                        cleanedMore = true
                        break
                    }
                }
            }

            if (searchTarget == "mở nhạc" || searchTarget == "bật nhạc" || searchTarget == "nghe nhạc") {
                searchTarget = ""
            }

            // 3. Tách bỏ các hậu tố thân mật giao tiếp tiếng Việt ("cho anh", "giúp em"...)
            for (s in GENERAL_SUFFIXES) {
                if (searchTarget.endsWith(s)) {
                    searchTarget = searchTarget.removeSuffix(s).trim()
                    break
                }
            }

            // 4. Nếu sau khi tách chỉ còn trống hoặc từ "nhạc", đề xuất mở nhạc tuyển chọn hot nhất
            val finalSearch = when {
                searchTarget.isBlank() || searchTarget == "nhạc" -> "nhạc tuyển chọn hay nhất"
                core.contains("karaoke") && !searchTarget.contains("karaoke") -> "karaoke $searchTarget"
                else -> searchTarget
            }

            return VoiceCommand.YouTubeSearch(finalSearch)
        }

        // VII. MỞ MỌI ỨNG DỤNG BẤT KỲ TRÊN TIVI (Universal App Launcher)
        val isAppCommand = core.startsWith("mở ứng dụng ") || core.startsWith("mở app ") ||
                           core.startsWith("bật ứng dụng ") || core.startsWith("bật app ") ||
                           core.startsWith("chạy ứng dụng ") || core.startsWith("chạy app ") ||
                           core.startsWith("vào ứng dụng ") || core.startsWith("vào app ") ||
                           core.startsWith("khởi động ứng dụng ") || core.startsWith("khởi động app ")

        if (isAppCommand) {
            var appName = core
            for (p in listOf(
                "mở ứng dụng ", "mở app ", "bật ứng dụng ", "bật app ",
                "chạy ứng dụng ", "chạy app ", "vào ứng dụng ", "vào app ",
                "khởi động ứng dụng ", "khởi động app "
            )) {
                if (appName.startsWith(p)) {
                    appName = appName.removePrefix(p).trim()
                    break
                }
            }
            for (s in GENERAL_SUFFIXES) {
                if (appName.endsWith(s)) {
                    appName = appName.removeSuffix(s).trim()
                    break
                }
            }
            if (appName.isNotBlank()) {
                return VoiceCommand.OpenApp(appName)
            }
        }

        // Tên các ứng dụng phổ biến trên TV khi người dùng nói trực tiếp (VD: "mở netflix", "vào vlc", "kodi")
        val knownApps = listOf(
            "netflix", "kodi", "vlc", "spotify", "vieon", "fpt play", "tv360",
            "vtvgo", "xplore", "x-plore", "monster tv", "smarttube", "tiktok", "zing mp3",
            "ch play", "google play", "cửa hàng", "facebook", "fb", "chrome", "trình duyệt"
        )
        for (app in knownApps) {
            if (core.contains(app)) {
                return VoiceCommand.OpenApp(app)
            }
        }

        // VIII. LỆNH MỞ PHIM & TÌM KIẾM PHIM TRONG DUYTV
        val isMovieExplicit = core.startsWith("mở phim ") || core.startsWith("bật phim ") ||
                              core.startsWith("xem phim ") || core.startsWith("chiếu phim ") ||
                              core.startsWith("coi phim ") || core.startsWith("phim ") ||
                              core.startsWith("tìm phim ")

        if (isMovieExplicit) {
            var movieTitle = core
            for (p in listOf(
                "mở phim ", "bật phim ", "xem phim ", "chiếu phim ", "coi phim ", "tìm phim ", "phim "
            )) {
                if (movieTitle.startsWith(p)) {
                    movieTitle = movieTitle.removePrefix(p).trim()
                    break
                }
            }
            for (s in GENERAL_SUFFIXES) {
                if (movieTitle.endsWith(s)) {
                    movieTitle = movieTitle.removeSuffix(s).trim()
                    break
                }
            }
            if (movieTitle.isNotBlank()) {
                return VoiceCommand.OpenMovie(movieTitle)
            }
        }

        if (core.startsWith("tìm kiếm ") || core.startsWith("tìm ")) {
            var searchTxt = core.removePrefix("tìm kiếm ").removePrefix("tìm ").trim()
            for (s in GENERAL_SUFFIXES) {
                if (searchTxt.endsWith(s)) {
                    searchTxt = searchTxt.removeSuffix(s).trim()
                    break
                }
            }
            if (searchTxt.isNotBlank()) {
                return VoiceCommand.SearchMovie(searchTxt)
            }
        }

        return VoiceCommand.Unknown(rawText)
    }

    /**
     * Khớp kênh truyền hình & nhà đài cực nhanh 0ms (VTV, HTV, VTC, THVL, SCTV, K+, BTV, Today...)
     */
    fun matchTvChannel(core: String, availableChannels: List<TvChannel>): VoiceCommand.SwitchChannel? {
        val channels = if (availableChannels.isNotEmpty()) availableChannels else MovieRepository.getVerifiedOfficialChannels()
        if (channels.isEmpty()) return null

        val trimmed = core.trim()
        // 0. Khẩu lệnh chung muốn xem truyền hình: "mở đài", "xem đài", "bật đài", "đài", "kênh", "truyền hình", "mở tivi", "bật tivi", "xem tivi"
        if (trimmed == "đài" || trimmed == "kênh" || trimmed == "truyền hình" ||
            trimmed == "mở đài" || trimmed == "xem đài" || trimmed == "bật đài" ||
            trimmed == "mở tivi" || trimmed == "bật tivi" || trimmed == "xem tivi" ||
            trimmed == "đài truyền hình" || trimmed == "kênh tivi") {
            val lastIdx = channels.indexOfFirst {
                it.name.equals(AppCacheManager.lastWatchedChannelName, ignoreCase = true)
            }
            val targetIdx = if (lastIdx >= 0) lastIdx else 0
            return VoiceCommand.SwitchChannel(targetIdx, channels[targetIdx])
        }

        // 1. Loại bỏ tiền tố hành động
        var cleanedTarget = core
        for (prefix in ACTION_PREFIXES) {
            if (cleanedTarget.startsWith(prefix)) {
                cleanedTarget = cleanedTarget.removePrefix(prefix).trim()
                break
            }
        }

        // 2. Loại bỏ hậu tố thân mật giao tiếp tiếng Việt ("cho anh", "giúp em", "hộ tôi"...)
        for (suffix in GENERAL_SUFFIXES) {
            if (cleanedTarget.endsWith(suffix)) {
                cleanedTarget = cleanedTarget.removeSuffix(suffix).trim()
                break
            }
        }

        if (cleanedTarget.isBlank() || cleanedTarget == "đài" || cleanedTarget == "kênh" ||
            cleanedTarget == "tivi" || cleanedTarget == "tv" || cleanedTarget == "truyền hình" ||
            cleanedTarget == "đài truyền hình" || cleanedTarget == "kênh tivi") {
            val lastIdx = channels.indexOfFirst {
                it.name.equals(AppCacheManager.lastWatchedChannelName, ignoreCase = true)
            }
            val targetIdx = if (lastIdx >= 0) lastIdx else 0
            return VoiceCommand.SwitchChannel(targetIdx, channels[targetIdx])
        }

        // A. Khớp theo số kênh trực tiếp ("kênh 5", "số 10")
        val numberMatch = Regex("^\\s*(?:kênh\\s+|số\\s+)?(\\d{1,3})\\s*$").find(cleanedTarget)
        if (numberMatch != null) {
            val channelNumber = numberMatch.groupValues[1].toIntOrNull()
            if (channelNumber != null && channelNumber in 1..channels.size) {
                val targetIdx = channelNumber - 1
                return VoiceCommand.SwitchChannel(targetIdx, channels[targetIdx])
            }
        }

        // B. Khớp nhà đài chính khi người dùng không đọc số (Flagship Defaults)
        val targetNoAccent = removeAccents(cleanedTarget)
        if (cleanedTarget == "vtv" || targetNoAccent == "vtv") {
            val idx = channels.indexOfFirst { it.name.startsWith("VTV1", ignoreCase = true) }
            if (idx >= 0) return VoiceCommand.SwitchChannel(idx, channels[idx])
        }
        if (cleanedTarget == "htv" || targetNoAccent == "htv") {
            val idx = channels.indexOfFirst { it.name.startsWith("HTV7", ignoreCase = true) }
            if (idx >= 0) return VoiceCommand.SwitchChannel(idx, channels[idx])
        }
        if (cleanedTarget == "vtc" || targetNoAccent == "vtc") {
            val idx = channels.indexOfFirst { it.name.startsWith("VTC1", ignoreCase = true) }
            if (idx >= 0) return VoiceCommand.SwitchChannel(idx, channels[idx])
        }
        if (cleanedTarget == "thvl" || targetNoAccent == "thvl" || cleanedTarget == "vinh long" || targetNoAccent == "vinh long" || cleanedTarget == "vĩnh long") {
            val idx = channels.indexOfFirst { it.name.startsWith("THVL1", ignoreCase = true) }
            if (idx >= 0) return VoiceCommand.SwitchChannel(idx, channels[idx])
        }
        if (cleanedTarget == "k+" || targetNoAccent == "k+") {
            val idx = channels.indexOfFirst { it.name.contains("K+", ignoreCase = true) }
            if (idx >= 0) return VoiceCommand.SwitchChannel(idx, channels[idx])
        }
        if (cleanedTarget.contains("đồng tháp") || targetNoAccent.contains("dong thap") || targetNoAccent.contains("thdt") || targetNoAccent.contains("dongthap")) {
            val idx = channels.indexOfFirst { it.name.contains("Đồng Tháp", ignoreCase = true) || it.name.contains("THĐT", ignoreCase = true) }
            if (idx >= 0) return VoiceCommand.SwitchChannel(idx, channels[idx])
        }

        // C. Khớp theo mã định danh kênh chuẩn (MovieRepository.normalizeChannelKey)
        val normalizedTargetKey = MovieRepository.normalizeChannelKey(cleanedTarget)
        if (normalizedTargetKey.isNotBlank()) {
            val directKeyMatchIdx = channels.indexOfFirst { ch ->
                val chKey = MovieRepository.normalizeChannelKey(ch.name)
                chKey == normalizedTargetKey
            }
            if (directKeyMatchIdx >= 0) {
                return VoiceCommand.SwitchChannel(directKeyMatchIdx, channels[directKeyMatchIdx])
            }
        }

        // D. Khớp mờ theo khoảng cách ngữ âm Levenshtein & Substring
        val compactTarget = cleanedTarget.replace(Regex("[^a-z0-9]"), "")
        if (compactTarget.isNotBlank()) {
            val fuzzyIdx = fuzzyMatchChannel(compactTarget, channels)
            if (fuzzyIdx >= 0) {
                return VoiceCommand.SwitchChannel(fuzzyIdx, channels[fuzzyIdx])
            }

            // Thử khớp theo tên gốc không dấu
            val targetNoAccent = removeAccents(cleanedTarget)
            if (targetNoAccent.length >= 2) {
                val nameMatchIdx = channels.indexOfFirst { ch ->
                    val chNoAccent = removeAccents(ch.name)
                    chNoAccent.contains(targetNoAccent, ignoreCase = true) || targetNoAccent.contains(chNoAccent, ignoreCase = true)
                }
                if (nameMatchIdx >= 0) {
                    return VoiceCommand.SwitchChannel(nameMatchIdx, channels[nameMatchIdx])
                }
            }
        }

        // E. Khớp từ khóa thể thao / bóng đá
        if (core.contains("bóng đá") || core.contains("thể thao") || core.contains("ngoại hạng")) {
            val sportsIdx = channels.indexOfFirst { ch ->
                val group = ch.groupTitle?.uppercase() ?: ""
                val name = ch.name.uppercase()
                group.contains("THỂ THAO") || group.contains("SPORTS") || name.contains("SPORTS") || name.contains("ON FOOTBALL") || name.contains("ON SPORTS")
            }
            if (sportsIdx >= 0) {
                return VoiceCommand.SwitchChannel(sportsIdx, channels[sportsIdx])
            }
        }

        return null
    }

    fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    fun fuzzyMatchChannel(query: String, channels: List<TvChannel>): Int {
        val cleanQuery = removeAccents(query).replace(Regex("[^a-z0-9]"), "")
        if (cleanQuery.length < 3) return -1
        
        var bestIdx = -1
        var bestDistance = Int.MAX_VALUE
        
        for (i in channels.indices) {
            val cleanCh = MovieRepository.normalizeChannelKey(channels[i].name).replace(Regex("[^a-z0-9]"), "")
            if (cleanCh.isBlank()) continue
            if (cleanCh == cleanQuery) return i
            if (cleanQuery.length >= 3 && cleanCh.contains(cleanQuery)) return i
            if (cleanCh.length >= 4 && cleanQuery.contains(cleanCh)) return i
            
            val dist = levenshteinDistance(cleanQuery, cleanCh)
            val maxAllowedDist = when {
                cleanQuery.length <= 4 -> 1
                cleanQuery.length <= 7 -> 2
                else -> 3
            }
            if (dist <= maxAllowedDist && dist < bestDistance) {
                bestDistance = dist
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun removeAccents(text: String): String {
        val nfd = Normalizer.normalize(text, Normalizer.Form.NFD)
        return Pattern.compile("\\p{InCombiningDiacriticalMarks}+").matcher(nfd)
            .replaceAll("")
            .replace('đ', 'd').replace('Đ', 'D')
            .lowercase()
    }
}
