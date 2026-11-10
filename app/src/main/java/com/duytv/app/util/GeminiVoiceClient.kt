package com.duytv.app.util

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class GeminiVoiceAction(
    val action: String, // "play_tv", "play_movie", "search_youtube", "playback_control", "system_action", "chat"
    val query: String? = null,
    val ttsReply: String? = null,
    val target: String? = null
)

/**
 * Trợ lý AI Độc quyền DuyTV - Kiến trúc Đa Tầng (Multi-Tier Resilient Architecture):
 * 1. Tầng Cloud/LAN: Kết nối trực tiếp Google Gemini API chính thức hoặc Cổng mạng nội bộ Hermes LAN Pool (192.168.1.189:8100).
 * 2. Tầng Phòng Vệ (Fast Failover): Nếu mạng chậm, tài khoản thường hết quota hoặc tắt máy tính, tự động chuyển về Tầng Cục Bộ trong < 1.5s.
 * 3. Tầng Cục Bộ 0ms: Bộ giải mã từ khóa VoiceCommandParser hoạt động 100% offline, zero-latency.
 */
object GeminiVoiceClient {
    private const val TAG = "GeminiVoiceClient"

    // Cổng LAN AI Hermes (12 tài khoản Google Gemini Pro chính thức của Anh Duy)
    var hermesHostUrl: String = "http://192.168.1.189:8100/v1/chat/completions"

    // Cổng Google Gemini Cloud chính thức (Hỗ trợ cả tài khoản Gmail thường & Gemini Pro)
    var geminiDirectApiKey: String = "" // Tùy chọn cấu hình trực tiếp từ người dùng

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    private val SYSTEM_PROMPT = """
        Bạn là Trợ lý AI DuyTV độc quyền cho Anh Duy trên Android TV.
        Nhiệm vụ: Phân tích khẩu lệnh tiếng Việt của Anh Duy và trả về JSON hành động chính xác nhất.
        
        Quy tắc phân loại:
        1. 'play_tv': Xem truyền hình Việt Nam (VTV1, VTV6, HTV9, VTC, bóng đá trực tiếp...).
        2. 'play_movie': Xem phim ảnh, anime, phim bộ, phim lẻ trong kho DuyTV.
        3. 'search_youtube': Tìm kiếm ca sĩ, bài hát, MV, karaoke, hài kịch, tin tức, review trên YouTube.
        4. 'playback_control': Điều khiển video (pause, resume, seek, speed, clear_dialog, subtitle, skip_intro).
        5. 'system_action': Điều khiển TV (volume_up, volume_down, mute, home, back, power).
        6. 'chat': Hỏi đáp kiến thức, thời tiết, giờ giấc, tâm sự đời thường.
        
        Bắt buộc trả về đúng cấu trúc JSON sau (không kèm giải thích markdown ngoài JSON):
        {
          "action": "play_tv" | "play_movie" | "search_youtube" | "playback_control" | "system_action" | "chat",
          "query": "tên bài hát, ca sĩ, kênh hoặc phim đã được lọc sạch",
          "tts_reply": "câu trả lời ngắn gọn, thân mật, xưng em với anh Duy bằng tiếng Việt"
        }
    """.trimIndent()

    suspend fun query(userInput: String): GeminiVoiceAction? = withContext(Dispatchers.IO) {
        // Ưu tiên 1: Thử truy vấn qua Google Gemini Cloud Trực Tiếp nếu có API Key
        if (geminiDirectApiKey.isNotBlank()) {
            val directResult = queryDirectGeminiCloud(userInput, geminiDirectApiKey)
            if (directResult != null) return@withContext directResult
        }

        // Ưu tiên 2: Thử truy vấn qua Cổng LAN Hermes Pool (192.168.1.189:8100)
        try {
            val messages = listOf(
                mapOf("role" to "system", "content" to SYSTEM_PROMPT),
                mapOf("role" to "user", "content" to userInput)
            )
            val reqBodyJson = JsonObject().apply {
                addProperty("model", "gemini-3.8-flash-low")
                add("messages", gson.toJsonTree(messages))
                addProperty("temperature", 0.1)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(hermesHostUrl)
                .post(reqBodyJson.toString().toRequestBody(mediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string()
                if (!bodyStr.isNullOrBlank()) {
                    val rootObj = gson.fromJson(bodyStr, JsonObject::class.java)
                    val content = rootObj.getAsJsonArray("choices")
                        ?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")
                        ?.get("content")?.asString

                    if (!content.isNullOrBlank()) {
                        val jsonStr = extractJsonString(content)
                        val parsedObj = gson.fromJson(jsonStr, JsonObject::class.java)
                        val action = parsedObj.get("action")?.asString ?: "chat"
                        val query = parsedObj.get("query")?.asString
                        val ttsReply = parsedObj.get("tts_reply")?.asString

                        Log.i(TAG, "Gemini Pro Brain (LAN): action=$action, query=$query, tts=$ttsReply")
                        return@withContext GeminiVoiceAction(action, query, ttsReply)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không kết nối được LAN Hermes (${e.message}). Chuyển sang xử lý cục bộ 0ms")
        }

        // Không phụ thuộc vào máy chủ: trả về null để PlayerScreen chuyển sang bộ phân tích cục bộ 0ms
        return@withContext null
    }

    private fun queryDirectGeminiCloud(userInput: String, apiKey: String): GeminiVoiceAction? {
        try {
            val cloudUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
            val reqJson = JsonObject().apply {
                val sysInst = JsonObject().apply {
                    val parts = com.google.gson.JsonArray().apply {
                        val part = JsonObject().apply { addProperty("text", SYSTEM_PROMPT) }
                        add(part)
                    }
                    add("parts", parts)
                }
                add("system_instruction", sysInst)

                val contents = com.google.gson.JsonArray().apply {
                    val userContent = JsonObject().apply {
                        val parts = com.google.gson.JsonArray().apply {
                            val part = JsonObject().apply { addProperty("text", userInput) }
                            add(part)
                        }
                        add("parts", parts)
                    }
                    add(userContent)
                }
                add("contents", contents)

                val genConfig = JsonObject().apply {
                    addProperty("temperature", 0.1)
                    addProperty("response_mime_type", "application/json")
                }
                add("generationConfig", genConfig)
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val request = Request.Builder()
                .url(cloudUrl)
                .post(reqJson.toString().toRequestBody(mediaType))
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val bodyStr = response.body?.string() ?: return null
                val root = gson.fromJson(bodyStr, JsonObject::class.java)
                val text = root.getAsJsonArray("candidates")
                    ?.get(0)?.asJsonObject
                    ?.getAsJsonObject("content")
                    ?.getAsJsonArray("parts")
                    ?.get(0)?.asJsonObject
                    ?.get("text")?.asString ?: return null

                val jsonStr = extractJsonString(text)
                val parsed = gson.fromJson(jsonStr, JsonObject::class.java)
                val action = parsed.get("action")?.asString ?: "chat"
                val query = parsed.get("query")?.asString
                val ttsReply = parsed.get("tts_reply")?.asString

                Log.i(TAG, "Gemini Cloud Direct: action=$action, query=$query, tts=$ttsReply")
                return GeminiVoiceAction(action, query, ttsReply)
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
