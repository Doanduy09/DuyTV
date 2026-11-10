package com.duytv.app.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.duytv.app.MainActivity

/**
 * DuyVoiceAccessibilityService:
 * Lắng nghe và đánh chặn triệt để phím Micro Remote TV (KEYCODE_VOICE_ASSIST = 231, KEYCODE_ASSIST = 219, KEYCODE_SEARCH = 84).
 * Trả về true ở cả ACTION_DOWN và ACTION_UP để ngăn chặn 100% việc hệ điều hành Android TV / Google TV
 * khởi chạy Google Assistant (Katniss / SearchActivityTrampoline), đồng thời kích hoạt giao diện
 * điều khiển giọng nói tiếng Việt chuyên nghiệp của DuyTV ngay lập tức.
 */
class DuyVoiceAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Chỉ xử lý key filter, không lắng nghe UI events khác để tiết kiệm tối đa tài nguyên
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val isVoiceKey = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
                         keyCode == KeyEvent.KEYCODE_ASSIST ||
                         keyCode == KeyEvent.KEYCODE_SEARCH

        if (isVoiceKey) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) {
                    MainActivity.triggerVoiceFromService(applicationContext, event)
                }
            } else if (event.action == KeyEvent.ACTION_UP) {
                MainActivity.triggerVoiceFromService(applicationContext, event)
            }
            // QUAN TRỌNG: Trả về true cho cả ACTION_DOWN lẫn ACTION_UP để triệt tiêu hoàn toàn Katniss / Google Assistant
            return true
        }

        return super.onKeyEvent(event)
    }
}
