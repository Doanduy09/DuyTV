package com.duytv.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.duytv.app.data.AppCacheManager
import com.duytv.app.data.MovieRepository
import com.duytv.app.ui.components.VoiceOverlay
import com.duytv.app.ui.theme.DuyTvTheme
import com.duytv.app.util.VoiceAssistantManager
import com.duytv.app.util.VoiceIntentHelper
import com.duytv.app.util.VoiceState
import com.duytv.app.util.VoiceTtsFeedback

/**
 * Activity trong suốt chuyên xử lý nút Micro / Google Assistant hệ thống (WEB_SEARCH / ASSIST)
 * khi người dùng đang xem ứng dụng khác (ví dụ YouTube, Netflix, Launcher).
 *
 * Bảo đảm giữ nguyên 100% ứng dụng hiện tại trên màn hình thay vì tự ý kéo DuyTV (mục trước) lên đè;
 * chỉ chuyển sang MainActivity sau khi người dùng thực sự nói ra khẩu lệnh mở nội dung của DuyTV.
 */
class VoiceOverlayActivity : ComponentActivity() {

    private val repository = MovieRepository()
    private var voiceManagerRef: VoiceAssistantManager? = null
    private var downAtMs: Long = 0L

    private fun finishWithoutAnim() {
        if (!isFinishing) {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun isVoiceOrAssistKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
               keyCode == KeyEvent.KEYCODE_ASSIST ||
               keyCode == KeyEvent.KEYCODE_SEARCH
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isVoiceOrAssistKey(event.keyCode)) {
            val now = System.currentTimeMillis()
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                downAtMs = now
                if (voiceManagerRef?.state?.value is VoiceState.Idle) {
                    voiceManagerRef?.startListening()
                }
                return true
            } else if (event.action == KeyEvent.ACTION_UP) {
                if (now - downAtMs > 350L && voiceManagerRef?.state?.value is VoiceState.Listening) {
                    voiceManagerRef?.stopListening()
                }
                return true
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (MainActivity.isActivityInForeground) {
            MainActivity.dispatchVoiceKeyFromOverlay()
            finishWithoutAnim()
            return
        }
        if (voiceManagerRef?.state?.value is VoiceState.Idle) {
            voiceManagerRef?.startListening()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Nếu DuyTV đang mở sẵn trên màn hình, nhường cho VoiceOverlay nội bộ để không ngắt ExoPlayer
        if (MainActivity.isActivityInForeground) {
            MainActivity.dispatchVoiceKeyFromOverlay()
            finishWithoutAnim()
            return
        }

        AppCacheManager.init(applicationContext, MovieRepository.getVerifiedOfficialChannels())
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        }

        setContent {
            val appContext = applicationContext
            DuyTvTheme {
                val scope = rememberCoroutineScope()
                val tts = remember { VoiceTtsFeedback(appContext) }
                var hasEnteredActiveState by remember { mutableStateOf(false) }

                val voiceManager = remember {
                    VoiceAssistantManager(appContext) { recognizedText, candidates ->
                        VoiceIntentHelper.handleRecognizedVoiceCommand(
                            appContext = appContext,
                            repository = repository,
                            voiceManager = this,
                            ttsAssistant = tts,
                            scope = scope,
                            recognizedText = recognizedText,
                            candidates = candidates,
                            onNavigateScreen = { targetScreen, ttsPhrase ->
                                MainActivity.openScreenFromVoiceOverlay(this@VoiceOverlayActivity, targetScreen, ttsPhrase)
                                finishWithoutAnim()
                            },
                            onSystemHome = {
                                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_HOME)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                try { startActivity(homeIntent) } catch (_: Exception) {}
                                finishWithoutAnim()
                            },
                            onSystemBack = {
                                cancel()
                                finishWithoutAnim()
                            },
                            onExternalActionDone = {
                                finishWithoutAnim()
                            }
                        )
                    }
                }

                DisposableEffect(voiceManager) {
                    voiceManagerRef = voiceManager
                    onDispose {
                        voiceManagerRef = null
                        voiceManager.destroy()
                        tts.destroy()
                    }
                }

                val voiceState by voiceManager.state.collectAsState()

                LaunchedEffect(Unit) {
                    voiceManager.startListening()
                }

                LaunchedEffect(voiceState) {
                    if (voiceState !is VoiceState.Idle) {
                        hasEnteredActiveState = true
                    } else if (hasEnteredActiveState) {
                        finishWithoutAnim()
                    }
                }

                BackHandler {
                    voiceManager.cancel()
                    finishWithoutAnim()
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    VoiceOverlay(
                        state = voiceState,
                        onDismiss = {
                            voiceManager.cancel()
                            finishWithoutAnim()
                        }
                    )
                }
            }
        }
    }
}
