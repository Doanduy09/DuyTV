package com.duytv.app.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.duytv.app.MainActivity
import java.lang.ref.WeakReference
import java.util.LinkedList

data class ScreenUiSnapshot(
    val activePackage: String? = null,
    val visibleLabels: List<String> = emptyList()
)

/**
 * DuyVoiceAccessibilityService:
 * - Lắng nghe và đánh chặn triệt để phím Micro Remote TV (KEYCODE_VOICE_ASSIST, KEYCODE_ASSIST, KEYCODE_SEARCH).
 * - Cung cấp năng lực điều khiển toàn hệ thống TV (Global Actions, đọc màn hình, bấm nút & cuộn UI ở mọi app).
 */
class DuyVoiceAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instanceRef: WeakReference<DuyVoiceAccessibilityService>? = null

        fun isServiceConnected(): Boolean = instanceRef?.get() != null

        fun performTvGlobalAction(action: Int): Boolean {
            return try {
                instanceRef?.get()?.performGlobalAction(action) ?: false
            } catch (_: Exception) {
                false
            }
        }

        fun captureScreenContext(maxLabels: Int = 15): ScreenUiSnapshot {
            val svc = instanceRef?.get() ?: return ScreenUiSnapshot()
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return ScreenUiSnapshot()
            val pkg = root.packageName?.toString()
            val labels = LinkedHashSet<String>()
            val queue = LinkedList<AccessibilityNodeInfo>()
            queue.add(root)
            try {
                while (queue.isNotEmpty() && labels.size < maxLabels) {
                    val node = queue.poll() ?: continue
                    val rawText = node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
                        ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }
                    if (rawText != null && rawText.length in 2..60 && (node.isClickable || node.isFocusable)) {
                        labels.add(rawText)
                    }
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.add(it) }
                    }
                    if (node !== root) {
                        @Suppress("DEPRECATION")
                        node.recycle()
                    }
                }
            } catch (_: Exception) {
            } finally {
                @Suppress("DEPRECATION")
                while (queue.isNotEmpty()) {
                    val rem = queue.poll()
                    if (rem != null && rem !== root) {
                        try { rem.recycle() } catch (_: Exception) {}
                    }
                }
                @Suppress("DEPRECATION")
                try { root.recycle() } catch (_: Exception) {}
            }
            return ScreenUiSnapshot(activePackage = pkg, visibleLabels = labels.toList())
        }

        fun clickNodeByText(query: String): Boolean {
            val q = query.trim()
            if (q.isBlank()) return false
            val svc = instanceRef?.get() ?: return false
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return false
            val queue = LinkedList<AccessibilityNodeInfo>()
            queue.add(root)
            try {
                while (queue.isNotEmpty()) {
                    val node = queue.poll() ?: continue
                    val text = node.text?.toString()?.trim().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim().orEmpty()
                    if (text.contains(q, ignoreCase = true) || desc.contains(q, ignoreCase = true)) {
                        var clickableTarget: AccessibilityNodeInfo? = node
                        while (clickableTarget != null) {
                            if (clickableTarget.isClickable) {
                                val clicked = clickableTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                if (clickableTarget !== node && clickableTarget !== root) {
                                    @Suppress("DEPRECATION")
                                    try { clickableTarget.recycle() } catch (_: Exception) {}
                                }
                                if (node !== root) {
                                    @Suppress("DEPRECATION")
                                    try { node.recycle() } catch (_: Exception) {}
                                }
                                return clicked
                            }
                            val nextParent = clickableTarget.parent
                            if (clickableTarget !== node && clickableTarget !== root) {
                                @Suppress("DEPRECATION")
                                try { clickableTarget.recycle() } catch (_: Exception) {}
                            }
                            clickableTarget = nextParent
                        }
                        if (node.isFocusable) {
                            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            if (node !== root) {
                                @Suppress("DEPRECATION")
                                try { node.recycle() } catch (_: Exception) {}
                            }
                            return clicked
                        }
                    }
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.add(it) }
                    }
                    if (node !== root) {
                        @Suppress("DEPRECATION")
                        node.recycle()
                    }
                }
            } catch (_: Exception) {
            } finally {
                @Suppress("DEPRECATION")
                while (queue.isNotEmpty()) {
                    val rem = queue.poll()
                    if (rem != null && rem !== root) {
                        try { rem.recycle() } catch (_: Exception) {}
                    }
                }
                @Suppress("DEPRECATION")
                try { root.recycle() } catch (_: Exception) {}
            }
            return false
        }

        fun scrollWindow(forward: Boolean): Boolean {
            val svc = instanceRef?.get() ?: return false
            val root = try { svc.rootInActiveWindow } catch (_: Exception) { null } ?: return false
            val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            val queue = LinkedList<AccessibilityNodeInfo>()
            queue.add(root)
            try {
                while (queue.isNotEmpty()) {
                    val node = queue.poll() ?: continue
                    if (node.isScrollable) {
                        val ok = node.performAction(action)
                        if (node !== root) {
                            @Suppress("DEPRECATION")
                            try { node.recycle() } catch (_: Exception) {}
                        }
                        return ok
                    }
                    for (i in 0 until node.childCount) {
                        node.getChild(i)?.let { queue.add(it) }
                    }
                    if (node !== root) {
                        @Suppress("DEPRECATION")
                        node.recycle()
                    }
                }
            } catch (_: Exception) {
            } finally {
                @Suppress("DEPRECATION")
                while (queue.isNotEmpty()) {
                    val rem = queue.poll()
                    if (rem != null && rem !== root) {
                        try { rem.recycle() } catch (_: Exception) {}
                    }
                }
                @Suppress("DEPRECATION")
                try { root.recycle() } catch (_: Exception) {}
            }
            return false
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
    }

    override fun onDestroy() {
        instanceRef = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Không xử lý nặng trong callback để tiết kiệm tối đa tài nguyên TV
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
            return true
        }

        return super.onKeyEvent(event)
    }
}
