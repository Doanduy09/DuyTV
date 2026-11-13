package com.duytv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.duytv.app.data.AppCacheManager
import com.duytv.app.data.EpisodeItemDto
import com.duytv.app.data.MovieRepository
import com.duytv.app.ui.components.VoiceOverlay
import com.duytv.app.ui.screens.HomeScreen
import com.duytv.app.ui.screens.MovieDetailScreen
import com.duytv.app.ui.screens.PlayerScreen
import com.duytv.app.ui.screens.SearchScreen
import com.duytv.app.ui.theme.DuyTvTheme
import com.duytv.app.util.GeminiVoiceClient
import com.duytv.app.util.VoiceAssistantManager
import com.duytv.app.util.VoiceCommand
import com.duytv.app.util.VoiceCommandParser
import com.duytv.app.util.VoiceContext
import com.duytv.app.util.VoiceIntentHelper
import com.duytv.app.util.VoiceState
import com.duytv.app.util.VoiceTtsFeedback
import kotlinx.coroutines.launch

sealed class AppScreen {
    object Home : AppScreen()
    data class CategoryHome(val categoryId: String) : AppScreen()
    object Search : AppScreen()
    data class SearchQuery(val query: String) : AppScreen()
    data class Detail(val slug: String) : AppScreen()
    data class Player(
        val slug: String,
        val movieTitle: String,
        val episodeName: String,
        val streamUrl: String,
        val posterUrl: String? = null,
        val subtitleUrl: String? = null,
        val initialPositionMs: Long = 0L,
        val backupUrls: List<String> = emptyList(),
        val episodes: List<EpisodeItemDto> = emptyList()
    ) : AppScreen()
}

class MainActivity : ComponentActivity() {
    companion object {
        var onKeyEventListener: ((android.view.KeyEvent) -> Boolean)? = null
        private const val IDLE_AUTO_KILL_MS = 10 * 60 * 1000L // 10 phút không hoạt động trên Điện thoại / Máy tính bảng

        @Volatile
        var isVideoActivelyPlaying: Boolean = false
            private set

        @Volatile
        var isActivityInForeground: Boolean = false
            private set

        @Volatile
        var pendingVoiceTrigger: Boolean = false

        @Volatile
        var lastVoiceTriggerMs: Long = 0L

        @Volatile
        var lastUserInteractionMs: Long = System.currentTimeMillis()
            private set

        fun notifyPlaybackState(isPlaying: Boolean) {
            isVideoActivelyPlaying = isPlaying
            lastUserInteractionMs = System.currentTimeMillis()
        }

        fun triggerVoiceFromService(context: android.content.Context, event: android.view.KeyEvent) {
            val now = System.currentTimeMillis()
            if (now - lastVoiceTriggerMs < 800L) {
                return
            }
            lastVoiceTriggerMs = now
            if (isActivityInForeground && onKeyEventListener?.invoke(event) == true) {
                return
            }
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                pendingVoiceTrigger = true
                val intent = android.content.Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                    putExtra("trigger_voice", true)
                }
                context.startActivity(intent)
            }
        }
    }

    private val repository = MovieRepository()
    private var pendingScreenFromIntent by mutableStateOf<AppScreen?>(null)
    private var onGlobalVoiceKeyEvent: ((android.view.KeyEvent) -> Boolean)? = null
    private var globalVoiceTriggerTick by mutableIntStateOf(0)
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var backgroundedAtMs: Long = 0L

    private val idleWatchdogRunnable = object : Runnable {
        override fun run() {
            if (com.duytv.app.util.HardwareOptimizer.isTvDevice(this@MainActivity)) return
            val now = System.currentTimeMillis()
            val isBackgroundIdleExpired = backgroundedAtMs > 0L && (now - backgroundedAtMs >= IDLE_AUTO_KILL_MS)
            val isForegroundIdleExpired = backgroundedAtMs == 0L && !isVideoActivelyPlaying && (now - lastUserInteractionMs >= IDLE_AUTO_KILL_MS)
            if (isBackgroundIdleExpired || isForegroundIdleExpired) {
                terminateForBatterySaving()
                return
            }
            idleHandler.postDelayed(this, 20_000L)
        }
    }

    private fun terminateForBatterySaving() {
        try {
            idleHandler.removeCallbacksAndMessages(null)
            coil.Coil.imageLoader(this).memoryCache?.clear()
            finishAndRemoveTask()
        } catch (_: Exception) {}
        kotlin.system.exitProcess(0)
    }

    private fun handleLauncherIntent(intent: android.content.Intent?) {
        if (intent == null) return
        com.duytv.app.data.AppCacheManager.init(applicationContext, MovieRepository.getVerifiedOfficialChannels())
        val streamUrl = intent.getStringExtra("stream_url")
        val channelName = intent.getStringExtra("channel_name")
        if (!streamUrl.isNullOrBlank() && !channelName.isNullOrBlank()) {
            val channelId = intent.getStringExtra("channel_id") ?: System.currentTimeMillis().toString()
            val logoUrl = intent.getStringExtra("logo_url")
            val backups = intent.getStringArrayListExtra("backup_urls") ?: arrayListOf()
            pendingScreenFromIntent = AppScreen.Player(
                slug = "live_launcher_$channelId",
                movieTitle = channelName,
                episodeName = "TRỰC TIẾP",
                streamUrl = streamUrl,
                posterUrl = logoUrl,
                subtitleUrl = null,
                initialPositionMs = 0L,
                backupUrls = backups
            )
            intent.removeExtra("stream_url")
            intent.removeExtra("channel_name")
        } else if (intent.getBooleanExtra("open_home", false)) {
            pendingScreenFromIntent = AppScreen.Home
            intent.removeExtra("open_home")
        } else if (intent.action == android.content.Intent.ACTION_SEARCH ||
                   intent.action == android.content.Intent.ACTION_ASSIST ||
                   intent.action == "android.speech.action.WEB_SEARCH" ||
                   intent.action == "android.speech.action.VOICE_SEARCH_HANDS_FREE" ||
                   intent.action == "android.intent.action.VOICE_COMMAND" ||
                   intent.getBooleanExtra("trigger_voice", false)) {
            val now = System.currentTimeMillis()
            if (now - lastVoiceTriggerMs < 800L) {
                return
            }
            lastVoiceTriggerMs = now
            val fakeEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOICE_ASSIST)
            if (onKeyEventListener?.invoke(fakeEvent) != true) {
                if (onGlobalVoiceKeyEvent?.invoke(fakeEvent) != true) {
                    pendingVoiceTrigger = true
                    globalVoiceTriggerTick++
                }
            }
            intent.removeExtra("trigger_voice")
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLauncherIntent(intent)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        lastUserInteractionMs = System.currentTimeMillis()
        return super.dispatchTouchEvent(ev)
    }

    private fun isVoiceOrAssistKey(keyCode: Int): Boolean {
        return keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST ||
               keyCode == android.view.KeyEvent.KEYCODE_ASSIST ||
               keyCode == android.view.KeyEvent.KEYCODE_SEARCH
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        lastUserInteractionMs = System.currentTimeMillis()
        val keyCode = event.keyCode
        val action = event.action

        // 🎙️ Đánh chặn triệt để phím Micro Remote TV (KEYCODE_VOICE_ASSIST, KEYCODE_ASSIST & KEYCODE_SEARCH):
        // Luôn tiêu thụ sự kiện để ngăn Google Assistant hệ thống nhảy đè lên màn hình DuyTV!
        if (isVoiceOrAssistKey(keyCode)) {
            val now = System.currentTimeMillis()
            if (action == android.view.KeyEvent.ACTION_DOWN) {
                if (event.repeatCount > 0) return true
                if (now - lastVoiceTriggerMs < 800L) {
                    return true
                }
                lastVoiceTriggerMs = now
            }
            if (onKeyEventListener?.invoke(event) == true) {
                return true
            }
            if (onGlobalVoiceKeyEvent?.invoke(event) == true) {
                return true
            }
            if (action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                pendingVoiceTrigger = true
                globalVoiceTriggerTick++
            }
            return true
        }

        if (onKeyEventListener?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (isVoiceOrAssistKey(keyCode)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent): Boolean {
        if (isVoiceOrAssistKey(keyCode)) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onSearchRequested(): Boolean {
        val fakeEvent = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOICE_ASSIST)
        if (onKeyEventListener?.invoke(fakeEvent) == true) {
            return true
        }
        pendingScreenFromIntent = AppScreen.Search
        return true
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!com.duytv.app.util.HardwareOptimizer.isTvDevice(this) &&
            isVideoActivelyPlaying &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        ) {
            try {
                val pipParams = android.app.PictureInPictureParams.Builder()
                    .setAspectRatio(android.util.Rational(16, 9))
                    .build()
                enterPictureInPictureMode(pipParams)
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityInForeground = true
    }

    override fun onPause() {
        isActivityInForeground = false
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        backgroundedAtMs = 0L
        lastUserInteractionMs = System.currentTimeMillis()
        if (!com.duytv.app.util.HardwareOptimizer.isTvDevice(this)) {
            idleHandler.removeCallbacks(idleWatchdogRunnable)
            idleHandler.postDelayed(idleWatchdogRunnable, 20_000L)
        }
    }

    override fun onStop() {
        super.onStop()
        backgroundedAtMs = System.currentTimeMillis()
        if (!com.duytv.app.util.HardwareOptimizer.isTvDevice(this)) {
            idleHandler.removeCallbacks(idleWatchdogRunnable)
            idleHandler.postDelayed(idleWatchdogRunnable, IDLE_AUTO_KILL_MS)
        }
        // Giữ Activity ấm trong RAM (Hot Process) để khi người dùng bấm mở DuyTV sẽ bật lên tức thì (0ms).
        // Âm thanh và MediaCodec của ExoPlayer đã được LifecycleEventObserver trong PlayerScreen
        // tự động pause/stop an toàn khi nhận sự kiện ON_STOP, bảo đảm 100% không rò rỉ âm thanh nền.
    }

    override fun onDestroy() {
        idleHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Khi người dùng chuyển sang app khác (YouTube, Netflix...) hoặc về Home:
        // Lập tức thu hẹp bộ nhớ RAM từ ~120MB xuống dưới 30MB để tránh bị hệ điều hành Android TV (LMK) kill.
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            try {
                coil.Coil.imageLoader(this).memoryCache?.clear()
                System.gc()
            } catch (_: Exception) {}
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.navigationBarColor = android.graphics.Color.BLACK
        window.statusBarColor = android.graphics.Color.BLACK
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        // Khởi tạo đồng bộ Coil với RGB_565 và tắt Hardware Bitmap để ngăn tràn bộ nhớ đồ họa
        // GraphicBuffer (EGL_BAD_ALLOC 12291) trên GPU MediaTek mt5891 khi mở song song ExoPlayer
        try {
            val hwProfile = com.duytv.app.util.HardwareOptimizer.getProfile(applicationContext)
            val imageLoader = coil.ImageLoader.Builder(applicationContext)
                .allowHardware(hwProfile.allowHardwareBitmaps)
                .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                .crossfade(false)
                .memoryCache {
                    coil.memory.MemoryCache.Builder(applicationContext)
                        .maxSizeBytes(12 * 1024 * 1024)
                        .build()
                }
                .build()
            // [✅ ĐÃ CHUẨN - CẤM SỬA]: Không gọi setImageLoader ở đây để giữ nguyên cấu hình chuẩn tại DuyTvApplication.newImageLoader()
        } catch (_: Exception) {}

        // Cấp quyền thu âm micro cho Remote TV điều khiển giọng nói
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 101)
        }

        handleLauncherIntent(intent)

        setContent {
            val appContext = applicationContext
            DuyTvTheme {
                var currentScreen by remember { mutableStateOf<AppScreen>(pendingScreenFromIntent ?: AppScreen.Home) }
                val rootScope = rememberCoroutineScope()
                val globalTts = remember { VoiceTtsFeedback(appContext) }
                val currentScreenState by rememberUpdatedState(currentScreen)

                val globalVoiceManager = remember {
                    VoiceAssistantManager(appContext) { recognizedText, candidates ->
                        fun speakGlobal(message: String, ttsPhrase: String = message, onDone: (() -> Unit)? = null) {
                            VoiceIntentHelper.speakAndReport(
                                voiceManager = this,
                                ttsAssistant = globalTts,
                                scope = rootScope,
                                message = message,
                                ttsPhrase = ttsPhrase,
                                onDone = onDone
                            )
                        }

                        val channels = AppCacheManager.activeChannels.ifEmpty {
                            MovieRepository.getVerifiedOfficialChannels()
                        }
                        val bestCandidate = VoiceCommandParser.selectBestCandidate(candidates, channels)
                        val rawQuery = (if (bestCandidate.isNotBlank()) bestCandidate else recognizedText).trim()
                        val localCmd = VoiceCommandParser.parse(rawQuery, channels)

                        fun runAppCommand(cmd: VoiceCommand) {
                            when (cmd) {
                                is VoiceCommand.SwitchChannel -> {
                                    speakGlobal("Đang mở: ${cmd.channel.name}", "Dạ, đang mở kênh ${cmd.channel.name}")
                                    currentScreen = VoiceIntentHelper.buildChannelPlayerScreen(cmd.channel)
                                }
                                is VoiceCommand.OpenMovie -> {
                                    speakGlobal("Đang tìm: ${cmd.query}", "Dạ, em đang tìm ${cmd.query}")
                                    rootScope.launch {
                                        try {
                                            val resolved = VoiceIntentHelper.resolveOpenMovieScreen(repository, cmd.query)
                                            if (resolved != null) {
                                                speakGlobal("Đang mở: ${resolved.first}", "Dạ, đang mở phim ${resolved.first}")
                                                currentScreen = resolved.second
                                            } else {
                                                val matchedCh = VoiceCommandParser.matchTvChannel(cmd.query, channels)
                                                if (matchedCh != null) {
                                                    speakGlobal("Đang mở: ${matchedCh.channel.name}", "Dạ, đang mở kênh ${matchedCh.channel.name}")
                                                    currentScreen = VoiceIntentHelper.buildChannelPlayerScreen(matchedCh.channel)
                                                } else {
                                                    speakGlobal("YouTube: ${cmd.query.uppercase()}", "Dạ, em mở ${cmd.query} trên YouTube cho anh") {
                                                        VoiceIntentHelper.launchYouTubeSearch(appContext, cmd.query)
                                                    }
                                                }
                                            }
                                        } catch (_: Exception) {
                                            speakGlobal("YouTube: ${cmd.query.uppercase()}", "Dạ, em mở ${cmd.query} trên YouTube cho anh") {
                                                VoiceIntentHelper.launchYouTubeSearch(appContext, cmd.query)
                                            }
                                        }
                                    }
                                }
                                is VoiceCommand.SearchMovie -> {
                                    speakGlobal("Tìm kiếm: ${cmd.query}", "Dạ, đang tìm kiếm ${cmd.query}")
                                    currentScreen = AppScreen.SearchQuery(cmd.query)
                                }
                                is VoiceCommand.BrowseCategory -> {
                                    speakGlobal("Đang mở: ${cmd.categoryName}", "Dạ, đang mở danh mục ${cmd.categoryName}")
                                    currentScreen = AppScreen.CategoryHome(cmd.categoryId)
                                }
                                is VoiceCommand.OpenApp -> {
                                    speakGlobal("Đang mở: ${cmd.target.uppercase()}", "Dạ, đang mở ứng dụng ${cmd.target}") {
                                        VoiceIntentHelper.launchAppByQuery(appContext, cmd.target)
                                    }
                                }
                                is VoiceCommand.YouTubeSearch -> {
                                    speakGlobal("YouTube: ${cmd.query.uppercase()}", "Dạ, đang tìm trên YouTube: ${cmd.query}") {
                                        VoiceIntentHelper.launchYouTubeSearch(appContext, cmd.query)
                                    }
                                }
                                is VoiceCommand.OpenSetting -> {
                                    speakGlobal("Đang mở cài đặt...", "Dạ, đang mở cài đặt")
                                    VoiceIntentHelper.launchSettingIntent(appContext, cmd.settingType)
                                }
                                is VoiceCommand.AdjustVolume -> {
                                    VoiceIntentHelper.adjustSystemVolume(appContext, cmd.isIncrease)
                                    val phrase = if (cmd.isIncrease) "Dạ, đã tăng âm lượng rồi anh nhé" else "Dạ, đã giảm âm lượng"
                                    speakGlobal(if (cmd.isIncrease) "Đã tăng âm lượng (+3)" else "Đã giảm âm lượng (-3)", phrase)
                                }
                                VoiceCommand.ToggleMute -> {
                                    VoiceIntentHelper.toggleSystemMute(appContext)
                                    speakGlobal("Đã bật/tắt tiếng", "Dạ, đã bật tắt tiếng")
                                }
                                is VoiceCommand.SystemAction -> {
                                    when (cmd.type) {
                                        VoiceCommand.SystemActionType.HOME,
                                        VoiceCommand.SystemActionType.POWER -> {
                                            speakGlobal("Về trang chủ", "Dạ, về trang chủ")
                                            currentScreen = AppScreen.Home
                                            moveTaskToBack(true)
                                        }
                                        VoiceCommand.SystemActionType.BACK -> {
                                            speakGlobal("Quay lại", "Dạ, quay lại")
                                            if (currentScreenState !is AppScreen.Home) currentScreen = AppScreen.Home
                                        }
                                    }
                                }
                                is VoiceCommand.Unknown -> {
                                    val rawLower = cmd.rawText.lowercase().trim()
                                    if (VoiceIntentHelper.isTvIntentQuery(rawLower)) {
                                        val matched = VoiceCommandParser.matchTvChannel(rawLower, channels)
                                        if (matched != null) {
                                            speakGlobal("Đang mở: ${matched.channel.name}", "Dạ, đang mở kênh ${matched.channel.name}")
                                            currentScreen = VoiceIntentHelper.buildChannelPlayerScreen(matched.channel)
                                        } else if (channels.isNotEmpty()) {
                                            speakGlobal("Đang mở: ${channels[0].name}", "Dạ, đang mở truyền hình cho anh")
                                            currentScreen = VoiceIntentHelper.buildChannelPlayerScreen(channels[0])
                                        }
                                    } else if (cmd.rawText.isNotBlank()) {
                                        speakGlobal("YouTube: ${cmd.rawText.uppercase()}", "Dạ, em mở nội dung này trên YouTube cho anh") {
                                            VoiceIntentHelper.launchYouTubeSearch(appContext, cmd.rawText)
                                        }
                                    } else {
                                        speakGlobal("Chưa rõ lệnh", "Dạ, em chưa hiểu câu lệnh này")
                                    }
                                }
                                else -> {
                                    speakGlobal("Dùng khi đang phát video", "Dạ, lệnh này dùng khi đang phát phim hoặc tivi ạ")
                                }
                            }
                        }

                        if (localCmd !is VoiceCommand.Unknown) {
                            runAppCommand(localCmd)
                        } else {
                            rootScope.launch {
                                val aiAction = GeminiVoiceClient.query(rawQuery, VoiceContext())
                                if (aiAction != null) {
                                    when (aiAction.action) {
                                        "search_youtube" -> {
                                            val q = aiAction.query ?: rawQuery
                                            speakGlobal("YouTube: ${q.uppercase()}", aiAction.ttsReply ?: "Dạ, em mở trên YouTube cho anh") {
                                                VoiceIntentHelper.launchYouTubeSearch(appContext, q)
                                            }
                                        }
                                        "play_tv" -> {
                                            val q = aiAction.query ?: rawQuery
                                            val matched = VoiceCommandParser.matchTvChannel(q, channels)
                                            if (matched != null) {
                                                speakGlobal("Đang mở: ${matched.channel.name}", aiAction.ttsReply ?: "Dạ, đang mở kênh ${matched.channel.name}")
                                                currentScreen = VoiceIntentHelper.buildChannelPlayerScreen(matched.channel)
                                            } else {
                                                runAppCommand(localCmd)
                                            }
                                        }
                                        "play_movie" -> {
                                            val q = aiAction.query ?: rawQuery
                                            speakGlobal("Đang tìm: $q", aiAction.ttsReply ?: "Dạ, đang mở phim $q")
                                            runAppCommand(VoiceCommand.OpenMovie(q))
                                        }
                                        "chat" -> {
                                            val reply = aiAction.ttsReply ?: "Dạ, em nghe đây anh Duy"
                                            speakGlobal(reply, reply)
                                        }
                                        else -> runAppCommand(localCmd)
                                    }
                                } else {
                                    runAppCommand(localCmd)
                                }
                            }
                        }
                    }
                }

                DisposableEffect(Unit) {
                    var globalDownAtMs = 0L
                    onGlobalVoiceKeyEvent = listener@{ event ->
                        if (currentScreenState is AppScreen.Player) return@listener false
                        val now = System.currentTimeMillis()
                        if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                            globalDownAtMs = now
                            if (globalVoiceManager.state.value is VoiceState.Idle) {
                                globalVoiceManager.startListening()
                            }
                            return@listener true
                        } else if (event.action == android.view.KeyEvent.ACTION_UP) {
                            if (now - globalDownAtMs > 350L && globalVoiceManager.state.value is VoiceState.Listening) {
                                globalVoiceManager.stopListening()
                            }
                            return@listener true
                        }
                        true
                    }
                    onDispose {
                        onGlobalVoiceKeyEvent = null
                        globalVoiceManager.destroy()
                        globalTts.destroy()
                    }
                }

                LaunchedEffect(globalVoiceTriggerTick, currentScreen is AppScreen.Player) {
                    if (currentScreen !is AppScreen.Player && pendingVoiceTrigger) {
                        pendingVoiceTrigger = false
                        if (globalVoiceManager.state.value is VoiceState.Idle) {
                            globalVoiceManager.startListening()
                        }
                    }
                }

                val globalVoiceState by globalVoiceManager.state.collectAsState()

                // Giải phóng tức thì bộ nhớ đệm ảnh poster khi bước vào trình phát PlayerScreen
                // để nhường 100% tài nguyên VRAM / GraphicBuffer cho bộ giải mã phần cứng MediaCodec
                LaunchedEffect(currentScreen is AppScreen.Player) {
                    if (currentScreen is AppScreen.Player) {
                        try {
                            coil.Coil.imageLoader(appContext).memoryCache?.clear()
                        } catch (_: Exception) {}
                    }
                }

                LaunchedEffect(pendingScreenFromIntent) {
                    pendingScreenFromIntent?.let { target ->
                        currentScreen = target
                        pendingScreenFromIntent = null
                    }
                }

                BackHandler(enabled = currentScreen !is AppScreen.Home) {
                    when (val screen = currentScreen) {
                        is AppScreen.Player -> {
                            if (screen.slug.startsWith("live_launcher_")) {
                                currentScreen = AppScreen.Home
                                moveTaskToBack(true)
                            } else {
                                currentScreen = if (screen.slug.startsWith("live_")) AppScreen.Home else AppScreen.Detail(screen.slug)
                            }
                        }
                        is AppScreen.Detail -> {
                            currentScreen = AppScreen.Home
                        }
                        is AppScreen.Search, is AppScreen.SearchQuery -> {
                            currentScreen = AppScreen.Home
                        }
                        is AppScreen.CategoryHome -> {
                            currentScreen = AppScreen.Home
                        }
                        else -> {}
                    }
                }

                // Khi ở màn hình Home, nhấn Back sẽ đưa DuyTV xuống nền (moveTaskToBack) thay vì hủy Activity.
                // Nhờ đó app luôn được giữ ấm trong RAM, khi người dùng bấm lại icon DuyTV trên Launcher
                // thì ứng dụng sẽ mở lên ngay tức thì (< 30ms) mà không phải khởi động lại.
                BackHandler(enabled = currentScreen is AppScreen.Home) {
                    moveTaskToBack(true)
                }

                BackHandler(enabled = currentScreen !is AppScreen.Player && globalVoiceState !is VoiceState.Idle) {
                    globalVoiceManager.cancel()
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    when (val screen = currentScreen) {
                    is AppScreen.Home -> {
                        HomeScreen(
                            repository = repository,
                            onMovieSelected = { slug ->
                                currentScreen = AppScreen.Detail(slug)
                            },
                            onOpenSearch = {
                                currentScreen = AppScreen.Search
                            },
                            onResumePlayback = { historyItem ->
                                currentScreen = AppScreen.Player(
                                    slug = historyItem.slug,
                                    movieTitle = historyItem.movieTitle,
                                    episodeName = historyItem.episodeName,
                                    streamUrl = historyItem.streamUrl,
                                    posterUrl = historyItem.posterUrl,
                                    subtitleUrl = null,
                                    initialPositionMs = historyItem.positionMs
                                )
                            },
                            onPlayLiveStream = { title, streamUrl, posterUrl, backups ->
                                currentScreen = AppScreen.Player(
                                    slug = "live_${System.currentTimeMillis()}",
                                    movieTitle = title,
                                    episodeName = "TRỰC TIẾP",
                                    streamUrl = streamUrl,
                                    posterUrl = posterUrl,
                                    subtitleUrl = null,
                                    initialPositionMs = 0L,
                                    backupUrls = backups
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    is AppScreen.CategoryHome -> {
                        HomeScreen(
                            repository = repository,
                            onMovieSelected = { slug ->
                                currentScreen = AppScreen.Detail(slug)
                            },
                            onOpenSearch = {
                                currentScreen = AppScreen.Search
                            },
                            onResumePlayback = { historyItem ->
                                currentScreen = AppScreen.Player(
                                    slug = historyItem.slug,
                                    movieTitle = historyItem.movieTitle,
                                    episodeName = historyItem.episodeName,
                                    streamUrl = historyItem.streamUrl,
                                    posterUrl = historyItem.posterUrl,
                                    subtitleUrl = null,
                                    initialPositionMs = historyItem.positionMs
                                )
                            },
                            onPlayLiveStream = { title, streamUrl, posterUrl, backups ->
                                currentScreen = AppScreen.Player(
                                    slug = "live_${System.currentTimeMillis()}",
                                    movieTitle = title,
                                    episodeName = "TRỰC TIẾP",
                                    streamUrl = streamUrl,
                                    posterUrl = posterUrl,
                                    subtitleUrl = null,
                                    initialPositionMs = 0L,
                                    backupUrls = backups
                                )
                            },
                            initialCategoryId = screen.categoryId,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    is AppScreen.Search -> {
                        SearchScreen(
                            repository = repository,
                            onMovieSelected = { slug ->
                                currentScreen = AppScreen.Detail(slug)
                            },
                            onBack = { currentScreen = AppScreen.Home },
                            onPlayLiveChannel = { ch ->
                                val allUrls = ch.allStreamUrls()
                                val primary = allUrls.firstOrNull() ?: ch.streamUrl
                                currentScreen = AppScreen.Player(
                                    slug = "live_${ch.id}",
                                    movieTitle = ch.name,
                                    episodeName = "TRỰC TIẾP",
                                    streamUrl = primary,
                                    posterUrl = ch.logoUrl,
                                    subtitleUrl = null,
                                    initialPositionMs = 0L,
                                    backupUrls = allUrls.drop(1)
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    is AppScreen.SearchQuery -> {
                        SearchScreen(
                            repository = repository,
                            onMovieSelected = { slug ->
                                currentScreen = AppScreen.Detail(slug)
                            },
                            onBack = { currentScreen = AppScreen.Home },
                            onPlayLiveChannel = { ch ->
                                val allUrls = ch.allStreamUrls()
                                val primary = allUrls.firstOrNull() ?: ch.streamUrl
                                currentScreen = AppScreen.Player(
                                    slug = "live_${ch.id}",
                                    movieTitle = ch.name,
                                    episodeName = "TRỰC TIẾP",
                                    streamUrl = primary,
                                    posterUrl = ch.logoUrl,
                                    subtitleUrl = null,
                                    initialPositionMs = 0L,
                                    backupUrls = allUrls.drop(1)
                                )
                            },
                            initialQuery = screen.query,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    is AppScreen.Detail -> {
                        MovieDetailScreen(
                            slug = screen.slug,
                            repository = repository,
                            onBack = { currentScreen = AppScreen.Home },
                            onPlayEpisode = { slug, movieTitle, episodeName, streamUrl, posterUrl, subtitleUrl, initialPosMs, episodes ->
                                currentScreen = AppScreen.Player(
                                    slug = slug,
                                    movieTitle = movieTitle,
                                    episodeName = episodeName,
                                    streamUrl = streamUrl,
                                    posterUrl = posterUrl,
                                    subtitleUrl = subtitleUrl,
                                    initialPositionMs = initialPosMs,
                                    episodes = episodes
                                )
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    is AppScreen.Player -> {
                        PlayerScreen(
                            slug = screen.slug,
                            movieTitle = screen.movieTitle,
                            episodeName = screen.episodeName,
                            streamUrl = screen.streamUrl,
                            posterUrl = screen.posterUrl,
                            subtitleUrl = screen.subtitleUrl,
                            initialPositionMs = screen.initialPositionMs,
                            backupUrls = screen.backupUrls,
                            episodes = screen.episodes,
                            repository = repository,
                            onClose = {
                                if (screen.slug.startsWith("live_launcher_")) {
                                    currentScreen = AppScreen.Home
                                    moveTaskToBack(true)
                                } else {
                                    currentScreen = if (screen.slug.startsWith("live_")) AppScreen.Home else AppScreen.Detail(screen.slug)
                                }
                            },
                            onNavigate = { targetScreen ->
                                currentScreen = targetScreen
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                    if (currentScreen !is AppScreen.Player) {
                        VoiceOverlay(
                            state = globalVoiceState,
                            onDismiss = { globalVoiceManager.cancel() }
                        )
                    }
                }
            }
        }
    }
}