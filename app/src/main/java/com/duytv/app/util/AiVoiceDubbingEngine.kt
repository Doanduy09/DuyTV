package com.duytv.app.util

import android.content.Context
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class AiDubMode(val label: String, val badge: String) {
    MULTI_CHARACTER("🎭 ĐA NHÂN VẬT CẢM XÚC", "🎭 AI Lồng Tiếng: Đa Nhân Vật Cảm Xúc"),
    MALE_CINEMA("👨 NAM TRẦM ĐIỆN ẢNH", "👨 AI Lồng Tiếng: Nam Chính Trầm Ấm"),
    FEMALE_EXPRESSIVE("👩 NỮ TRUYỀN CẢM", "👩 AI Lồng Tiếng: Nữ Chính Truyền Cảm"),
    OFF("TẮT", "Đã tắt AI Lồng Tiếng")
}

enum class CharacterRole(val displayTitle: String, val basePitch: Float, val baseSpeed: Float) {
    MALE_HERO("👨 Nam Chính", 0.83f, 1.08f),
    FEMALE_LEAD("👩 Nữ Chính", 1.07f, 1.06f),
    CHILD_YOUTH("🧒 Thiếu Niên", 1.20f, 1.10f),
    ELDER_DEEP("👴 Trưởng Bối", 0.75f, 0.99f)
}

enum class EmotionTone(val displayTitle: String, val pitchDelta: Float, val speedDelta: Float) {
    CALM_NATURAL("Tự nhiên", 0.0f, 0.0f),
    ACTION_INTENSE("🔥 Kịch tính", 0.04f, 0.10f),
    SAD_EMOTIONAL("💧 Xúc động", -0.04f, -0.07f),
    SURPRISE_QUESTION("❓ Ngạc nhiên", 0.06f, 0.04f)
}

data class DubSubtitleCue(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val role: CharacterRole,
    val emotion: EmotionTone,
    val finalPitch: Float,
    val finalSpeed: Float
)

@OptIn(UnstableApi::class)
class AiVoiceDubbingEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val moviePlayer: ExoPlayer,
    private val onStatusUpdate: (String?) -> Unit
) {
    var currentMode: AiDubMode = AiDubMode.OFF
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var voicePlayer: ExoPlayer? = null
    private var fallbackTts: TextToSpeech? = null
    private var isFallbackTtsReady = false

    private val parsedCues = mutableListOf<DubSubtitleCue>()
    private val cachedAudioFiles = ConcurrentHashMap<Int, File>()
    private val prefetchingIndices = ConcurrentHashMap.newKeySet<Int>()

    private var lastSpokenCueIndex: Int = -1
    private var lastSpokenRealtimeText: String = ""
    private var duckRestoreJob: Job? = null
    private var prefetchJob: Job? = null

    private val movieDuckVolume = 0.22f
    private val movieNormalVolume = 1.0f

    init {
        try {
            voicePlayer = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED) {
                            restoreMovieAudioSmoothly()
                        }
                    }
                })
            }
        } catch (_: Exception) {}

        try {
            fallbackTts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val res = fallbackTts?.setLanguage(Locale("vi", "VN"))
                    isFallbackTtsReady = (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED)
                }
            }
        } catch (_: Exception) {}
    }

    fun setDubMode(mode: AiDubMode, movieTitle: String = "") {
        currentMode = mode
        if (mode == AiDubMode.OFF) {
            stopSpeaking()
            onStatusUpdate(null)
        } else {
            val subCountInfo = if (parsedCues.isNotEmpty()) " (${parsedCues.size} câu thoại)" else " (Đang đồng bộ kịch bản...)"
            onStatusUpdate("${mode.badge}$subCountInfo")
            val introText = when (mode) {
                AiDubMode.MULTI_CHARACTER -> "Đã bật AI lồng tiếng đa nhân vật cảm xúc"
                AiDubMode.MALE_CINEMA -> "Đã bật giọng nam trầm điện ảnh"
                AiDubMode.FEMALE_EXPRESSIVE -> "Đã bật giọng nữ chính truyền cảm"
                AiDubMode.OFF -> ""
            }
            if (introText.isNotEmpty()) {
                val introRole = if (mode == AiDubMode.FEMALE_EXPRESSIVE) CharacterRole.FEMALE_LEAD else CharacterRole.MALE_HERO
                speakCueWithEmotion(
                    DubSubtitleCue(
                        index = -999,
                        startMs = 0L,
                        endMs = 2600L,
                        text = introText,
                        role = introRole,
                        emotion = EmotionTone.CALM_NATURAL,
                        finalPitch = introRole.basePitch,
                        finalSpeed = 1.08f
                    )
                )
            }
            // Kích hoạt tải trước ngay lập tức các câu thoại sắp tới
            triggerLookaheadPrefetch(moviePlayer.currentPosition)
        }
    }

    fun cycleDubMode(movieTitle: String = ""): AiDubMode {
        val next = when (currentMode) {
            AiDubMode.OFF -> AiDubMode.MULTI_CHARACTER
            AiDubMode.MULTI_CHARACTER -> AiDubMode.MALE_CINEMA
            AiDubMode.MALE_CINEMA -> AiDubMode.FEMALE_EXPRESSIVE
            AiDubMode.FEMALE_EXPRESSIVE -> AiDubMode.OFF
        }
        setDubMode(next, movieTitle)
        return next
    }

    fun hasLoadedSubtitles(): Boolean = parsedCues.isNotEmpty()

    /**
     * Nạp và phân tích ngữ cảnh nhân vật + cảm xúc cho toàn bộ kịch bản phụ đề (.SRT hoặc Data URI)
     */
    fun loadSubtitleScript(subtitleUriOrData: String?) {
        if (subtitleUriOrData.isNullOrBlank()) return
        scope.launch(Dispatchers.IO) {
            try {
                val rawSrt = when {
                    subtitleUriOrData.startsWith("data:application/x-subrip;base64,") -> {
                        val b64 = subtitleUriOrData.substringAfter("base64,")
                        String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                    }
                    subtitleUriOrData.startsWith("http") -> {
                        val req = Request.Builder()
                            .url(subtitleUriOrData)
                            .header("User-Agent", "Mozilla/5.0 DuyTV/1.0")
                            .build()
                        val res = httpClient.newCall(req).execute()
                        if (res.isSuccessful) res.body?.string() ?: "" else ""
                    }
                    else -> ""
                }
                if (rawSrt.isBlank()) return@launch
                val cues = parseSrtWithCharacterEmotion(rawSrt)
                withContext(Dispatchers.Main) {
                    parsedCues.clear()
                    parsedCues.addAll(cues)
                    lastSpokenCueIndex = -1
                    if (currentMode != AiDubMode.OFF && parsedCues.isNotEmpty()) {
                        onStatusUpdate("${currentMode.badge} • Đã đồng bộ ${parsedCues.size} câu thoại")
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun parseTimestampMs(ts: String): Long {
        val clean = ts.trim().replace('.', ',')
        val parts = clean.split(',')
        val ms = (parts.getOrNull(1) ?: "0").padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        val hms = parts[0].split(':')
        return when (hms.size) {
            3 -> {
                val h = hms[0].toLongOrNull() ?: 0L
                val m = hms[1].toLongOrNull() ?: 0L
                val s = hms[2].toLongOrNull() ?: 0L
                ((h * 3600L) + (m * 60L) + s) * 1000L + ms
            }
            2 -> {
                val m = hms[0].toLongOrNull() ?: 0L
                val s = hms[1].toLongOrNull() ?: 0L
                ((m * 60L) + s) * 1000L + ms
            }
            else -> 0L
        }
    }

    private fun cleanDialogueText(rawLines: List<String>): String {
        return rawLines.joinToString(" ") { line ->
            line.replace(Regex("<[^>]*>"), "")
                .replace(Regex("\\{[^}]*\\}"), "")
                .replace(Regex("^[-–—•]+\\s*"), "")
                .trim()
        }
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun detectCharacterAndEmotion(
        text: String,
        cueIndex: Int,
        prevRole: CharacterRole,
        durationMs: Long
    ): Triple<CharacterRole, EmotionTone, Pair<Float, Float>> {
        val lower = " ${text.lowercase()} "

        // 1. Phân tích nhân vật theo đại từ nhân xưng & ngữ cảnh đối thoại tiếng Việt
        val role = when {
            lower.contains(" bổn tọa ") || lower.contains(" lão phu ") || lower.contains(" trẫm ") ||
                lower.contains(" sư phụ ") || lower.contains(" ông nội ") -> CharacterRole.ELDER_DEEP

            lower.contains(" cháu ") || lower.contains(" bố ơi ") || lower.contains(" mẹ ơi ") ||
                lower.contains(" ba ơi ") || lower.contains(" má ơi ") -> CharacterRole.CHILD_YOUTH

            lower.contains(" em yêu anh") || lower.contains(" thiếp ") || lower.contains(" muội ") ||
                lower.contains(" chị sẽ ") || lower.contains(" em không ") || lower.contains(" em sợ ") ||
                lower.contains(" anh ơi ") || lower.contains(" chàng ") -> CharacterRole.FEMALE_LEAD

            lower.contains(" anh sẽ ") || lower.contains(" anh không ") || lower.contains(" huynh ") ||
                lower.contains(" tại hạ ") || lower.contains(" tao ") || lower.contains(" anh yêu em") ||
                lower.contains(" cô ấy ") || lower.contains(" em đừng ") -> CharacterRole.MALE_HERO

            else -> {
                // Luân phiên tự nhiên theo lượt thoại (Turn-taking) nếu câu ngắn đối đáp
                if (text.startsWith("-") || cueIndex % 3 == 1) {
                    if (prevRole == CharacterRole.MALE_HERO) CharacterRole.FEMALE_LEAD else CharacterRole.MALE_HERO
                } else {
                    CharacterRole.MALE_HERO
                }
            }
        }

        // 2. Phân tích cảm xúc câu thoại (Hành động / Bi thương / Ngạc nhiên / Tự nhiên)
        val emotion = when {
            text.contains("!") || lower.contains(" chạy ") || lower.contains(" cẩn thận ") ||
                lower.contains(" dừng lại ") || lower.contains(" bắn ") || lower.contains(" nhanh lên ") ||
                lower.contains(" cứu ") || lower.contains(" giết ") || lower.contains(" không thể ") -> EmotionTone.ACTION_INTENSE

            text.contains("...") || lower.contains(" xin lỗi ") || lower.contains(" tạm biệt ") ||
                lower.contains(" đừng đi ") || lower.contains(" đau ") || lower.contains(" nhớ ") ||
                lower.contains(" khóc ") || lower.contains(" tha thứ ") -> EmotionTone.SAD_EMOTIONAL

            text.contains("?") || lower.contains(" tại sao ") || lower.contains(" cái gì ") ||
                lower.contains(" thật sao ") || lower.contains(" sao cơ ") || lower.contains(" ai đó ") -> EmotionTone.SURPRISE_QUESTION

            else -> EmotionTone.CALM_NATURAL
        }

        val wordCount = text.split(" ").size.coerceAtLeast(1)
        val durationSec = (durationMs / 1000f).coerceAtLeast(1.2f)
        val requiredWordsPerSec = wordCount / durationSec

        // Tự động căn chỉnh tốc độ đọc vừa khít khẩu hình nhân vật để không bị chồng câu sau
        val adaptiveSpeedBoost = when {
            requiredWordsPerSec > 4.2f -> 0.20f
            requiredWordsPerSec > 3.5f -> 0.12f
            requiredWordsPerSec > 2.8f -> 0.05f
            else -> 0.0f
        }

        val finalPitch = (role.basePitch + emotion.pitchDelta).coerceIn(0.70f, 1.30f)
        val finalSpeed = (role.baseSpeed + emotion.speedDelta + adaptiveSpeedBoost).coerceIn(0.92f, 1.35f)

        return Triple(role, emotion, finalPitch to finalSpeed)
    }

    private fun parseSrtWithCharacterEmotion(rawSrt: String): List<DubSubtitleCue> {
        val timingRegex = Regex("""(\d{1,2}:\d{2}(?::\d{2})?[.,]\d{1,3})\s*-->\s*(\d{1,2}:\d{2}(?::\d{2})?[.,]\d{1,3})""")
        val lines = rawSrt.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val result = mutableListOf<DubSubtitleCue>()
        var cueIdx = 0
        var prevRole = CharacterRole.MALE_HERO
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            val match = timingRegex.find(line)
            if (match != null) {
                val startMs = parseTimestampMs(match.groupValues[1])
                val endMs = parseTimestampMs(match.groupValues[2]).coerceAtLeast(startMs + 1200L)
                i++
                val textLines = mutableListOf<String>()
                while (i < lines.size) {
                    val tLine = lines[i].trim()
                    if (tLine.isEmpty()) break
                    if (timingRegex.containsMatchIn(tLine)) break
                    if (tLine.all { it.isDigit() } && i + 1 < lines.size && timingRegex.containsMatchIn(lines[i + 1].trim())) {
                        break
                    }
                    textLines.add(tLine)
                    i++
                }
                val cleanText = cleanDialogueText(textLines)
                if (cleanText.length >= 2) {
                    val (role, emotion, pitchSpeed) = detectCharacterAndEmotion(
                        text = cleanText,
                        cueIndex = cueIdx,
                        prevRole = prevRole,
                        durationMs = endMs - startMs
                    )
                    prevRole = role
                    result.add(
                        DubSubtitleCue(
                            index = cueIdx++,
                            startMs = startMs,
                            endMs = endMs,
                            text = cleanText,
                            role = role,
                            emotion = emotion,
                            finalPitch = pitchSpeed.first,
                            finalSpeed = pitchSpeed.second
                        )
                    )
                }
            } else {
                i++
            }
        }
        return result
    }

    /**
     * Tải trước (Lookahead Pre-buffer 10 giây) âm thanh AI Neural TTS vào bộ nhớ đệm
     * để khi tới mốc thời gian nhân vật mở miệng là phát tức thì (độ trễ 0ms).
     */
    private fun triggerLookaheadPrefetch(currentPosMs: Long) {
        if (currentMode == AiDubMode.OFF || parsedCues.isEmpty()) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            val windowEnd = currentPosMs + 12_000L
            val upcoming = parsedCues.filter { it.startMs in (currentPosMs - 500L)..windowEnd }.take(5)
            for (cue in upcoming) {
                if (cachedAudioFiles.containsKey(cue.index) || !prefetchingIndices.add(cue.index)) continue
                try {
                    val file = downloadNeuralTtsAudio(cue.index, cue.text)
                    if (file != null && file.exists() && file.length() > 256) {
                        cachedAudioFiles[cue.index] = file
                    }
                } catch (_: Exception) {
                } finally {
                    prefetchingIndices.remove(cue.index)
                }
            }
        }
    }

    private fun downloadNeuralTtsAudio(cueIndex: Int, text: String): File? {
        val clippedText = text.take(195)
        val encoded = URLEncoder.encode(clippedText, "UTF-8")
        val url = "https://translate.googleapis.com/translate_tts?ie=UTF-8&tl=vi&client=tw-ob&q=$encoded"
        val outFile = File(context.cacheDir, "duytv_dub_${ clippedText.hashCode() }_$cueIndex.mp3")
        if (outFile.exists() && outFile.length() > 256) return outFile

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        val res = httpClient.newCall(req).execute()
        if (res.isSuccessful) {
            val bytes = res.body?.bytes()
            if (bytes != null && bytes.size > 256) {
                outFile.writeBytes(bytes)
                return outFile
            }
        }
        return null
    }

    /**
     * Được gọi định kỳ mỗi 250ms từ PlayerScreen để đồng bộ khẩu hình & giọng đọc AI
     */
    fun onPlaybackPositionTick(currentPosMs: Long, isMoviePlaying: Boolean) {
        if (currentMode == AiDubMode.OFF || !isMoviePlaying) return
        if (parsedCues.isEmpty()) return

        triggerLookaheadPrefetch(currentPosMs)

        // Tìm câu thoại khớp với thời điểm hiện tại (cho phép lệch sớm 180ms để khớp khẩu hình)
        val activeCue = parsedCues.firstOrNull { cue ->
            currentPosMs >= (cue.startMs - 180L) && currentPosMs <= (cue.endMs - 200L)
        } ?: return

        if (activeCue.index == lastSpokenCueIndex) return
        lastSpokenCueIndex = activeCue.index

        speakCueWithEmotion(activeCue)
    }

    /**
     * Hỗ trợ đọc trực tiếp cả phụ đề nhúng trong luồng HLS/WebVTT nếu ExoPlayer phát ra sự kiện onCues
     */
    fun onRealtimeCueText(cueText: String) {
        if (currentMode == AiDubMode.OFF) return
        // Nếu đã có kịch bản SRT đầy đủ trong parsedCues thì ưu tiên bộ đồng bộ Lookahead 0ms
        if (parsedCues.isNotEmpty()) return

        val cleaned = cleanDialogueText(cueText.lines())
        if (cleaned.length < 2 || cleaned == lastSpokenRealtimeText) return
        lastSpokenRealtimeText = cleaned

        val (role, emotion, pitchSpeed) = detectCharacterAndEmotion(
            text = cleaned,
            cueIndex = lastSpokenCueIndex + 1,
            prevRole = CharacterRole.MALE_HERO,
            durationMs = 3200L
        )
        val syntheticCue = DubSubtitleCue(
            index = ++lastSpokenCueIndex,
            startMs = moviePlayer.currentPosition,
            endMs = moviePlayer.currentPosition + 3200L,
            text = cleaned,
            role = role,
            emotion = emotion,
            finalPitch = pitchSpeed.first,
            finalSpeed = pitchSpeed.second
        )
        speakCueWithEmotion(syntheticCue)
    }

    private fun speakCueWithEmotion(cue: DubSubtitleCue) {
        val (effectivePitch, effectiveSpeed, roleTitle) = when (currentMode) {
            AiDubMode.MULTI_CHARACTER -> Triple(
                cue.finalPitch,
                cue.finalSpeed,
                "${cue.role.displayTitle} (${cue.emotion.displayTitle})"
            )
            AiDubMode.MALE_CINEMA -> Triple(
                (CharacterRole.MALE_HERO.basePitch + cue.emotion.pitchDelta).coerceIn(0.75f, 0.92f),
                cue.finalSpeed,
                "👨 Nam Trầm (${cue.emotion.displayTitle})"
            )
            AiDubMode.FEMALE_EXPRESSIVE -> Triple(
                (CharacterRole.FEMALE_LEAD.basePitch + cue.emotion.pitchDelta).coerceIn(1.02f, 1.18f),
                cue.finalSpeed,
                "👩 Nữ Chính (${cue.emotion.displayTitle})"
            )
            AiDubMode.OFF -> return
        }

        onStatusUpdate("🎙️ $roleTitle: \"${cue.text.take(48)}${if (cue.text.length > 48) "..." else ""}\"")

        // Hạ mượt âm lượng nhạc nền phim xuống 22% (Audio Ducking chuẩn rạp chiếu phim)
        duckMovieAudioSmoothly()

        scope.launch(Dispatchers.IO) {
            val cachedFile = cachedAudioFiles[cue.index] ?: try {
                downloadNeuralTtsAudio(cue.index, cue.text)?.also { cachedAudioFiles[cue.index] = it }
            } catch (_: Exception) { null }

            withContext(Dispatchers.Main) {
                if (currentMode == AiDubMode.OFF) return@withContext
                val player = voicePlayer
                if (cachedFile != null && cachedFile.exists() && player != null) {
                    try {
                        player.stop()
                        player.clearMediaItems()
                        player.playbackParameters = PlaybackParameters(effectiveSpeed, effectivePitch)
                        player.volume = 1.0f
                        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(cachedFile)))
                        player.prepare()
                        player.play()

                        // Hẹn giờ khôi phục âm lượng phim nếu câu thoại kết thúc
                        duckRestoreJob?.cancel()
                        val estDurationMs = ((cue.endMs - cue.startMs).coerceIn(1400L, 6500L))
                        duckRestoreJob = scope.launch {
                            delay(estDurationMs)
                            if (voicePlayer?.isPlaying != true) {
                                restoreMovieAudioSmoothly()
                            }
                        }
                        return@withContext
                    } catch (_: Exception) {}
                }

                // Fallback sang Android Native TTS nếu mất kết nối tới máy chủ Neural TTS
                if (isFallbackTtsReady) {
                    try {
                        fallbackTts?.setPitch(effectivePitch)
                        fallbackTts?.setSpeechRate(effectiveSpeed)
                        fallbackTts?.speak(cue.text, TextToSpeech.QUEUE_FLUSH, null, "dub_${cue.index}")
                        duckRestoreJob?.cancel()
                        duckRestoreJob = scope.launch {
                            delay((cue.endMs - cue.startMs).coerceIn(1500L, 6000L))
                            restoreMovieAudioSmoothly()
                        }
                    } catch (_: Exception) {
                        restoreMovieAudioSmoothly()
                    }
                } else {
                    restoreMovieAudioSmoothly()
                }
            }
        }
    }

    private fun duckMovieAudioSmoothly() {
        try {
            if (moviePlayer.volume > movieDuckVolume) {
                moviePlayer.volume = movieDuckVolume
            }
        } catch (_: Exception) {}
    }

    private fun restoreMovieAudioSmoothly() {
        try {
            moviePlayer.volume = movieNormalVolume
        } catch (_: Exception) {}
    }

    fun stopSpeaking() {
        duckRestoreJob?.cancel()
        prefetchJob?.cancel()
        try {
            voicePlayer?.stop()
        } catch (_: Exception) {}
        try {
            fallbackTts?.stop()
        } catch (_: Exception) {}
        restoreMovieAudioSmoothly()
    }

    fun release() {
        stopSpeaking()
        try {
            voicePlayer?.release()
            voicePlayer = null
        } catch (_: Exception) {}
        try {
            fallbackTts?.shutdown()
            fallbackTts = null
        } catch (_: Exception) {}
    }
}
