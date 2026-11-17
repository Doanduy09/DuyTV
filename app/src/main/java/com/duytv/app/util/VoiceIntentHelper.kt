package com.duytv.app.util

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.duytv.app.AppScreen
import com.duytv.app.data.AppCacheManager
import com.duytv.app.data.MovieRepository
import com.duytv.app.data.TvChannel
import com.duytv.app.service.DuyVoiceAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bộ định tuyến và thực thi lệnh giọng nói dùng chung cho toàn ứng dụng (Global Voice + Player).
 * Tách khỏi PlayerScreen để bấm Micro ở Home, Chi tiết phim hay Tìm kiếm đều phản hồi mượt tại chỗ.
 */
object VoiceIntentHelper {
    private const val TAG = "VoiceIntentHelper"

    fun buildChannelPlayerScreen(ch: TvChannel): AppScreen.Player {
        val allUrls = AppCacheManager.rankHealthyStreams(ch.name, ch.allStreamUrls())
        val primary = allUrls.firstOrNull() ?: ch.streamUrl
        return AppScreen.Player(
            slug = "live_${ch.id}",
            movieTitle = ch.name,
            episodeName = "TRỰC TIẾP",
            streamUrl = primary,
            posterUrl = ch.logoUrl,
            subtitleUrl = null,
            initialPositionMs = 0L,
            backupUrls = allUrls.drop(1)
        )
    }

    fun isTvIntentQuery(rawText: String): Boolean {
        val lower = rawText.lowercase().trim()
        return lower.contains("đài") || lower.contains("kênh") ||
            lower.contains("tivi") || lower.contains("truyền hình") ||
            lower.contains("vtv") || lower.contains("htv") ||
            lower.contains("vtc") || lower.contains("thvl") ||
            lower.contains("sctv") || lower.contains("k+") ||
            lower.contains("bóng đá") || lower.contains("thể thao") ||
            lower.contains("đồng tháp") || lower.contains("vĩnh long")
    }

    fun adjustSystemVolume(context: Context, isIncrease: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val direction = if (isIncrease) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(3) {
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
        }
    }

    fun toggleSystemMute(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
    }

    fun getSystemVolumePercent(context: Context): Int {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return -1
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) return -1
            ((am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f) / max).toInt().coerceIn(0, 100)
        } catch (_: Exception) {
            -1
        }
    }

    fun setSystemVolumePercent(context: Context, percent: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) return
            val target = ((percent.coerceIn(0, 100) / 100f) * max).toInt().coerceIn(0, max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        } catch (e: Exception) {
            Log.w(TAG, "setSystemVolumePercent error: ${e.message}")
        }
    }

    fun dispatchGlobalMediaKey(context: Context, keyCode: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val now = SystemClock.uptimeMillis()
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
            am.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
        } catch (e: Exception) {
            Log.w(TAG, "dispatchGlobalMediaKey error: ${e.message}")
        }
    }

    fun switchTvInput(context: Context, inputQuery: String): String? {
        return try {
            val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager ?: return null
            val q = inputQuery.lowercase().replace(" ", "").trim()
            val inputs = tim.tvInputList
            val matched = inputs.firstOrNull { info ->
                val label = info.loadLabel(context)?.toString()?.lowercase()?.replace(" ", "").orEmpty()
                val id = info.id.lowercase()
                label.contains(q) || id.contains(q)
            } ?: return null
            val uri = TvContract.buildChannelUriForPassthroughInput(matched.id)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            matched.loadLabel(context)?.toString() ?: inputQuery.uppercase()
        } catch (e: Exception) {
            Log.w(TAG, "switchTvInput error: ${e.message}")
            null
        }
    }

    suspend fun resolveOpenMovieScreen(
        repository: MovieRepository,
        query: String
    ): Pair<String, AppScreen>? {
        val results = repository.searchMovies(query)
        val first = results.firstOrNull() ?: return null
        val detail = repository.getMovieDetail(first.slug)
        val epList = detail?.episodes?.firstOrNull()?.serverData.orEmpty()
        val ep1 = epList.firstOrNull()
        return if (ep1 != null && !ep1.linkM3u8.isNullOrBlank()) {
            first.name to AppScreen.Player(
                slug = first.slug,
                movieTitle = first.name,
                episodeName = ep1.name,
                streamUrl = ep1.linkM3u8,
                posterUrl = first.posterUrl ?: first.thumbUrl,
                subtitleUrl = null,
                initialPositionMs = 0L,
                backupUrls = ep1.backupStreams,
                episodes = epList
            )
        } else {
            first.name to AppScreen.Detail(first.slug)
        }
    }

    fun speakAndReport(
        voiceManager: VoiceAssistantManager,
        ttsAssistant: VoiceTtsFeedback,
        scope: CoroutineScope,
        message: String,
        ttsPhrase: String = message,
        onAudioDuck: (Boolean) -> Unit = {},
        onDone: (() -> Unit)? = null
    ) {
        voiceManager.reportSuccess(message)
        onAudioDuck(true)
        val executed = AtomicBoolean(false)
        val safeDone = {
            if (executed.compareAndSet(false, true)) {
                onAudioDuck(false)
                onDone?.invoke()
            }
        }
        ttsAssistant.speak(
            text = ttsPhrase,
            onStart = { onAudioDuck(true) },
            onDone = { safeDone() }
        )
        if (onDone != null) {
            scope.launch {
                delay(1200)
                safeDone()
            }
        }
    }

    fun launchAppByQuery(context: Context, query: String) {
        try {
            val pm = context.packageManager
            val q = query.lowercase().trim()
            val directPkg = when {
                q.contains("youtube music") || q.contains("yt music") -> "com.google.android.youtube.tvmusic"
                q.contains("youtube") || q.contains("you tube") -> "com.google.android.youtube.tv"
                q.contains("smarttube") || q.contains("smart tube") -> "com.liskovsoft.smarttubetv.beta"
                q.contains("netflix") -> "com.netflix.ninja"
                q.contains("ott navigator") || q.contains("ott") -> "studio.scillarium.ottnavigator"
                q.contains("xplore") || q.contains("x-plore") -> "com.lonelycatgames.Xplore"
                q.contains("kodi") -> "org.xbmc.kodi"
                q.contains("vlc") -> "org.videolan.vlc"
                q.contains("spotify") -> "com.spotify.tv.android"
                q.contains("ch play") || q.contains("google play") || q.contains("cửa hàng") -> "com.android.vending"
                q.contains("vieon") || q.contains("vie on") -> "com.vieon"
                q.contains("fpt play") || q.contains("fpt") -> "com.fptplay.activity"
                q.contains("tv360") -> "com.viettel.tv360"
                q.contains("vtvgo") || q.contains("vtv go") -> "vn.vtv.vtvgo"
                q.contains("monster tv") -> "com.monstertv.app"
                q.contains("facebook") || q.contains("fb") -> "com.facebook.katana"
                q.contains("chrome") || q.contains("trình duyệt") -> "com.android.chrome"
                q.contains("cài đặt") || q.contains("settings") -> "com.android.tv.settings"
                else -> null
            }

            var intent: Intent? = null
            if (directPkg != null) {
                intent = pm.getLeanbackLaunchIntentForPackage(directPkg) ?: pm.getLaunchIntentForPackage(directPkg)
                if (intent == null && directPkg == "com.viettel.tv360") {
                    intent = pm.getLeanbackLaunchIntentForPackage("com.viettel.tv360.tv")
                        ?: pm.getLaunchIntentForPackage("com.viettel.tv360.tv")
                }
                if (intent == null && (directPkg == "com.google.android.youtube.tv" || directPkg == "com.google.android.youtube")) {
                    val shellIntent = Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName("com.google.android.youtube.tv", "com.google.android.apps.youtube.tv.activity.ShellActivity")
                        addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    }
                    intent = if (shellIntent.resolveActivity(pm) != null) {
                        shellIntent
                    } else {
                        pm.getLeanbackLaunchIntentForPackage("com.liskovsoft.smarttubetv.beta")
                            ?: pm.getLaunchIntentForPackage("com.liskovsoft.smarttubetv.beta")
                            ?: pm.getLeanbackLaunchIntentForPackage("com.liskovsoft.videomanager")
                            ?: pm.getLaunchIntentForPackage("com.google.android.youtube")
                    }
                }
            }
            if (intent == null) {
                val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                }
                val matched = pm.queryIntentActivities(mainIntent, 0).find {
                    val label = it.loadLabel(pm).toString().lowercase()
                    label.contains(q) || q.contains(label)
                }
                if (matched != null) {
                    val pkg = matched.activityInfo.packageName
                    intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
                }
            }
            if (intent == null) {
                val stdIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val matched = pm.queryIntentActivities(stdIntent, 0).find {
                    val label = it.loadLabel(pm).toString().lowercase()
                    label.contains(q) || q.contains(label)
                }
                if (matched != null) {
                    val pkg = matched.activityInfo.packageName
                    intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
                }
            }
            if (intent == null && (q.contains("youtube") || q.contains("you tube"))) {
                intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
            }

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                Log.w(TAG, "Không tìm thấy ứng dụng phù hợp cho: $query")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi launchAppByQuery: ${e.message}", e)
        }
    }

    fun launchSettingIntent(context: Context, type: VoiceCommand.SettingType) {
        try {
            val action = when (type) {
                VoiceCommand.SettingType.WIFI -> Settings.ACTION_WIFI_SETTINGS
                VoiceCommand.SettingType.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
                VoiceCommand.SettingType.SOUND -> Settings.ACTION_SOUND_SETTINGS
                VoiceCommand.SettingType.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
                VoiceCommand.SettingType.APPS -> Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
                VoiceCommand.SettingType.DATE -> Settings.ACTION_DATE_SETTINGS
                VoiceCommand.SettingType.ACCESSIBILITY -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                VoiceCommand.SettingType.SYSTEM -> Settings.ACTION_SETTINGS
            }
            context.startActivity(Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
            } catch (_: Exception) {}
        }
    }

    fun launchYouTubeSearch(context: Context, query: String) {
        try {
            val uri = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))
            val pm = context.packageManager
            val candidatePkgs = listOf(
                "com.google.android.youtube.tv",
                "com.liskovsoft.smarttubetv.beta",
                "com.teamsmart.videomanager.tv",
                "com.google.android.youtube"
            )
            val installedPkg = candidatePkgs.find { pkg ->
                try {
                    pm.getPackageInfo(pkg, 0)
                    true
                } catch (_: Exception) {
                    false
                }
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                if (installedPkg != null) setPackage(installedPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi mở YouTube Search: ${e.message}")
        }
    }

    /**
     * Bộ xử lý hợp nhất cho Global Voice (dùng chung cho MainActivity và VoiceOverlayActivity).
     * Tách biệt rõ: lệnh mở nội dung DuyTV mới gọi [onNavigateScreen]; mọi lệnh ngoài/âm lượng/hủy
     * giữ nguyên ứng dụng hiện tại đang xem (YouTube, Launcher...) rồi báo qua [onExternalActionDone].
     */
    fun handleRecognizedVoiceCommand(
        appContext: Context,
        repository: MovieRepository,
        voiceManager: VoiceAssistantManager,
        ttsAssistant: VoiceTtsFeedback,
        scope: CoroutineScope,
        recognizedText: String,
        candidates: List<String>,
        onNavigateScreen: (target: AppScreen, ttsPhrase: String?) -> Unit,
        onSystemHome: () -> Unit,
        onSystemBack: () -> Unit,
        onExternalActionDone: () -> Unit = {}
    ) {
        fun speak(message: String, ttsPhrase: String = message, onDone: (() -> Unit)? = null) {
            speakAndReport(
                voiceManager = voiceManager,
                ttsAssistant = ttsAssistant,
                scope = scope,
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
                    onNavigateScreen(
                        buildChannelPlayerScreen(cmd.channel),
                        "Dạ, đang mở kênh ${cmd.channel.name}"
                    )
                }
                is VoiceCommand.OpenMovie -> {
                    speak("Đang tìm: ${cmd.query}", "Dạ, em đang tìm ${cmd.query}")
                    scope.launch {
                        try {
                            val resolved = resolveOpenMovieScreen(repository, cmd.query)
                            if (resolved != null) {
                                onNavigateScreen(resolved.second, "Dạ, đang mở phim ${resolved.first}")
                            } else {
                                val matchedCh = VoiceCommandParser.matchTvChannel(cmd.query, channels)
                                if (matchedCh != null) {
                                    onNavigateScreen(
                                        buildChannelPlayerScreen(matchedCh.channel),
                                        "Dạ, đang mở kênh ${matchedCh.channel.name}"
                                    )
                                } else {
                                    speak("YouTube: ${cmd.query.uppercase()}", "Dạ, em mở ${cmd.query} trên YouTube cho anh") {
                                        launchYouTubeSearch(appContext, cmd.query)
                                        onExternalActionDone()
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            speak("YouTube: ${cmd.query.uppercase()}", "Dạ, em mở ${cmd.query} trên YouTube cho anh") {
                                launchYouTubeSearch(appContext, cmd.query)
                                onExternalActionDone()
                            }
                        }
                    }
                }
                is VoiceCommand.SearchMovie -> {
                    onNavigateScreen(AppScreen.SearchQuery(cmd.query), "Dạ, đang tìm kiếm ${cmd.query}")
                }
                is VoiceCommand.BrowseCategory -> {
                    onNavigateScreen(AppScreen.CategoryHome(cmd.categoryId), "Dạ, đang mở danh mục ${cmd.categoryName}")
                }
                is VoiceCommand.OpenApp -> {
                    speak("Đang mở: ${cmd.target.uppercase()}", "Dạ, đang mở ứng dụng ${cmd.target}") {
                        launchAppByQuery(appContext, cmd.target)
                        onExternalActionDone()
                    }
                }
                is VoiceCommand.YouTubeSearch -> {
                    speak("YouTube: ${cmd.query.uppercase()}", "Dạ, đang tìm trên YouTube: ${cmd.query}") {
                        launchYouTubeSearch(appContext, cmd.query)
                        onExternalActionDone()
                    }
                }
                is VoiceCommand.OpenSetting -> {
                    speak("Đang mở cài đặt...", "Dạ, đang mở cài đặt") {
                        launchSettingIntent(appContext, cmd.settingType)
                        onExternalActionDone()
                    }
                }
                is VoiceCommand.AdjustVolume -> {
                    adjustSystemVolume(appContext, cmd.isIncrease)
                    val phrase = if (cmd.isIncrease) "Dạ, đã tăng âm lượng rồi anh nhé" else "Dạ, đã giảm âm lượng"
                    speak(if (cmd.isIncrease) "Đã tăng âm lượng (+3)" else "Đã giảm âm lượng (-3)", phrase) {
                        onExternalActionDone()
                    }
                }
                is VoiceCommand.SetVolumePercent -> {
                    setSystemVolumePercent(appContext, cmd.percent)
                    speak("Âm lượng ${cmd.percent}%", "Dạ, đã đặt âm lượng ${cmd.percent} phần trăm") {
                        onExternalActionDone()
                    }
                }
                VoiceCommand.ToggleMute -> {
                    toggleSystemMute(appContext)
                    speak("Đã bật/tắt tiếng", "Dạ, đã bật tắt tiếng") {
                        onExternalActionDone()
                    }
                }
                is VoiceCommand.SwitchHdmiInput -> {
                    val switched = switchTvInput(appContext, cmd.inputQuery)
                    if (switched != null) {
                        speak("Chuyển cổng: $switched", "Dạ, đang chuyển sang $switched") {
                            onExternalActionDone()
                        }
                    } else {
                        speak("Chưa tìm thấy cổng ${cmd.inputQuery}", "Dạ, TV chưa nhận hoặc không tìm thấy cổng ${cmd.inputQuery}") {
                            onExternalActionDone()
                        }
                    }
                }
                is VoiceCommand.GlobalMediaControl -> {
                    val keyCode = when (cmd.action) {
                        VoiceCommand.MediaKeyAction.PLAY_PAUSE -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        VoiceCommand.MediaKeyAction.NEXT -> KeyEvent.KEYCODE_MEDIA_NEXT
                        VoiceCommand.MediaKeyAction.PREVIOUS -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                        VoiceCommand.MediaKeyAction.FAST_FORWARD -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        VoiceCommand.MediaKeyAction.REWIND -> KeyEvent.KEYCODE_MEDIA_REWIND
                    }
                    dispatchGlobalMediaKey(appContext, keyCode)
                    speak("Đã điều khiển phát", "Dạ, xong rồi ạ") {
                        onExternalActionDone()
                    }
                }
                VoiceCommand.Pause, VoiceCommand.Resume -> {
                    dispatchGlobalMediaKey(appContext, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    val label = if (cmd is VoiceCommand.Pause) "Đã tạm dừng" else "Đang phát tiếp"
                    speak(label, "Dạ, $label") {
                        onExternalActionDone()
                    }
                }
                is VoiceCommand.UiClickText -> {
                    val clicked = DuyVoiceAccessibilityService.clickNodeByText(cmd.targetText)
                    if (clicked) {
                        speak("Đã bấm: ${cmd.targetText}", "Dạ, đã bấm ${cmd.targetText}") {
                            onExternalActionDone()
                        }
                    } else {
                        speak("Không thấy nút ${cmd.targetText}", "Dạ, trên màn hình không có mục ${cmd.targetText}") {
                            onExternalActionDone()
                        }
                    }
                }
                is VoiceCommand.UiScroll -> {
                    DuyVoiceAccessibilityService.scrollWindow(cmd.forward)
                    val msg = if (cmd.forward) "Đã cuộn xuống" else "Đã cuộn lên"
                    speak(msg, "Dạ, $msg") {
                        onExternalActionDone()
                    }
                }
                is VoiceCommand.SystemAction -> {
                    when (cmd.type) {
                        VoiceCommand.SystemActionType.HOME -> {
                            speak("Về trang chủ", "Dạ, về trang chủ") {
                                if (!DuyVoiceAccessibilityService.performTvGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)) {
                                    onSystemHome()
                                } else {
                                    onExternalActionDone()
                                }
                            }
                        }
                        VoiceCommand.SystemActionType.POWER -> {
                            speak("Tắt màn hình", "Dạ, tạm nghỉ màn hình") {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                                    DuyVoiceAccessibilityService.performTvGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                                ) {
                                    onExternalActionDone()
                                } else {
                                    onSystemHome()
                                }
                            }
                        }
                        VoiceCommand.SystemActionType.BACK -> {
                            speak("Quay lại", "Dạ, quay lại") {
                                if (!DuyVoiceAccessibilityService.performTvGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
                                    onSystemBack()
                                } else {
                                    onExternalActionDone()
                                }
                            }
                        }
                        VoiceCommand.SystemActionType.RECENTS -> {
                            speak("Ứng dụng gần đây", "Dạ, mở đa nhiệm") {
                                DuyVoiceAccessibilityService.performTvGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                                onExternalActionDone()
                            }
                        }
                        VoiceCommand.SystemActionType.QUICK_SETTINGS -> {
                            speak("Cài đặt nhanh", "Dạ, mở bảng điều khiển nhanh") {
                                DuyVoiceAccessibilityService.performTvGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                                onExternalActionDone()
                            }
                        }
                        VoiceCommand.SystemActionType.SCREENSHOT -> {
                            speak("Chụp màn hình", "Dạ, đã chụp màn hình") {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    DuyVoiceAccessibilityService.performTvGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                                }
                                onExternalActionDone()
                            }
                        }
                    }
                }
                is VoiceCommand.Unknown -> {
                    val rawLower = cmd.rawText.lowercase().trim()
                    if (isTvIntentQuery(rawLower)) {
                        val matched = VoiceCommandParser.matchTvChannel(rawLower, channels)
                        if (matched != null) {
                            onNavigateScreen(
                                buildChannelPlayerScreen(matched.channel),
                                "Dạ, đang mở kênh ${matched.channel.name}"
                            )
                        } else if (channels.isNotEmpty()) {
                            onNavigateScreen(
                                buildChannelPlayerScreen(channels[0]),
                                "Dạ, đang mở truyền hình cho anh"
                            )
                        } else {
                            onExternalActionDone()
                        }
                    } else if (cmd.rawText.isNotBlank()) {
                        speak("YouTube: ${cmd.rawText.uppercase()}", "Dạ, em mở nội dung này trên YouTube cho anh") {
                            launchYouTubeSearch(appContext, cmd.rawText)
                            onExternalActionDone()
                        }
                    } else {
                        speak("Chưa rõ lệnh", "Dạ, em chưa hiểu câu lệnh này") {
                            onExternalActionDone()
                        }
                    }
                }
                VoiceCommand.ToggleNightMode -> {
                    val nextMode = !AppCacheManager.getPreferredNightMode(appContext)
                    AppCacheManager.savePreferredNightMode(appContext, nextMode)
                    val msg = if (nextMode) "Chế độ dịu mắt: BẬT" else "Chế độ dịu mắt: TẮT"
                    speak(msg, "Dạ, đã ${if (nextMode) "bật chế độ dịu mắt ban đêm" else "tắt chế độ dịu mắt"}") {
                        onExternalActionDone()
                    }
                }
                else -> {
                    speak("Dùng khi đang phát video", "Dạ, lệnh này dùng khi đang phát phim hoặc tivi ạ") {
                        onExternalActionDone()
                    }
                }
            }
        }

        fun mapAiStepToCommand(step: GeminiVoiceAction): VoiceCommand? {
            val target = step.target?.lowercase()?.trim().orEmpty()
            val q = step.query?.trim().orEmpty()
            return when (step.action) {
                "open_app" -> if (target.isNotBlank() || q.isNotBlank()) VoiceCommand.OpenApp(target.ifBlank { q }) else null
                "open_setting" -> {
                    val st = when {
                        target.contains("wifi") -> VoiceCommand.SettingType.WIFI
                        target.contains("bluetooth") -> VoiceCommand.SettingType.BLUETOOTH
                        target.contains("sound") || target.contains("âm") -> VoiceCommand.SettingType.SOUND
                        target.contains("display") || target.contains("màn") -> VoiceCommand.SettingType.DISPLAY
                        target.contains("app") || target.contains("ứng dụng") -> VoiceCommand.SettingType.APPS
                        target.contains("date") || target.contains("giờ") -> VoiceCommand.SettingType.DATE
                        target.contains("access") || target.contains("trợ năng") -> VoiceCommand.SettingType.ACCESSIBILITY
                        else -> VoiceCommand.SettingType.SYSTEM
                    }
                    VoiceCommand.OpenSetting(st)
                }
                "set_volume_percent" -> target.toIntOrNull()?.let { VoiceCommand.SetVolumePercent(it.coerceIn(0, 100)) }
                "adjust_volume" -> when {
                    target.contains("mute") -> VoiceCommand.ToggleMute
                    target.contains("down") -> VoiceCommand.AdjustVolume(false)
                    else -> VoiceCommand.AdjustVolume(true)
                }
                "switch_hdmi" -> VoiceCommand.SwitchHdmiInput(target.ifBlank { q }.ifBlank { "hdmi" })
                "media_control" -> {
                    val act = when {
                        target.contains("next") -> VoiceCommand.MediaKeyAction.NEXT
                        target.contains("prev") -> VoiceCommand.MediaKeyAction.PREVIOUS
                        target.contains("fast") || target.contains("forward") -> VoiceCommand.MediaKeyAction.FAST_FORWARD
                        target.contains("rewind") -> VoiceCommand.MediaKeyAction.REWIND
                        else -> VoiceCommand.MediaKeyAction.PLAY_PAUSE
                    }
                    VoiceCommand.GlobalMediaControl(act)
                }
                "system_action" -> {
                    val sysType = when {
                        target.contains("back") -> VoiceCommand.SystemActionType.BACK
                        target.contains("power") || target.contains("lock") -> VoiceCommand.SystemActionType.POWER
                        target.contains("recent") -> VoiceCommand.SystemActionType.RECENTS
                        target.contains("quick") -> VoiceCommand.SystemActionType.QUICK_SETTINGS
                        target.contains("screen") -> VoiceCommand.SystemActionType.SCREENSHOT
                        else -> VoiceCommand.SystemActionType.HOME
                    }
                    VoiceCommand.SystemAction(sysType)
                }
                "ui_click" -> if (target.isNotBlank() || q.isNotBlank()) VoiceCommand.UiClickText(target.ifBlank { q }) else null
                "ui_scroll" -> VoiceCommand.UiScroll(forward = !target.contains("up"))
                "night_mode" -> VoiceCommand.ToggleNightMode
                else -> null
            }
        }

        suspend fun executeAiAction(aiAction: GeminiVoiceAction) {
            val steps = aiAction.actions.ifEmpty { listOf(aiAction) }
            val tts = aiAction.ttsReply
            if (steps.size > 1) {
                if (!tts.isNullOrBlank()) {
                    speak(tts, tts)
                }
                for (step in steps) {
                    val mapped = mapAiStepToCommand(step)
                    if (mapped != null) {
                        when (mapped) {
                            is VoiceCommand.SetVolumePercent -> setSystemVolumePercent(appContext, mapped.percent)
                            is VoiceCommand.AdjustVolume -> adjustSystemVolume(appContext, mapped.isIncrease)
                            VoiceCommand.ToggleMute -> toggleSystemMute(appContext)
                            is VoiceCommand.OpenApp -> launchAppByQuery(appContext, mapped.target)
                            is VoiceCommand.OpenSetting -> launchSettingIntent(appContext, mapped.settingType)
                            is VoiceCommand.SwitchHdmiInput -> switchTvInput(appContext, mapped.inputQuery)
                            is VoiceCommand.UiClickText -> DuyVoiceAccessibilityService.clickNodeByText(mapped.targetText)
                            is VoiceCommand.UiScroll -> DuyVoiceAccessibilityService.scrollWindow(mapped.forward)
                            VoiceCommand.ToggleNightMode -> {
                                val nextMode = !AppCacheManager.getPreferredNightMode(appContext)
                                AppCacheManager.savePreferredNightMode(appContext, nextMode)
                            }
                            else -> runAppCommand(mapped)
                        }
                    } else if (step.action == "play_tv") {
                        val q = step.query ?: rawQuery
                        VoiceCommandParser.matchTvChannel(q, channels)?.let {
                            onNavigateScreen(buildChannelPlayerScreen(it.channel), null)
                        }
                    } else if (step.action == "play_movie") {
                        val q = step.query ?: rawQuery
                        runAppCommand(VoiceCommand.OpenMovie(q))
                    } else if (step.action == "search_youtube") {
                        launchYouTubeSearch(appContext, step.query ?: rawQuery)
                    }
                    delay(250)
                }
                onExternalActionDone()
                return
            }

            val mapped = mapAiStepToCommand(aiAction)
            if (mapped != null) {
                if (!tts.isNullOrBlank()) {
                    speak(tts, tts) { runAppCommand(mapped) }
                } else {
                    runAppCommand(mapped)
                }
                return
            }

            when (aiAction.action) {
                "search_youtube" -> {
                    val q = aiAction.query ?: rawQuery
                    speak("YouTube: ${q.uppercase()}", aiAction.ttsReply ?: "Dạ, em mở trên YouTube cho anh") {
                        launchYouTubeSearch(appContext, q)
                        onExternalActionDone()
                    }
                }
                "play_tv" -> {
                    val q = aiAction.query ?: rawQuery
                    val matched = VoiceCommandParser.matchTvChannel(q, channels)
                    if (matched != null) {
                        onNavigateScreen(
                            buildChannelPlayerScreen(matched.channel),
                            aiAction.ttsReply ?: "Dạ, đang mở kênh ${matched.channel.name}"
                        )
                    } else {
                        runAppCommand(localCmd)
                    }
                }
                "play_movie" -> {
                    val q = aiAction.query ?: rawQuery
                    runAppCommand(VoiceCommand.OpenMovie(q))
                }
                "chat" -> {
                    val reply = aiAction.ttsReply ?: "Dạ, em nghe đây anh Duy"
                    speak(reply, reply) {
                        onExternalActionDone()
                    }
                }
                "playback_control" -> {
                    val fallbackText = aiAction.query ?: rawQuery
                    val reParsedCmd = VoiceCommandParser.parse(fallbackText, channels)
                    if (reParsedCmd !is VoiceCommand.Unknown) {
                        if (!tts.isNullOrBlank()) {
                            speak(tts, tts) { runAppCommand(reParsedCmd) }
                        } else {
                            runAppCommand(reParsedCmd)
                        }
                    } else {
                        runAppCommand(localCmd)
                    }
                }
                else -> runAppCommand(localCmd)
            }
        }

        if (localCmd !is VoiceCommand.Unknown) {
            runAppCommand(localCmd)
        } else {
            scope.launch {
                val screenSnap = DuyVoiceAccessibilityService.captureScreenContext()
                val liveContext = VoiceContext(
                    activePackage = screenSnap.activePackage,
                    screenLabels = screenSnap.visibleLabels,
                    volumePercent = getSystemVolumePercent(appContext)
                )
                val aiAction = GeminiVoiceClient.query(rawQuery, liveContext)
                if (aiAction != null) {
                    executeAiAction(aiAction)
                } else {
                    runAppCommand(localCmd)
                }
            }
        }
    }
}
