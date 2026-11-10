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
    private var resetRunnable: Runnable? = null
    private val silenceWatchdog = Runnable {
        if (isListening) {
            Log.d(TAG, "Silence watchdog triggered (850ms sau khi nói) -> gọi stopListening()")
            stopListening()
        }
    }

    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    fun startListening() {
        mainHandler.post {
            Log.d(TAG, "startListening invoked. isListening=$isListening, available=${isAvailable()}")
            if (isListening) {
                Log.d(TAG, "startListening: already listening, skipping redundant invocation.")
                return@post
            }

            // Hủy lịch reset và silence watchdog trước đó
            resetRunnable?.let { mainHandler.removeCallbacks(it) }
            mainHandler.removeCallbacks(silenceWatchdog)

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
                } else {
                    try {
                        speechRecognizer?.cancel()
                    } catch (_: Exception) {}
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, "vi-VN")
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
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
            mainHandler.removeCallbacks(silenceWatchdog)
            if (isListening) {
                try {
                    speechRecognizer?.stopListening()
                    _state.value = VoiceState.Processing("Đang phân tích...")
                } catch (e: Exception) {
                    Log.e(TAG, "Lỗi dừng nhận diện giọng nói", e)
                }
            }
        }
    }

    fun cancel() {
        mainHandler.post {
            resetRunnable?.let { mainHandler.removeCallbacks(it) }
            mainHandler.removeCallbacks(silenceWatchdog)
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
            scheduleReset(2500)
        }
    }

    private fun scheduleReset(delayMs: Long) {
        resetRunnable?.let { mainHandler.removeCallbacks(it) }
        resetRunnable = Runnable {
            _state.value = VoiceState.Idle
        }
        mainHandler.postDelayed(resetRunnable!!, delayMs)
    }

    fun destroy() {
        mainHandler.post {
            resetRunnable?.let { mainHandler.removeCallbacks(it) }
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
                mainHandler.removeCallbacks(silenceWatchdog)
                _state.value = VoiceState.Listening(5f)
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Giữ trạng thái ổn định, không re-emit làm rung giật giao diện (Anti-Jitter)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "onEndOfSpeech")
                mainHandler.removeCallbacks(silenceWatchdog)
                isListening = false
                _state.value = VoiceState.Processing("Đang xử lý khẩu lệnh...")
            }

            override fun onError(error: Int) {
                Log.w(TAG, "RecognitionListener onError: $error")
                mainHandler.removeCallbacks(silenceWatchdog)
                isListening = false
                if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_SERVER) {
                    try {
                        speechRecognizer?.destroy()
                    } catch (_: Exception) {}
                    speechRecognizer = null
                }
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Chưa nghe rõ khẩu lệnh. Bấm mic và nói lại nhé!"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Hết thời gian chờ giọng nói."
                    SpeechRecognizer.ERROR_AUDIO -> "Lỗi thu âm từ micro remote."
                    SpeechRecognizer.ERROR_CLIENT -> "Hệ thống bận. Bấm mic thử lại."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Lỗi mạng khi nhận diện giọng nói."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chưa cấp quyền Micro cho DuyTV."
                    else -> "Chưa nhận diện được ($error)"
                }
                _state.value = VoiceState.Error(msg)
                scheduleReset(2500)
            }

            override fun onResults(results: Bundle?) {
                mainHandler.removeCallbacks(silenceWatchdog)
                isListening = false
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val bestMatch = matches?.firstOrNull()?.trim()
                Log.d(TAG, "onResults: bestMatch=$bestMatch, matches=$matches")
                if (!bestMatch.isNullOrBlank()) {
                    this@VoiceAssistantManager.onResult(bestMatch)
                } else {
                    _state.value = VoiceState.Error("Chưa nghe rõ khẩu lệnh. Hãy thử lại!")
                    scheduleReset(2200)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!partial.isNullOrBlank()) {
                    _state.value = VoiceState.Processing(partial)

                    // Zero-Latency Ear-Trigger: Khớp khẩu lệnh nhanh dứt khoát -> Kích hoạt ngay 0ms!
                    if (VoiceCommandParser.isQuickTriggerCommand(partial)) {
                        Log.d(TAG, "Quick command matched onPartialResults: $partial -> Fast execute 0ms!")
                        mainHandler.removeCallbacks(silenceWatchdog)
                        isListening = false
                        try {
                            speechRecognizer?.stopListening()
                        } catch (_: Exception) {}
                        this@VoiceAssistantManager.onResult(partial)
                        return
                    }

                    // Tự động chốt lệnh sau 850ms nếu người dùng ngừng nói, phản hồi tức thì mà không cần chờ 15s!
                    mainHandler.removeCallbacks(silenceWatchdog)
                    mainHandler.postDelayed(silenceWatchdog, 850L)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }
}
