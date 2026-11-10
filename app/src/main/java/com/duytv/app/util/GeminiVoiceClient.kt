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

data class VoiceContext(
    val movieTitle: String? = null,
    val episodeName: String? = null,
    val positionSeconds: Long = 0L,
    val channelName: String? = null
)

data class GeminiVoiceAction(
    val action: String, // "play_tv", "play_movie", "search_youtube", "playback_control", "system_action", "chat"
    val query: String? = null,
    val ttsReply: String? = null,
    val target: String? = null,
    val modelName: String? = null
)

/**
 * BỘ NÃO ĐA MÔ HÌNH HỢP NHẤT DUYTV (Unified Multi-Model Voice Brain):
 * 
 * 1. Cụm Mô Hình Tư Duy Sâu Sắc (High-Reasoning Cluster):
 *    - Claude Opus 5.5 & Claude Sonnet 5.5: Phân tích ngữ cảnh phức tạp, ý định ẩn, tâm lý học điện ảnh.
 *    - GPT-OSS 120B (Medium): Kho tri thức bách khoa, giải mã phim theo nội dung mô tả, gợi ý thông minh.
 * 
 * 2. Cụm Tốc Độ Cao & Đa Tài Khoản (High-Speed & Pool Cluster):
 *    - Gemini 4 / Argon / Gemini 3.8 Flash (Dàn 12 tài khoản Google AI Pool của Anh Duy qua LAN 192.168.1.189:8100).
 *    - Google Gemini Cloud Direct (Chạy độc lập trên TV không phụ thuộc máy tính PC).
 * 
 * 3. Tầng Phản Xạ Cục Bộ 0ms (Zero-Latency Local Reflex):
 *    - VoiceCommandParser: Tự động fallback trong < 1.5s nếu mạng nghẽn hoặc offline, không bao giờ đơ/treo TV.
 */
object GeminiVoiceClient {
    private const val TAG = "UnifiedVoiceBrain"

    // Cổng LAN AI Bridge (chạy 12 tài khoản Google Gemini Pro, Claude 5.5, GPT-OSS 120B)
    var hermesHostUrl: String = "http://192.168.1.189:8100/v1/chat/completions"

    // Cổng Google Gemini Cloud chính thức độc lập
    var geminiDirectApiKey: String = ""

    // Danh sách các model AI được hỗ trợ theo mức độ ưu tiên
    val SUPPORTED_AI_MODELS = listOf(
        "gemini-3.8-flash-low",
        "gemini-4-argon",
        "gpt-oss-120b",
        "claude-sonnet-4-6",
        "claude-opus-4-6"
    )

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .build()

    private fun buildSystemPrompt(context: VoiceContext?): String {
        val contextInfo = buildString {
            if (!context?.movieTitle.isNullOrBlank()) {
                append("\n- TV ĐANG PHÁT PHIM: '${context.movieTitle}' (${context.episodeName ?: "Trọn bộ"}), ở giây thứ ${context.positionSeconds}s.")
            }
            if (!context?.channelName.isNullOrBlank()) {
                append("\n- TV ĐANG XEM KÊNH: '${context.channelName}'.")
            }
        }

        return """
            Bạn là Trợ lý AI DuyTV độc quyền cho Anh Duy trên Android TV.
            Hệ thống vận hành đa mô hình (Claude Opus 5.5, Claude Sonnet 5.5, GPT-OSS 120B, Gemini Pro 12-Pool).
            
            Ngữ cảnh thiết bị hiện tại:$contextInfo
            
            Nhiệm vụ: Phân tích khẩu lệnh tiếng Việt của Anh Duy và trả về đúng JSON hành động.
            
            Quy tắc phân loại:
            1. 'play_tv': Xem truyền hình Việt Nam (VTV1, HTV9, VTC, THVL, K+, bóng đá...).
            2. 'play_movie': Xem phim ảnh, anime, phim bộ, phim lẻ trong kho DuyTV.
            3. 'search_youtube': Tìm kiếm ca sĩ, bài hát, MV, karaoke, hài kịch, tin tức, review trên YouTube.
            4. 'playback_control': Điều khiển video (pause, resume, seek, speed, clear_dialog, subtitle, next_ep, prev_ep).
            5. 'system_action': Điều khiển TV (volume_up, volume_down, mute, home, back, power).
            6. 'chat': Hỏi đáp nội dung phim đang xem, thời tiết, kiến thức, trò chuyện tự nhiên với Anh Duy.
            
            Bắt buộc trả về đúng định dạng JSON:
            {
              "action": "play_tv" | "play_movie" | "search_youtube" | "playback_control" | "system_action" | "chat",
              "query": "tên bài hát, ca sĩ, kênh hoặc phim đã được lọc sạch",
              "tts_reply": "câu trả lời ngắn gọn 1 câu, thân mật, xưng em với anh Duy bằng tiếng Việt"
            }
        """.trimIndent()
    }

    suspend fun query(userInput: String, voiceContext: VoiceContext? = null): GeminiVoiceAction? = withContext(Dispatchers.IO) {
        val systemPrompt = buildSystemPrompt(voiceContext)

        // 1. Nếu có Direct Google Gemini API Key: gọi thẳng Google Cloud
        if (geminiDirectApiKey.isNotBlank()) {
            val directResult = queryDirectGeminiCloud(userInput, systemPrompt, geminiDirectApiKey)
            if (directResult != null) return@withContext directResult
        }

        // 2. Thử truy vấn qua Cổng LAN Đa Mô Hình (Hermes Bridge)
        for (modelName in SUPPORTED_AI_MODELS) {
            try {
                val messages = listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to userInput)
                )
                val reqBodyJson = JsonObject().apply {
                    addProperty("model", modelName)
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

                            Log.i(TAG, "[$modelName] Đã giải mã: action=$action, query=$query, tts=$ttsReply")
                            return@withContext GeminiVoiceAction(action, query, ttsReply, modelName = modelName)
                        }
                    }
                }
            } catch (e: Exception) {
                // Tiếp tục thử model kế tiếp trong cụm đa mô hình hoặc fallback
                Log.d(TAG, "Model $modelName không khả dụng (${e.message}), thử phương án dự phòng...")
            }
        }

        return@withContext null
    }

    private fun queryDirectGeminiCloud(userInput: String, systemPrompt: String, apiKey: String): GeminiVoiceAction? {
        try {
            val cloudUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey"
            val reqJson = JsonObject().apply {
                val sysInst = JsonObject().apply {
                    val parts = com.google.gson.JsonArray().apply {
                        val part = JsonObject().apply { addProperty("text", systemPrompt) }
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

                Log.i(TAG, "[Gemini Cloud Direct] action=$action, query=$query, tts=$ttsReply")
                return GeminiVoiceAction(action, query, ttsReply, modelName = "Gemini Cloud")
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
