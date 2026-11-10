package com.duytv.app.util

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

sealed class VoiceState {
    object Idle : VoiceState()
    data class Listening(val rmsDb: Float = 0f) : VoiceState()
    data class Processing(val partialText: String = "") : VoiceState()
    data class Success(val message: String) : VoiceState()
    data class Error(val message: String) : VoiceState()
}

class VoiceAssistantManager(
    private val context: Context,
    private val onResult: VoiceAssistantManager.(String) -> Unit
) {
    companion object {
        private const val TAG = "VoiceAssistantManager"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private var isListening = false

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening() {
        mainHandler.post {
            Log.d(TAG, "startListening invoked. isListening=$isListening, available=${isAvailable()}")
            if (isListening) {
                cancel()
                return@post
            }

            if (!isAvailable()) {
                _state.value = VoiceState.Error("Hệ thống nhận diện giọng nói chưa sẵn sàng trên thiết bị")
                scheduleReset(2500)
                return@post
            }

            try {
                if (speechRecognizer == null) {
                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                        setRecognitionListener(createListener())
                    }
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

                speechRecognizer?.startListening(intent)
                isListening = true
                _state.value = VoiceState.Listening(0f)
                Log.d(TAG, "speechRecognizer.startListening started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi khi bắt đầu nhận diện giọng nói", e)
                _state.value = VoiceState.Error("Không thể kích hoạt micro: ${e.message}")
                isListening = false
                scheduleReset(2500)
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            if (isListening) {
                try {
                    speechRecognizer?.stopListening()
                    _state.value = VoiceState.Processing("Đang xử lý...")
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi dừng nhận diện giọng nói", e)
                }
            }
        }
    }

    fun cancel() {
        mainHandler.post {
            isListening = false
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
            _state.value = VoiceState.Idle
        }
    }

    fun reportSuccess(message: String) {
        mainHandler.post {
            isListening = false
            _state.value = VoiceState.Success(message)
            scheduleReset(1600)
        }
    }

    fun reportError(message: String) {
        mainHandler.post {
            isListening = false
            _state.value = VoiceState.Error(message)
            scheduleReset(2200)
        }
    }

    private fun scheduleReset(delayMs: Long) {
        mainHandler.postDelayed({
            _state.value = VoiceState.Idle
        }, delayMs)
    }

    fun destroy() {
        mainHandler.post {
            isListening = false
            try {
                speechRecognizer?.destroy()
            } catch (_: Exception) {}
            speechRecognizer = null
            _state.value = VoiceState.Idle
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "onReadyForSpeech")
                _state.value = VoiceState.Listening(0f)
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "onBeginningOfSpeech")
                _state.value = VoiceState.Listening(5f)
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (isListening) {
                    _state.value = VoiceState.Listening(rmsdB.coerceAtLeast(0f))
                }
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
                isListening = false
                _state.value = VoiceState.Processing("Đang nhận diện giọng nói...")
            }

            override fun onError(error: Int) {
                Log.w(TAG, "RecognitionListener onError: $error")
                isListening = false
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Chưa nghe rõ tên đài. Hãy nói lại (ví dụ: 'VTV1', 'THVL1')."
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Hết thời gian chờ giọng nói."
                    SpeechRecognizer.ERROR_AUDIO -> "Lỗi thu âm từ micro remote."
                    SpeechRecognizer.ERROR_CLIENT -> "Lỗi kết nối bộ nhận diện."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Lỗi mạng khi nhận diện giọng nói."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chưa cấp quyền Micro cho DuyTV."
                    else -> "Lỗi nhận diện ($error)"
                }
                _state.value = VoiceState.Error(msg)
                scheduleReset(2200)
            }

            override fun onResults(results: Bundle?) {
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val bestMatch = matches?.firstOrNull()?.trim()
                Log.d(TAG, "onResults: bestMatch=$bestMatch, matches=$matches")
                if (!bestMatch.isNullOrBlank()) {
                    this@VoiceAssistantManager.onResult(bestMatch)
                } else {
                    _state.value = VoiceState.Error("Không tìm thấy kết quả phù hợp.")
                    scheduleReset(2000)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    _state.value = VoiceState.Processing(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}
