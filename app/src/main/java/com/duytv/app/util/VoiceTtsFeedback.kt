package com.duytv.app.util

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Trợ lý phản hồi giọng nói tiếng Việt tự nhiên (Voice TTS Feedback) cho TV.
 * Tích hợp tự động Audio Ducking, không chặn luồng giao diện, giải phóng bộ nhớ sạch sẽ.
 */
class VoiceTtsFeedback(private val context: Context) {
    companion object {
        private const val TAG = "VoiceTtsFeedback"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val localeVi = Locale("vi", "VN")
                    val result = tts?.setLanguage(localeVi)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.w(TAG, "Ngôn ngữ vi-VN chưa cài đặt hoàn thiện, dùng default Locale")
                        tts?.setLanguage(Locale.getDefault())
                    }
                    tts?.setPitch(1.02f)
                    tts?.setSpeechRate(1.08f)
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    isReady = true
                    Log.d(TAG, "TextToSpeech initialized successfully")
                } else {
                    Log.w(TAG, "TextToSpeech init status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi khởi tạo TextToSpeech", e)
        }
    }

    fun speak(
        text: String,
        onStart: (() -> Unit)? = null,
        onDone: (() -> Unit)? = null
    ) {
        if (!isReady || tts == null || text.isBlank()) {
            onDone?.invoke()
            return
        }

        mainHandler.post {
            try {
                val utteranceId = "duytv_tts_${System.currentTimeMillis()}"
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {
                        mainHandler.post { onStart?.invoke() }
                    }

                    override fun onDone(id: String?) {
                        mainHandler.post { onDone?.invoke() }
                    }

                    override fun onError(id: String?) {
                        mainHandler.post { onDone?.invoke() }
                    }
                })

                val speakResult = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
                if (speakResult != TextToSpeech.SUCCESS) {
                    onDone?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi phát giọng nói TTS", e)
                onDone?.invoke()
            }
        }
    }

    fun stop() {
        mainHandler.post {
            try {
                tts?.stop()
            } catch (_: Exception) {}
        }
    }

    fun destroy() {
        mainHandler.post {
            try {
                tts?.stop()
                tts?.shutdown()
                tts = null
                isReady = false
            } catch (_: Exception) {}
        }
    }
}
