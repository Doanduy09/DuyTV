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
    val volumePercent: Int = -1
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
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(4500, TimeUnit.MILLISECONDS)
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
        }

        return """
            Bạn là Trợ lý AI DuyTV thông minh toàn năng cho Anh Duy trên Android TV.
            
            Ngữ cảnh thiết bị hiện tại:$contextInfo
            
            Nhiệm vụ: Hiểu khẩu lệnh tiếng Việt tự nhiên (kể cả câu hỏi nối tiếp hoặc câu ghép nhiều bước) và trả về JSON hành động chính xác.
            
            Các giá trị `action` hợp lệ:
            - "play_tv": Mở kênh truyền hình VN (query = tên kênh, vd "VTV1", "HTV7").
            - "play_movie": Tìm/mở phim trong DuyTV (query = tên phim sạch).
            - "search_youtube": Tìm/nghe nhạc, karaoke, video trên YouTube (query = từ khóa sạch).
            - "open_app": Mở ứng dụng trên TV (target = tên app, vd "YouTube", "TV360", "Netflix", "Cài đặt").
            - "open_setting": Mở mục cài đặt (target = "wifi"|"bluetooth"|"sound"|"display"|"apps"|"date"|"accessibility"|"system").
            - "set_volume_percent": Đặt âm lượng theo % (target = số 0..100).
            - "adjust_volume": Tăng/giảm âm lượng (target = "up"|"down"|"mute").
            - "switch_hdmi": Chuyển cổng đầu vào TV (target = "hdmi 1"|"hdmi 2"|"av").
            - "media_control": Điều khiển phát media toàn cục (target = "play_pause"|"next"|"previous"|"fast_forward"|"rewind").
            - "system_action": Điều khiển hệ thống TV (target = "home"|"back"|"recents"|"quick_settings"|"screenshot"|"power").
            - "ui_click": Bấm vào nút/chữ đang hiện trên màn hình TV (target = chữ trên nút).
            - "ui_scroll": Cuộn màn hình hiện tại (target = "down"|"up").
            - "night_mode": Bật/tắt chế độ dịu mắt ban đêm.
            - "sleep_timer": Hẹn giờ tắt (target = số phút, vd "30").
            - "playback_control": Điều khiển trình phát DuyTV (query = câu lệnh chuẩn).
            - "chat": Trả lời câu hỏi kiến thức, gợi ý phim, tóm tắt phim, trò chuyện tự nhiên.
            
            Nếu người dùng yêu cầu nhiều bước liên tiếp (vd: "giảm âm lượng 30% rồi mở VTV1"), trả về mảng `actions`.
            Bắt buộc trả về đúng JSON:
            {
              "action": "...",
              "query": "...",
              "target": "...",
              "tts_reply": "câu trả lời ngắn gọn, tự nhiên, xưng em với anh Duy",
              "actions": [{"action": "...", "query": "...", "target": "..."}]
            }
        """.trimIndent()
    }

    internal fun parseResponseJson(rawContent: String): GeminiVoiceAction? {
        return try {
            val jsonStr = extractJsonString(rawContent)
            val parsedObj = gson.fromJson(jsonStr, JsonObject::class.java) ?: return null
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
            GeminiVoiceAction(
                action = action,
                query = query,
                ttsReply = ttsReply,
                target = target,
                actions = subActions
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseResponseJson lỗi: ${e.message}")
            null
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
                addProperty("model", "gemini-3.8-flash-low")
                add("messages", gson.toJsonTree(messages))
                addProperty("temperature", 0.2)
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
        var str = raw.trim()
        if (str.startsWith("```json")) {
            str = str.removePrefix("```json").trim()
        } else if (str.startsWith("```")) {
            str = str.removePrefix("```").trim()
        }
        if (str.endsWith("```")) {
            str = str.removeSuffix("```").trim()
        }
        return str.trim()
    }
}
