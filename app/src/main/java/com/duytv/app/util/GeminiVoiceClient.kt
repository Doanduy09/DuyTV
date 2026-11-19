package com.duytv.app.util

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class VoiceContext(
    val movieTitle: String? = null,
    val episodeName: String? = null,
    val positionSeconds: Long = 0L,
    val channelName: String? = null,
    val activePackage: String? = null,
    val screenLabels: List<String> = emptyList(),
    val volumePercent: Int = -1,
    val liveSportsSummary: String? = null
)

data class GeminiVoiceAction(
    val action: String,
    val query: String? = null,
    val ttsReply: String? = null,
    val target: String? = null,
    val actions: List<GeminiVoiceAction> = emptyList()
)

/**
 * Trợ Lý Trí Tuệ Nhân Tạo DuyTV (Gemini Pro Brain):
 * - Kết nối LAN AI Pool (192.168.1.189:8100) hoặc Google Gemini Cloud Direct.
 * - Hỗ trợ Ngữ cảnh màn hình TV (Screen-Aware), Trí nhớ hội thoại 5 lượt và Chuỗi đa hành động (Multi-step Actions).
 */
object GeminiVoiceClient {
    private const val TAG = "GeminiVoiceClient"
    private const val PREFS_NAME = "duytv_ai_config"
    private const val KEY_LAN_HOST_URL = "lan_host_url"
    private const val KEY_GEMINI_API_KEY = "gemini_direct_api_key"
    private const val MEMORY_TTL_MS = 180_000L
    private const val MAX_HISTORY_TURNS = 5
    const val DEFAULT_LAN_HOST_URL = "http://192.168.1.189:8100/v1/chat/completions"

    @Volatile
    var hermesHostUrl: String = DEFAULT_LAN_HOST_URL

    @Volatile
    var geminiDirectApiKey: String = ""

    private val conversationHistory = ArrayDeque<Pair<String, String>>()
    @Volatile
    private var lastTurnTimestampMs: Long = 0L

    fun init(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            hermesHostUrl = prefs.getString(KEY_LAN_HOST_URL, DEFAULT_LAN_HOST_URL)?.takeIf { it.isNotBlank() }
                ?: DEFAULT_LAN_HOST_URL
            geminiDirectApiKey = prefs.getString(KEY_GEMINI_API_KEY, "")?.trim() ?: ""
        } catch (_: Exception) {}
    }

    fun clearHistory() {
        synchronized(conversationHistory) {
            conversationHistory.clear()
            lastTurnTimestampMs = 0L
        }
    }

    private fun getActiveHistorySnapshot(): List<Pair<String, String>> {
        synchronized(conversationHistory) {
            val now = System.currentTimeMillis()
            if (now - lastTurnTimestampMs > MEMORY_TTL_MS) {
                conversationHistory.clear()
                return emptyList()
            }
            return conversationHistory.toList()
        }
    }

    fun recordTurn(userText: String, assistantSummary: String) {
        if (userText.isBlank() || assistantSummary.isBlank()) return
        appendHistoryTurn(userText.trim(), assistantSummary.trim())
    }

    private fun appendHistoryTurn(userText: String, assistantSummary: String) {
        synchronized(conversationHistory) {
            lastTurnTimestampMs = System.currentTimeMillis()
            if (conversationHistory.size >= MAX_HISTORY_TURNS) {
                conversationHistory.removeFirst()
            }
            conversationHistory.addLast(userText to assistantSummary)
        }
    }

    fun normalizeLanHostUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return DEFAULT_LAN_HOST_URL
        var url = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "http://$trimmed"
        } else {
            trimmed
        }
        if (!url.contains("/v1/chat/completions")) {
            url = url.trimEnd('/') + "/v1/chat/completions"
        }
        return url
    }

    fun saveConfig(context: Context, lanUrl: String, apiKey: String) {
        val cleanUrl = if (lanUrl.isBlank()) DEFAULT_LAN_HOST_URL else normalizeLanHostUrl(lanUrl)
        val cleanKey = apiKey.trim()
        hermesHostUrl = cleanUrl
        geminiDirectApiKey = cleanKey
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAN_HOST_URL, cleanUrl)
            .putString(KEY_GEMINI_API_KEY, cleanKey)
            .apply()
    }

    fun resetConfig(context: Context) {
        hermesHostUrl = DEFAULT_LAN_HOST_URL
        geminiDirectApiKey = ""
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAN_HOST_URL)
            .remove(KEY_GEMINI_API_KEY)
            .apply()
    }

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(8000, TimeUnit.MILLISECONDS)
        .build()

    private fun buildSystemPrompt(context: VoiceContext?): String {
        val contextInfo = buildString {
            if (!context?.movieTitle.isNullOrBlank()) {
                append("\n- TV ĐANG PHÁT PHIM: '${context?.movieTitle}' (${context?.episodeName ?: "Trọn bộ"}), ở giây thứ ${context?.positionSeconds}s.")
            }
            if (!context?.channelName.isNullOrBlank()) {
                append("\n- TV ĐANG XEM KÊNH: '${context?.channelName}'.")
            }
            if (!context?.activePackage.isNullOrBlank()) {
                append("\n- ỨNG DỤNG ĐANG MỞ: '${context?.activePackage}'.")
            }
            if ((context?.volumePercent ?: -1) >= 0) {
                append("\n- ÂM LƯỢNG HIỆN TẠI: ${context?.volumePercent}%.")
            }
            if (!context?.screenLabels.isNullOrEmpty()) {
                append("\n- CÁC NÚT/CHỮ TRÊN MÀN HÌNH: ${context?.screenLabels?.joinToString(" | ")}")
            }
            if (!context?.liveSportsSummary.isNullOrBlank()) {
                append("\n- LỊCH BÓNG ĐÁ HÔM NAY:\n${context?.liveSportsSummary}")
            }
        }

        return """
            BẠN LÀ BỘ ĐIỀU KHIỂN HỆ ĐIỀU HÀNH ANDROID TV TÍCH HỢP TRỰC TIẾP TRÊN TIVI CỦA ANH DUY (DUYTV).
            BẠN CÓ TOÀN QUYỀN ĐIỀU KHIỂN HỆ THỐNG QUA ACCESSIBILITY SERVICE VÀ MEDIA CONTROLLER.
            TUYỆT ĐỐI KHÔNG TỪ CHỐI, KHÔNG NÓI 'EM LÀ AI KHÔNG TỰ BẤM ĐƯỢC REMOTE'.
            BẮT BUỘC TRẢ VỀ DUY NHẤT 1 ĐỐI TƯỢNG JSON HỢP LỆ THEO SCHEMA (KHÔNG VIẾT CHỮ NÀO BÊN NGOÀI JSON).

            Ngữ cảnh hiện tại của Tivi:$contextInfo

            Schema JSON:
            {"action":"...","query":"...","target":"...","tts_reply":"1 câu thân mật xưng em với anh Duy","actions":[{"action":"...","query":"...","target":"..."}]}

            Danh sách Actions:
            - play_tv(query=tên kênh): VTV1, VTV3, HTV7, THVL1, K+ SPORT, ON Sports... (VD: "thời sự" -> query="VTV1")
            - play_movie(query=tên phim): Phát phim trên DuyTV
            - search_youtube(query=từ khóa): Chỉ khi yêu cầu YouTube, bài hát, karaoke
            - adjust_volume(target=up|down|mute): "hơi ồn", "cho nhỏ lại" -> target="down"; "to lên" -> target="up"
            - set_volume_percent(target=0..100): Âm lượng cụ thể
            - playback_control(query=lệnh): tua tới 30 giây, tua lại 15 giây, tập tiếp, tập trước, tạm dừng, tiếp tục
            - system_action(target=home|back|power|recents|screenshot): back (khi nói "đổi phim khác", "phim dở quá", "thoát"), power (khi nói "đi ngủ", "tắt tivi")
            - open_app(target=tên app), open_setting(target=wifi|bluetooth|sound|display|apps|accessibility)
            - sleep_timer(target=số phút)
            - chat: Trả lời trò chuyện tự nhiên với anh Duy khi không phải lệnh tivi

            Ví dụ chuẩn (Few-Shot):
            - "hơi ồn" -> {"action":"adjust_volume","target":"down","tts_reply":"Dạ em giảm âm lượng xuống cho anh Duy ngay ạ"}
            - "bật thời sự" -> {"action":"play_tv","query":"VTV1","tts_reply":"Dạ em mở bản tin thời sự VTV1 cho anh Duy đây ạ"}
            - "phim này dở quá đổi phim khác" -> {"action":"system_action","target":"back","tts_reply":"Dạ em thoát ra để anh Duy chọn phim khác ạ"}
            - "tắt tivi đi ngủ" -> {"action":"system_action","target":"power","tts_reply":"Dạ chúc anh Duy ngủ ngon ạ, em tắt tivi ngay đây"}
            - "bớt ồn rồi mở vtv3" -> {"actions":[{"action":"adjust_volume","target":"down"},{"action":"play_tv","query":"VTV3"}],"tts_reply":"Dạ em giảm nhỏ lại và chuyển VTV3 cho anh Duy liền ạ"}
            - "tập này coi rồi" -> {"action":"playback_control","query":"tập tiếp","tts_reply":"Dạ em mở tập tiếp theo cho anh Duy nhé"}
        """.trimIndent()
    }

    internal fun parseResponseJson(rawContent: String): GeminiVoiceAction? {
        return try {
            val jsonStr = extractJsonString(rawContent)
            val parsedObj = gson.fromJson(jsonStr, JsonObject::class.java)
            if (parsedObj != null) {
                val action = parsedObj.get("action")?.takeIf { !it.isJsonNull }?.asString ?: "chat"
                val query = parsedObj.get("query")?.takeIf { !it.isJsonNull }?.asString
                val target = parsedObj.get("target")?.takeIf { !it.isJsonNull }?.asString
                val ttsReply = parsedObj.get("tts_reply")?.takeIf { !it.isJsonNull }?.asString
                val subActions = mutableListOf<GeminiVoiceAction>()
                val arr = parsedObj.getAsJsonArray("actions")
                if (arr != null) {
                    for (el in arr) {
                        if (!el.isJsonObject) continue
                        val obj = el.asJsonObject
                        val subAct = obj.get("action")?.takeIf { !it.isJsonNull }?.asString ?: continue
                        val subQuery = obj.get("query")?.takeIf { !it.isJsonNull }?.asString
                        val subTarget = obj.get("target")?.takeIf { !it.isJsonNull }?.asString
                        subActions.add(GeminiVoiceAction(action = subAct, query = subQuery, target = subTarget))
                    }
                }
                return GeminiVoiceAction(
                    action = action,
                    query = query,
                    ttsReply = ttsReply,
                    target = target,
                    actions = subActions
                )
            }
            GeminiVoiceAction(action = "chat", ttsReply = rawContent.trim())
        } catch (e: Exception) {
            Log.w(TAG, "parseResponseJson fallback to chat: ${e.message}")
            GeminiVoiceAction(action = "chat", ttsReply = rawContent.trim())
        }
    }

    suspend fun query(userInput: String, voiceContext: VoiceContext? = null): GeminiVoiceAction? = withContext(Dispatchers.IO) {
        val systemPrompt = buildSystemPrompt(voiceContext)
        val history = getActiveHistorySnapshot()

        // 1. Nếu có Direct Google Gemini API Key: gọi thẳng Google Cloud
        if (geminiDirectApiKey.isNotBlank()) {
            val directResult = queryDirectGeminiCloud(userInput, systemPrompt, history, geminiDirectApiKey)
            if (directResult != null) {
                appendHistoryTurn(userInput, directResult.ttsReply ?: directResult.action)
                return@withContext directResult
            }
        }

        // 2. Gọi qua Cổng LAN Gemini Pro Pool (192.168.1.189:8100)
        try {
            val messages = mutableListOf<Map<String, String>>(
                mapOf("role" to "system", "content" to systemPrompt)
            )
            for ((u, a) in history) {
                messages.add(mapOf("role" to "user", "content" to u))
                messages.add(mapOf("role" to "assistant", "content" to a))
            }
            messages.add(mapOf("role" to "user", "content" to userInput))

            val reqBodyJson = JsonObject().apply {
                addProperty("model", "gemini-2.5-flash")
                add("messages", gson.toJsonTree(messages))
                addProperty("temperature", 0.1)
                add("response_format", JsonObject().apply {
                    addProperty("type", "json_object")
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(hermesHostUrl)
                .post(reqBodyJson.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string()
                    if (!bodyStr.isNullOrBlank()) {
                        val rootObj = gson.fromJson(bodyStr, JsonObject::class.java)
                        val content = rootObj.getAsJsonArray("choices")
                            ?.get(0)?.asJsonObject
                            ?.getAsJsonObject("message")
                            ?.get("content")?.asString

                        if (!content.isNullOrBlank()) {
                            val parsed = parseResponseJson(content)
                            if (parsed != null) {
                                appendHistoryTurn(userInput, parsed.ttsReply ?: parsed.action)
                                Log.i(TAG, "Gemini Pro Brain đã giải mã: action=${parsed.action}, actions=${parsed.actions.size}")
                                return@withContext parsed
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Gemini Pro LAN không phản hồi (${e.message}), chuyển sang phản xạ cục bộ 0ms")
        }

        return@withContext null
    }

    private fun queryDirectGeminiCloud(
        userInput: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        apiKey: String
    ): GeminiVoiceAction? {
        try {
            val cloudUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val reqJson = JsonObject().apply {
                val sysInst = JsonObject().apply {
                    val parts = JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", systemPrompt) })
                    }
                    add("parts", parts)
                }
                add("system_instruction", sysInst)

                val contents = JsonArray().apply {
                    for ((u, a) in history) {
                        add(JsonObject().apply {
                            addProperty("role", "user")
                            add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", u) }) })
                        })
                        add(JsonObject().apply {
                            addProperty("role", "model")
                            add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", a) }) })
                        })
                    }
                    add(JsonObject().apply {
                        addProperty("role", "user")
                        add("parts", JsonArray().apply { add(JsonObject().apply { addProperty("text", userInput) }) })
                    })
                }
                add("contents", contents)

                val genConfig = JsonObject().apply {
                    addProperty("temperature", 0.2)
                    addProperty("response_mime_type", "application/json")
                }
                add("generationConfig", genConfig)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(cloudUrl)
                .post(reqJson.toString().toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: return null
                    val root = gson.fromJson(bodyStr, JsonObject::class.java)
                    val text = root.getAsJsonArray("candidates")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")
                        ?.get(0)?.asJsonObject
                        ?.get("text")?.asString ?: return null

                    val parsed = parseResponseJson(text)
                    if (parsed != null) {
                        Log.i(TAG, "[Gemini Cloud Direct] action=${parsed.action}, actions=${parsed.actions.size}")
                        return parsed
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gemini Cloud Direct lỗi (${e.message})")
        }
        return null
    }

    private fun extractJsonString(raw: String): String {
        val str = raw.trim()
        val firstBrace = str.indexOf('{')
        val lastBrace = str.lastIndexOf('}')
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return str.substring(firstBrace, lastBrace + 1).trim()
        }
        var clean = str
        if (clean.startsWith("```json")) {
            clean = clean.removePrefix("```json").trim()
        } else if (clean.startsWith("```")) {
            clean = clean.removePrefix("```").trim()
        }
        if (clean.endsWith("```")) {
            clean = clean.removeSuffix("```").trim()
        }
        return clean.trim()
    }
}
