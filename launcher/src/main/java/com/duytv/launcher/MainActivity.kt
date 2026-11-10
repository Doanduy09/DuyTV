package com.duytv.launcher

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.duytv.launcher.ui.HomeScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive TV Fullscreen flags
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        try {
            val imageLoader = coil.ImageLoader.Builder(applicationContext)
                .allowHardware(false)
                .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                .crossfade(false)
                .memoryCache {
                    coil.memory.MemoryCache.Builder(applicationContext)
                        .maxSizeBytes(8 * 1024 * 1024)
                        .build()
                }
                .build()
            coil.Coil.setImageLoader(imageLoader)
        } catch (_: Exception) {}

        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                HomeScreen()
            }
        }
    }

    private fun isVoiceOrAssistKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
               keyCode == KeyEvent.KEYCODE_ASSIST ||
               keyCode == KeyEvent.KEYCODE_SEARCH
    }

    private fun launchDuyTvVoice() {
        try {
            val intent = packageManager.getLaunchIntentForPackage("com.duytv.app")
                ?: packageManager.getLeanbackLaunchIntentForPackage("com.duytv.app")
            if (intent != null) {
                intent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                intent.putExtra("trigger_voice", true)
                startActivity(intent)
            }
        } catch (_: Exception) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // 🎙️ Đánh chặn triệt để phím Micro Remote TV (KEYCODE_VOICE_ASSIST, KEYCODE_ASSIST & KEYCODE_SEARCH):
        // Khi người dùng bấm phím Micro trên Remote: Mở ngay DuyTV với giao diện giọng nói thay vì mở Google!
        if (isVoiceOrAssistKey(keyCode)) {
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                launchDuyTvVoice()
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVoiceOrAssistKey(keyCode)) {
            return true
        }
        // Prevent Back key from closing the launcher
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (isVoiceOrAssistKey(keyCode)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onSearchRequested(): Boolean {
        launchDuyTvVoice()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing on back press to maintain launcher state
    }
}
