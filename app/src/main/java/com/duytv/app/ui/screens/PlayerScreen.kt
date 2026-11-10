package com.duytv.app.ui.screens

import android.app.Activity
import android.net.Uri
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import com.duytv.app.util.VoiceAssistantManager
import com.duytv.app.util.VoiceTtsFeedback
import com.duytv.app.util.VoiceState
import com.duytv.app.util.VoiceCommandParser
import com.duytv.app.util.VoiceCommand
import com.duytv.app.util.GeminiVoiceClient
import com.duytv.app.util.GeminiVoiceAction
import com.duytv.app.ui.components.VoiceOverlay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import android.media.AudioManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.media.audiofx.LoudnessEnhancer
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.PlaybackParameters
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.duytv.app.MainActivity
import com.duytv.app.data.AppCacheManager
import com.duytv.app.data.EpisodeItemDto
import com.duytv.app.data.MovieRepository
import com.duytv.app.data.TvChannel
import com.duytv.app.data.WatchHistoryManager
import com.duytv.app.ui.theme.AccentRed
import com.duytv.app.ui.theme.CardBg
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.util.Locale


enum class CinematicFilter(val title: String, val desc: String) {
    OFF("TẮT", "Màu sắc nguyên bản của luồng phát"),
    VINTAGE_MARTIAL_ARTS("🎬 CỔ TRANG KIẾM HIỆP", "Tăng tương phản & màu ấm điện ảnh cho phim TVB và kiếm hiệp"),
    VIBRANT_CINEMA("💎 ĐIỆN ẢNH SỐNG ĐỘNG", "Tăng độ bão hòa và độ trong trẻo cho phim chiếu rạp")
}

enum class VideoAspectMode(val mode: Int, val label: String, val desc: String) {
    FILL(AspectRatioFrameLayout.RESIZE_MODE_FILL, "↔️ TỰ CO GIÃN TRÀN MÀN HÌNH (100%)", "Tự co giãn theo tỷ lệ mọi màn hình, xóa sạch 100% khúc đen và giữ trọn phụ đề/tỷ số"),
    ZOOM(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "🌟 PHÓNG TRÀN VIỀN (CẮT VIỀN)", "Xóa sạch 100% viền đen, lấp kín toàn màn hình"),
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT, "📺 VỪA VẶN CHUẨN GỐC", "Giữ nguyên tỷ lệ chuẩn điện ảnh gốc")
}

private class PlayerLaunchParams(
    val slug: String,
    val movieTitle: String,
    val episodeName: String,
    val streamUrl: String,
    val posterUrl: String?,
    val subtitleUrl: String?,
    val initialPositionMs: Long,
    val backupUrls: List<String>,
    val episodes: List<EpisodeItemDto>,
    val repository: MovieRepository?,
    val onClose: () -> Unit,
    val onNavigate: ((com.duytv.app.AppScreen) -> Unit)? = null
)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    slug: String,
    movieTitle: String,
    episodeName: String,
    streamUrl: String,
    posterUrl: String? = null,
    subtitleUrl: String? = null,
    initialPositionMs: Long = 0L,
    backupUrls: List<String> = emptyList(),
    episodes: List<EpisodeItemDto> = emptyList(),
    repository: MovieRepository? = null,
    onClose: () -> Unit,
    onNavigate: ((com.duytv.app.AppScreen) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val params = remember(slug, movieTitle, episodeName, streamUrl, posterUrl, subtitleUrl, initialPositionMs, backupUrls, episodes, repository, onClose, onNavigate) {
        PlayerLaunchParams(
            slug = slug,
            movieTitle = movieTitle,
            episodeName = episodeName,
            streamUrl = streamUrl,
            posterUrl = posterUrl,
            subtitleUrl = subtitleUrl,
            initialPositionMs = initialPositionMs,
            backupUrls = backupUrls,
            episodes = episodes,
            repository = repository,
            onClose = onClose,
            onNavigate = onNavigate
        )
    }
    PlayerScreenContent(params = params, modifier = modifier)
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerScreenContent(
    params: PlayerLaunchParams,
    modifier: Modifier
) {
    val slug = params.slug
    val movieTitle = params.movieTitle
    val episodeName = params.episodeName
    val streamUrl = params.streamUrl
    val posterUrl = params.posterUrl
    val subtitleUrl = params.subtitleUrl
    val initialPositionMs = params.initialPositionMs
    val backupUrls = params.backupUrls
    val episodes = params.episodes
    val repository = params.repository
    val onClose = params.onClose
    val onNavigate = params.onNavigate

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isLive = remember(streamUrl, slug) {
        slug.startsWith("live_") || streamUrl.contains("/live/") || streamUrl.contains("akamaized.net") || streamUrl.contains("fptplay")
    }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(!isLive) }
    var showQualityMenu by remember { mutableStateOf(false) }
    var selectedQualityMode by remember { mutableStateOf("AUTO") }
    var videoResolution by remember { mutableStateOf("1080p FHD") }
    var currentPosMs by remember { mutableStateOf(initialPositionMs) }
    var voiceKeyDownTimeMs by remember { mutableLongStateOf(0L) }
    var lastVoiceTriggerTimestampMs by remember { mutableLongStateOf(0L) }
    var totalDurationMs by remember { mutableStateOf(1L) }
    var pendingSeekPosMs by remember { mutableStateOf<Long?>(null) }
    var seekDeltaText by remember { mutableStateOf("") }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var lastSeekTimestamp by remember { mutableLongStateOf(0L) }
    var seekConsecutiveCount by remember { mutableIntStateOf(0) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }

    var currentTitle by remember(movieTitle) { mutableStateOf(movieTitle) }
    var currentEpisodeName by remember(episodeName) { mutableStateOf(episodeName) }
    var currentStreamUrl by remember(streamUrl) { mutableStateOf(streamUrl) }
    var currentPosterUrl by remember(posterUrl) { mutableStateOf(posterUrl) }
    var currentGroupName by remember { mutableStateOf("") }

    // Danh sách tập phim để tự động nhảy tập tiếp theo khi hết tập (Auto-Next Episode)
    var availableEpisodes by remember(episodes) {
        mutableStateOf(MovieRepository.mergeAndGroupEpisodes(episodes))
    }
    val currentEpisodeIndex = remember(availableEpisodes, currentEpisodeName, currentStreamUrl) {
        val normCurrent = MovieRepository.normalizeEpisodeKey(currentEpisodeName)
        availableEpisodes.indexOfFirst {
            MovieRepository.normalizeEpisodeKey(it.name) == normCurrent ||
                (!it.linkM3u8.isNullOrBlank() && it.linkM3u8 == currentStreamUrl) ||
                it.backupStreams.contains(currentStreamUrl)
        }
    }

    // Nếu người dùng bấm "Tiếp tục xem" từ màn hình chính (chưa truyền sẵn danh sách tập),
    // tự động tải ngầm danh sách tập để khi hết tập hiện tại sẽ tự nhảy qua tập tiếp theo!
    LaunchedEffect(slug, isLive) {
        if (!isLive && availableEpisodes.isEmpty() && repository != null) {
            try {
                val detail = repository.getMovieDetail(slug)
                val serverEps = detail?.episodes?.firstOrNull()?.serverData ?: emptyList()
                if (serverEps.isNotEmpty()) {
                    availableEpisodes = MovieRepository.mergeAndGroupEpisodes(serverEps, detail?.episodes.orEmpty())
                }
            } catch (_: Exception) {}
        }
        if (!isLive) {
            WatchHistoryManager.saveProgress(
                context = context,
                slug = slug,
                movieTitle = currentTitle,
                episodeName = currentEpisodeName,
                streamUrl = currentStreamUrl,
                posterUrl = currentPosterUrl,
                positionMs = maxOf(1000L, initialPositionMs),
                durationMs = totalDurationMs,
                forceSave = true
            )
        }
    }

    // Danh sách kênh & index hiện tại khi xem truyền hình (-1 nếu là luồng trận bóng đá trực tiếp)
    val availableChannels = remember { AppCacheManager.activeChannels }
    val initialChIndex = remember(availableChannels, streamUrl, movieTitle) {
        availableChannels.indexOfFirst {
            it.streamUrl == streamUrl ||
            it.backupUrls.contains(streamUrl) ||
            it.name.equals(movieTitle, ignoreCase = true)
        }
    }
    var currentChannelIndex by remember { mutableIntStateOf(initialChIndex) }
    var currentBackupIndex by remember { mutableIntStateOf(0) }
    var errorRetryJob by remember { mutableStateOf<Job?>(null) }

    fun getActiveLiveUrls(): List<String> {
        if (!isLive) {
            val ep = availableEpisodes.getOrNull(currentEpisodeIndex)
            val rawVod = buildList {
                ep?.linkM3u8?.let { add(it) }
                if (ep != null && ep.slug.startsWith("cobe_ep_")) add(ep.slug)
                ep?.backupStreams?.let { addAll(it) }
                add(currentStreamUrl)
                addAll(backupUrls)
            }.filter { MovieRepository.isPlayableVodStreamOrToken(it) }.distinct()
            return rawVod.ifEmpty { listOf(currentStreamUrl) }
        }
        val ch = availableChannels.getOrNull(currentChannelIndex)
        val rawUrls = if (ch != null) {
            (ch.allStreamUrls() + backupUrls).filter { it.isNotBlank() }.distinct()
        } else {
            (listOf(streamUrl) + backupUrls).filter { it.isNotBlank() }.distinct()
        }
        return AppCacheManager.rankHealthyStreams(ch?.name ?: currentTitle, rawUrls)
    }

    LaunchedEffect(currentChannelIndex, availableChannels) {
        if (isLive && availableChannels.isNotEmpty() && currentChannelIndex in availableChannels.indices) {
            currentGroupName = availableChannels[currentChannelIndex].groupTitle
        }
    }

    // Trạng thái HUD OSD chuyển kênh truyền hình
    var channelOsdVisible by remember { mutableStateOf(false) }
    var channelOsdName by remember { mutableStateOf("") }
    var channelOsdSub by remember { mutableStateOf("") }
    var channelOsdLogo by remember { mutableStateOf<String?>(null) }
    var channelOsdJob by remember { mutableStateOf<Job?>(null) }

    // 7 Tiện Ích Thông Minh v1.5.0:
    // 1. Thanh chọn kênh ngang nhanh (showChannelRibbon)
    // 2. Khóa cảm ứng trẻ em cho máy tính bảng bé An (isTouchLocked)
    // 3. Hẹn giờ tự động tắt (sleepTimerMinutes: 0, 15, 30, 60, 90)
    // 4. Khuếch đại âm lượng phần cứng LoudnessEnhancer (audioBoostPercent: 100, 150, 200)
    // 5. Nhập phím số 0-9 nhảy kênh trên Remote TV (numberInputBuffer)
    // 6. Ghim kênh yêu thích (isCurrentChannelFav)
    var showChannelRibbon by remember { mutableStateOf(false) }
    var isTouchLocked by remember { mutableStateOf(false) }
    var showUnlockPill by remember { mutableStateOf(false) }
    var sleepTimerMinutes by remember { mutableIntStateOf(0) }
    var audioBoostPercent by remember { mutableIntStateOf(AppCacheManager.getPreferredAudioBoost(context)) }
    var loudnessEnhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }
    var numberInputBuffer by remember { mutableStateOf("") }
    var numberInputJob by remember { mutableStateOf<Job?>(null) }
    var previousVisitedChannelIndex by remember { mutableIntStateOf(-1) }
    var isScreenOffAudioMode by remember { mutableStateOf(false) }
    var isNightWarmMode by remember { mutableStateOf(AppCacheManager.getPreferredNightMode(context)) }
    var cinematicFilter by remember { mutableStateOf(CinematicFilter.OFF) }
    var isAmbilightEnabled by remember { mutableStateOf(false) }
    var isDrcVoiceEnabled by remember { mutableStateOf(false) }
    var zeroStallFailoverEnabled by remember { mutableStateOf(true) }
    var autoSkipIntroEnabled by remember { mutableStateOf(false) }
    var isHlsAdBlockEnabled by remember { mutableStateOf(com.duytv.app.util.HlsAdBlockEngine.isEnabled) }
    var adsBlockedSessionCount by remember { mutableIntStateOf(com.duytv.app.util.HlsAdBlockEngine.totalAdsBlocked.get()) }
    var activeSubtitleData by remember(subtitleUrl) { mutableStateOf(subtitleUrl) }
    var showQuickActionSheet by remember { mutableStateOf(false) }
    var quickActionTab by remember { mutableIntStateOf(0) }
    var stallTimerJob by remember { mutableStateOf<Job?>(null) }
    var isCurrentChannelFav by remember(currentTitle) {
        mutableStateOf(AppCacheManager.isFavoriteChannel(currentTitle))
    }

    DisposableEffect(Unit) {
        com.duytv.app.util.HlsAdBlockEngine.onAdBlockedCallback = { total ->
            adsBlockedSessionCount = total
        }
        onDispose {
            com.duytv.app.util.HlsAdBlockEngine.onAdBlockedCallback = null
        }
    }

    LaunchedEffect(currentTitle) {
        isCurrentChannelFav = AppCacheManager.isFavoriteChannel(currentTitle)
        if (isLive) {
            AppCacheManager.saveLastWatchedChannel(context, currentTitle)
        }
    }

    // Bộ đếm ngược Hẹn Giờ Tự Động Tắt (Sleep Timer)
    LaunchedEffect(sleepTimerMinutes) {
        if (sleepTimerMinutes > 0) {
            delay(60_000L)
            val nextMin = sleepTimerMinutes - 1
            sleepTimerMinutes = nextMin
            if (nextMin == 0) {
                onClose()
            }
        }
    }

    

    // Mặc định tự động CO GIÃN TRÀN MÀN HÌNH 100% KHÔNG KHÚC ĐEN (FILL) theo tỷ lệ mọi màn hình (#5)
    var currentAspectMode by remember {
        val savedName = AppCacheManager.getPreferredAspectModeName(context)
        val mode = VideoAspectMode.values().firstOrNull { it.name == savedName } ?: VideoAspectMode.FILL
        mutableStateOf(mode)
    }
    var aspectBadgeText by remember { mutableStateOf<String?>(null) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    val currentConfig = LocalConfiguration.current
    val screenConfig = remember(context, currentConfig.screenWidthDp, currentConfig.screenHeightDp, currentConfig.orientation) {
        com.duytv.app.util.HardwareOptimizer.detectScreenConfig(context, currentConfig.screenWidthDp, currentConfig.screenHeightDp)
    }

    // Tự động nhận diện mọi thay đổi kích thước/tỷ lệ màn hình (xoay ngang/dọc, gập/mở Z Fold, Tablet, TV)
    // để co giãn tràn 100% diện tích màn hình không bị khúc đen
    LaunchedEffect(currentConfig.screenWidthDp, currentConfig.screenHeightDp, currentConfig.orientation, currentAspectMode) {
        playerViewRef?.let { pv ->
            pv.resizeMode = currentAspectMode.mode
            pv.requestLayout()
        }
    }

    LaunchedEffect(isNightWarmMode) {
        AppCacheManager.savePreferredNightMode(context, isNightWarmMode)
    }

    fun cycleSleepTimer() {
        sleepTimerMinutes = when (sleepTimerMinutes) {
            0 -> 15
            15 -> 30
            30 -> 60
            60 -> 90
            else -> 0
        }
        aspectBadgeText = if (sleepTimerMinutes > 0) "⏲️ HẸN GIỜ TẮT: $sleepTimerMinutes PHÚT" else "⏲️ HẸN GIỜ TẮT: ĐÃ TẮT"
        scope.launch {
            delay(2200)
            aspectBadgeText = null
        }
    }

    fun cycleAspectMode() {
        val modes = VideoAspectMode.values()
        val nextIdx = (currentAspectMode.ordinal + 1) % modes.size
        currentAspectMode = modes[nextIdx]
        AppCacheManager.savePreferredAspectModeName(context, currentAspectMode.name)
        aspectBadgeText = currentAspectMode.label
        scope.launch {
            delay(2200)
            aspectBadgeText = null
        }
    }

    // Keep screen awake, Low-Latency Wi-Fi Lock (#14) & Immersive Sticky Fullscreen
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val wifiLock = try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wm?.createWifiLock(mode, "DuyTV:LiveStreamLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) { null }
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            // Cho phép vẽ tràn qua Notch / Camera nốt ruồi / Tai thỏ trên Tablet & Phone
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            // Tắt DecorFitsSystemWindows để chiếm trọn 100% diện tích màn hình
            WindowCompat.setDecorFitsSystemWindows(window, false)

            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.hide(WindowInsetsCompat.Type.navigationBars())
            insetsController.hide(WindowInsetsCompat.Type.statusBars())

            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            )

            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if ((visibility and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    window.decorView.postDelayed({
                        val insets = WindowCompat.getInsetsController(window, window.decorView)
                        insets.hide(WindowInsetsCompat.Type.systemBars())
                        insets.hide(WindowInsetsCompat.Type.navigationBars())
                        insets.hide(WindowInsetsCompat.Type.statusBars())
                    }, 2000)
                }
            }
        }
        onDispose {
            try {
                if (wifiLock?.isHeld == true) wifiLock.release()
            } catch (_: Exception) {}
            if (window != null) {
                @Suppress("DEPRECATION")
                window.decorView.setOnSystemUiVisibilityChangeListener(null)
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
        }
    }

    // Tự động tái khóa Immersive Fullscreen mỗi khi thanh điều khiển ẩn đi
    LaunchedEffect(showControls) {
        if (!showControls) {
            val window = (context as? Activity)?.window
            if (window != null) {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.hide(WindowInsetsCompat.Type.navigationBars())
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            }
        }
    }

    // Auto-hide controls timer (6 seconds)
    LaunchedEffect(showControls, isPlaying, showQuickActionSheet) {
        if (showControls && isPlaying && !showQuickActionSheet) {
            delay(6000)
            showControls = false
        }
    }

    val isMagnet = streamUrl.startsWith("magnet:?")

    val exoPlayer = remember(streamUrl) {
        if (isMagnet) null else createConfiguredExoPlayer(context, streamUrl, isLive, subtitleUrl, initialPositionMs)
    }

    if (isMagnet) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "💎 NGUỒN PHIM 4K CINEMA TORRENT REMUX",
                    color = AccentRed,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Luồng torrent này yêu cầu Debrid API (Real-Debrid / TorBox) để chuyển thành link direct siêu tốc hoặc bạn có thể chọn phát ngay qua 'Server VIP Vietsub' ở màn hình trước.",
                    color = Color.White,
                    fontSize = 15.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.width(600.dp)
                )
                Spacer(modifier = Modifier.height(24.dp))
                val backInter = remember { MutableInteractionSource() }
                val isBackFoc by backInter.collectIsFocusedAsState()

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isBackFoc) AccentRed else Color(0xFF222938))
                        .clickable(interactionSource = backInter, indication = null, onClick = onClose)
                        .padding(horizontal = 24.dp, vertical = 12.dp)
                ) {
                    Text("QUAY LẠI CHỌN SERVER VIP", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        return
    }

    if (exoPlayer == null) return

    var preWarmedEpisodeUrl by remember(currentEpisodeName) { mutableStateOf<String?>(null) }
    var hasSkippedIntroForEpisode by remember(currentStreamUrl) { mutableStateOf(false) }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.prepare()
            }
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
        showControls = true
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (exoPlayer.playbackState == Player.STATE_IDLE) {
                        exoPlayer.prepare()
                    }
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    val isInPip = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
                        (context as? Activity)?.isInPictureInPictureMode == true
                    if (isInPip) {
                        showControls = false
                    } else {
                        exoPlayer.pause()
                        exoPlayer.playWhenReady = false
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    val pos = exoPlayer.currentPosition
                    val dur = maxOf(1L, exoPlayer.duration)
                    if (!isLive) {
                        WatchHistoryManager.saveProgress(
                            context, slug, currentTitle, currentEpisodeName, currentStreamUrl, currentPosterUrl, pos, dur, forceSave = true
                        )
                    }
                    exoPlayer.stop()
                    exoPlayer.playWhenReady = false
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun showChannelOsd(channel: TvChannel, index: Int, total: Int, sourceIdx: Int = 0) {
        showControls = false
        val totalSources = getActiveLiveUrls().size.coerceAtLeast(1)
        channelOsdName = "${index + 1}. ${channel.name}"
        channelOsdSub = "Kênh ${index + 1}/$total • Nguồn ${sourceIdx + 1}/$totalSources • ${channel.groupTitle.ifBlank { "Truyền Hình FHD" }}"
        channelOsdLogo = channel.logoUrl
        channelOsdVisible = true
        channelOsdJob?.cancel()
        channelOsdJob = scope.launch {
            delay(3000)
            channelOsdVisible = false
        }
    }

    fun loadLiveStreamUrl(url: String) {
        val player = exoPlayer ?: return
        if (isLive) {
            currentStreamUrl = url
            try {
                if (!player.applicationLooper.thread.isAlive) {
                    android.util.Log.w("PlayerScreen", "ExoPlayer thread đã giải phóng, bỏ qua loadLiveStreamUrl")
                    return
                }
                val newMediaSource = buildStreamMediaSource(context, url, isLive = true, subtitleUrl = null)
                errorRetryJob?.cancel()
                player.stop()
                player.clearMediaItems()
                player.setMediaSource(newMediaSource)
                player.prepare()
                player.play()
            } catch (e: Exception) {
                android.util.Log.e("PlayerScreen", "Lỗi loadLiveStreamUrl: ${e.message}", e)
            }
        } else {
            val savedPos = maxOf(0L, player.currentPosition, currentPosMs)
            scope.launch {
                val allVodCands = getActiveLiveUrls()
                var resolvedUrl: String? = null
                val startIdx = allVodCands.indexOf(url).takeIf { it >= 0 } ?: currentBackupIndex
                for (offset in allVodCands.indices) {
                    val idx = (startIdx + offset) % allVodCands.size
                    val cand = allVodCands[idx]
                    val res = if (cand.startsWith("cobe_ep_") && repository != null) {
                        repository.resolveAnyVodCandidate(cand)
                    } else if (MovieRepository.isPlayableVodStreamOrToken(cand)) {
                        cand
                    } else null
                    if (!res.isNullOrBlank() && res != currentStreamUrl) {
                        resolvedUrl = res
                        currentBackupIndex = idx
                        break
                    } else if (!res.isNullOrBlank() && resolvedUrl == null) {
                        resolvedUrl = res
                    }
                }
                val targetUrl = resolvedUrl ?: return@launch
                currentStreamUrl = targetUrl
                try {
                    val currentSub = activeSubtitleData ?: subtitleUrl
                    val newMediaSource = buildStreamMediaSource(context, targetUrl, isLive = false, subtitleUrl = currentSub)
                    errorRetryJob?.cancel()
                    player.stop()
                    player.clearMediaItems()
                    player.setMediaSource(newMediaSource)
                    player.prepare()
                    if (savedPos > 1000L) {
                        player.seekTo(savedPos)
                    }
                    player.play()
                } catch (_: Exception) {}
            }
        }
    }

    // Track playback position, Save history mỗi 10s, Frozen Frame Watchdog (#7) & Pre-warm Next Episode (#11)
    LaunchedEffect(exoPlayer) {
        var tickCount = 0
        var lastRenderedFrames = -1
        var frozenTicks = 0
        while (true) {
            delay(1000)
            if (exoPlayer.isPlaying && pendingSeekPosMs == null) {
                val livePos = exoPlayer.currentPosition
                val liveDur = maxOf(1L, exoPlayer.duration)
                tickCount++
                // Chỉ cập nhật biến State UI khi mở HUD hoặc ở 50s cuối tập phim (giảm 98% Recomposition nền khi xem TV/Phim)
                if (!isLive && (showControls || (liveDur - livePos) <= 50_000L || tickCount % 10 == 0)) {
                    currentPosMs = livePos
                    totalDurationMs = liveDur
                }

                // #7 Cảm biến phát hiện "Đứng hình ngầm" 6s khi xem IPTV / Bóng đá / Phim VOD
                if (zeroStallFailoverEnabled && exoPlayer.playbackState == Player.STATE_READY) {
                    val rendered = exoPlayer.videoDecoderCounters?.renderedOutputBufferCount ?: -1
                    if (rendered > 0 && rendered == lastRenderedFrames) {
                        frozenTicks++
                        if (frozenTicks >= 6) {
                            frozenTicks = 0
                            val allUrls = getActiveLiveUrls()
                            if (allUrls.size > 1) {
                                if (isLive) AppCacheManager.markStreamFailed(currentStreamUrl)
                                val backupCandidates = allUrls.filterNot { it == currentStreamUrl }
                                val fastestUrl = if (isLive) {
                                    com.duytv.app.util.HlsAdBlockEngine.raceFastestHealthyStream(backupCandidates, 1100L)
                                } else null
                                val nextIdx = if (fastestUrl != null) {
                                    allUrls.indexOf(fastestUrl).takeIf { it >= 0 } ?: ((currentBackupIndex + 1) % allUrls.size)
                                } else {
                                    (currentBackupIndex + 1) % allUrls.size
                                }
                                currentBackupIndex = nextIdx
                                loadLiveStreamUrl(allUrls[nextIdx])
                            }
                        }
                    } else {
                        frozenTicks = 0
                        if (isLive && rendered > 0 && tickCount % 8 == 0) {
                            AppCacheManager.markStreamSuccess(currentTitle, currentStreamUrl)
                        }
                    }
                    lastRenderedFrames = rendered
                }

                // #11 Tự nạp trước tập tiếp theo khi còn 45 giây cuối (Pre-warm HLS Manifest & khử tập trùng)
                if (!isLive && preWarmedEpisodeUrl == null && liveDur > 120_000L &&
                    (liveDur - livePos) in 10_000L..45_000L
                ) {
                    val nextEp = availableEpisodes.getOrNull(currentEpisodeIndex + 1)
                    if (nextEp != null) {
                        preWarmedEpisodeUrl = nextEp.linkM3u8 ?: nextEp.linkEmbed ?: "ready"
                        aspectBadgeText = "⏭️ Sắp chuyển sang: ${nextEp.name}"
                        scope.launch {
                            var warmUrl = nextEp.linkM3u8 ?: ""
                            if (warmUrl.isBlank() && nextEp.slug.startsWith("cobe_ep_") && repository != null) {
                                val epId = nextEp.slug.split("_").getOrNull(2)?.toLongOrNull()
                                if (epId != null) {
                                    warmUrl = try { repository.resolveCoBeEpisode(epId) ?: "" } catch (_: Exception) { "" }
                                }
                            }
                            if (warmUrl.isNotBlank()) {
                                preWarmedEpisodeUrl = warmUrl
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    com.duytv.app.util.HlsAdBlockEngine.preWarmStreamManifest(warmUrl)
                                }
                            }
                            delay(2500)
                            aspectBadgeText = null
                        }
                    }
                }

                if (!isLive && tickCount % 10 == 0) {
                    WatchHistoryManager.saveProgress(
                        context = context,
                        slug = slug,
                        movieTitle = currentTitle,
                        episodeName = currentEpisodeName,
                        streamUrl = currentStreamUrl,
                        posterUrl = currentPosterUrl,
                        positionMs = livePos,
                        durationMs = liveDur
                    )
                }
            }
        }
    }

    fun playVodEpisodeAt(targetIndex: Int, isAutoAdvance: Boolean = false) {
        if (isLive || targetIndex !in availableEpisodes.indices) return
        val ep = availableEpisodes[targetIndex]
        val player = exoPlayer ?: return

        scope.launch {
            val rawCandidates = buildList {
                ep.linkM3u8?.let { add(it) }
                if (ep.slug.startsWith("cobe_ep_")) add(ep.slug)
                addAll(ep.backupStreams)
            }.filter { it.isNotBlank() }.distinct()
            var nextUrl = ""
            for (cand in rawCandidates) {
                val resolved = if (repository != null) repository.resolveAnyVodCandidate(cand) else cand
                if (!resolved.isNullOrBlank()) {
                    nextUrl = resolved
                    break
                }
            }
            if (nextUrl.isBlank()) return@launch

            currentEpisodeName = ep.name
            currentStreamUrl = nextUrl
            currentBackupIndex = 0
            currentPosMs = 0L
            pendingSeekPosMs = null
            seekDeltaText = ""
            errorMessage = null

            // Lưu nhớ ngay lập tức tập mới đang coi vào WatchHistoryManager
            WatchHistoryManager.saveProgress(
                context = context,
                slug = slug,
                movieTitle = currentTitle,
                episodeName = ep.name,
                streamUrl = nextUrl,
                posterUrl = currentPosterUrl,
                positionMs = 1000L,
                durationMs = 0L,
                forceSave = true
            )

            try {
                val currentSub = activeSubtitleData ?: subtitleUrl
                val newMediaSource = buildStreamMediaSource(context, nextUrl, isLive = false, subtitleUrl = currentSub)
                errorRetryJob?.cancel()
                player.stop()
                player.clearMediaItems()
                player.setMediaSource(newMediaSource)
                player.prepare()
                player.seekTo(0L)
                player.play()
            } catch (_: Exception) {}

            showControls = false
            channelOsdVisible = false
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                if (audioSessionId != C.AUDIO_SESSION_ID_UNSET && audioSessionId != 0) {
                    try {
                        loudnessEnhancer?.release()
                        loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                    } catch (_: Exception) {}
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_BUFFERING) {
                    if (zeroStallFailoverEnabled && getActiveLiveUrls().size > 1) {
                        stallTimerJob?.cancel()
                        stallTimerJob = scope.launch {
                            delay(if (isLive) 4500L else 5500L) // Chờ 4.5s (Live) / 5.5s (Phim) rồi tự động đổi sang Server dự phòng
                            val allUrls = getActiveLiveUrls()
                            if (allUrls.size > 1) {
                                if (isLive) AppCacheManager.markStreamFailed(currentStreamUrl)
                                val backupCandidates = allUrls.filterNot { it == currentStreamUrl }
                                val fastestUrl = if (isLive) {
                                    com.duytv.app.util.HlsAdBlockEngine.raceFastestHealthyStream(backupCandidates, 1100L)
                                } else null
                                val nextBackupIdx = if (fastestUrl != null) {
                                    allUrls.indexOf(fastestUrl).takeIf { it >= 0 } ?: ((currentBackupIndex + 1) % allUrls.size)
                                } else {
                                    (currentBackupIndex + 1) % allUrls.size
                                }
                                currentBackupIndex = nextBackupIdx
                                errorMessage = null
                                channelOsdVisible = false
                                loadLiveStreamUrl(allUrls[nextBackupIdx])
                            }
                        }
                    }
                } else if (playbackState == Player.STATE_READY) {
                    stallTimerJob?.cancel()
                    errorRetryJob?.cancel()
                    errorMessage = null
                    totalDurationMs = maxOf(1L, exoPlayer.duration)
                    if (isLive) {
                        AppCacheManager.markStreamSuccess(currentTitle, currentStreamUrl)
                    } else if (autoSkipIntroEnabled && !hasSkippedIntroForEpisode && availableEpisodes.size > 1) {
                        // #12 Smart Auto-Skip Intro Guard ngay khi tập phim vừa STATE_READY lần đầu
                        hasSkippedIntroForEpisode = true
                        if (exoPlayer.currentPosition < 10_000L && totalDurationMs > 600_000L) {
                            exoPlayer.seekTo(85_000L)
                            channelOsdName = currentTitle
                            channelOsdSub = "⏭️ Đã tự động bỏ qua mở đầu 85s"
                            channelOsdLogo = currentPosterUrl
                            channelOsdVisible = true
                            scope.launch {
                                delay(2500L)
                                channelOsdVisible = false
                            }
                        }
                    }
                } else if (playbackState == Player.STATE_ENDED && !isLive) {
                    val normEp = MovieRepository.normalizeEpisodeKey(currentEpisodeName)
                    val activeIdx = availableEpisodes.indexOfFirst {
                        MovieRepository.normalizeEpisodeKey(it.name) == normEp ||
                            (!it.linkM3u8.isNullOrBlank() && it.linkM3u8 == currentStreamUrl) ||
                            it.backupStreams.contains(currentStreamUrl)
                    }
                    if (activeIdx >= 0 && activeIdx + 1 < availableEpisodes.size) {
                        playVodEpisodeAt(activeIdx + 1, isAutoAdvance = true)
                    } else {
                        WatchHistoryManager.saveProgress(
                            context, slug, currentTitle, currentEpisodeName, currentStreamUrl, currentPosterUrl, 1000L, totalDurationMs, forceSave = true
                        )
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                MainActivity.notifyPlaybackState(playing)
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val width = videoSize.width
                val height = videoSize.height
                videoResolution = when {
                    width >= 11520 || height >= 6480 -> "12K Ultra HD"
                    width >= 7680 || height >= 4320 -> "8K Ultra HD"
                    width >= 5120 || height >= 2880 -> "5K / 6K Ultra HD"
                    width >= 3840 || height >= 2160 -> "4K Ultra HD"
                    width >= 2560 || height >= 1440 -> "2K QHD"
                    width >= 1920 || height >= 1080 -> "1080p FHD"
                    width >= 1280 || height >= 720 -> "720p HD"
                    else -> if (width > 0) "${width}x${height}" else "Auto HD"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    errorMessage = null
                    exoPlayer.seekToDefaultPosition()
                    exoPlayer.prepare()
                    exoPlayer.play()
                    return
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
                ) {
                    errorMessage = null
                    val savedPos = maxOf(0L, exoPlayer.currentPosition)
                    scope.launch {
                        try {
                            exoPlayer.stop()
                            delay(250L)
                            exoPlayer.prepare()
                            if (!isLive && savedPos > 0L) {
                                exoPlayer.seekTo(savedPos)
                            }
                            exoPlayer.play()
                        } catch (_: Exception) {}
                    }
                    return
                }
                val allUrls = getActiveLiveUrls()
                if (isLive) {
                    AppCacheManager.markStreamFailed(currentStreamUrl)
                }

                if (currentBackupIndex + 1 < allUrls.size) {
                    val nextBackupIdx = currentBackupIndex + 1
                    currentBackupIndex = nextBackupIdx
                    errorMessage = null
                    channelOsdVisible = false
                    loadLiveStreamUrl(allUrls[nextBackupIdx])
                } else {
                    if (!isLive) {
                        errorMessage = "Lỗi đường truyền: ${error.errorCodeName}. Đang tự động đổi Server / kết nối lại..."
                    }
                    errorRetryJob?.cancel()
                    errorRetryJob = scope.launch {
                        // #3 Smart VOD Server Failover: Tìm link tập hiện tại từ Server dự phòng nếu có
                        if (!isLive && repository != null) {
                            val altEpUrl = try {
                                val detail = AppCacheManager.getCachedMovieDetail(slug) ?: repository.getMovieDetail(slug)
                                detail?.episodes?.asSequence()
                                    ?.flatMap { it.serverData.asSequence() }
                                    ?.firstOrNull {
                                        it.name.equals(currentEpisodeName, ignoreCase = true) &&
                                            !it.linkM3u8.isNullOrBlank() &&
                                            it.linkM3u8 != currentStreamUrl
                                    }?.linkM3u8
                            } catch (_: Exception) { null }
                            if (!altEpUrl.isNullOrBlank()) {
                                val savedPos = maxOf(0L, currentPosMs)
                                errorMessage = null
                                currentStreamUrl = altEpUrl
                                val dsFactory = com.duytv.app.util.HlsAdBlockEngine.buildAdBlockingDataSourceFactory(
                                    context = context,
                                    userAgent = "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 DuyTV/1.0",
                                    headers = emptyMap()
                                )
                                val newMedia = DefaultMediaSourceFactory(dsFactory)
                                    .createMediaSource(MediaItem.Builder().setUri(altEpUrl).setMimeType(MimeTypes.APPLICATION_M3U8).build())
                                exoPlayer.stop()
                                exoPlayer.setMediaSource(newMedia)
                                exoPlayer.prepare()
                                if (savedPos > 1000L) exoPlayer.seekTo(savedPos)
                                exoPlayer.play()
                                return@launch
                            }
                        }

                        // #8 Multi-Wave Auto-Reconnect (5 nhịp liên hoàn khi rớt Wi-Fi tạm thời)
                        val backoffDelays = listOf(2500L, 4000L, 6000L, 8000L, 10000L)
                        for (waitMs in backoffDelays) {
                            delay(waitMs)
                            if (exoPlayer.playbackState == Player.STATE_READY && exoPlayer.isPlaying) break
                            if (isLive && allUrls.isNotEmpty()) {
                                currentBackupIndex = 0
                                loadLiveStreamUrl(getActiveLiveUrls().firstOrNull() ?: allUrls.first())
                            } else {
                                exoPlayer.prepare()
                                if (currentPosMs > 1000L) exoPlayer.seekTo(currentPosMs)
                                exoPlayer.play()
                            }
                        }
                    }
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            MainActivity.notifyPlaybackState(false)
            if (!isLive) {
                val pos = exoPlayer.currentPosition
                val dur = maxOf(1L, exoPlayer.duration)
                WatchHistoryManager.saveProgress(
                    context, slug, currentTitle, currentEpisodeName, currentStreamUrl, currentPosterUrl, pos, dur, forceSave = true
                )
            }
            try {
                loudnessEnhancer?.release()
            } catch (_: Exception) {}
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    fun cycleAudioBoost() {
        val nextBoost = when (audioBoostPercent) {
            100 -> 150
            150 -> 200
            else -> 100
        }
        audioBoostPercent = nextBoost
        AppCacheManager.savePreferredAudioBoost(context, nextBoost)
        try {
            val sessionId = exoPlayer.audioSessionId
            if (sessionId != C.AUDIO_SESSION_ID_UNSET && sessionId != 0) {
                if (loudnessEnhancer == null) {
                    loudnessEnhancer = LoudnessEnhancer(sessionId)
                }
                val gainmB = when (nextBoost) {
                    150 -> 600  // +6 dB
                    200 -> 1200 // +12 dB
                    else -> 0
                }
                loudnessEnhancer?.setTargetGain(gainmB)
                loudnessEnhancer?.enabled = (gainmB > 0)
            }
        } catch (_: Exception) {}
        aspectBadgeText = "🔊 KHUẾCH ĐẠI ÂM LƯỢNG: $nextBoost%"
        scope.launch {
            delay(2000)
            aspectBadgeText = null
        }
    }

    // DRC Voice & Audio Gain
    LaunchedEffect(isDrcVoiceEnabled, audioBoostPercent, loudnessEnhancer) {
        loudnessEnhancer?.let { le ->
            try {
                if (isDrcVoiceEnabled) {
                    le.setTargetGain(1200)
                    le.enabled = true
                } else if (audioBoostPercent > 100) {
                    val gainMilliBels = when (audioBoostPercent) {
                        150 -> 1000
                        200 -> 1800
                        else -> 0
                    }
                    le.setTargetGain(gainMilliBels)
                    le.enabled = (gainMilliBels > 0)
                } else {
                    le.enabled = false
                }
            } catch (_: Exception) {}
        }
    }

    // Smooth Fade-out Sleep Timer Volume Control
    LaunchedEffect(sleepTimerMinutes, exoPlayer) {
        if (exoPlayer != null) {
            if (sleepTimerMinutes == 2) {
                exoPlayer.volume = 0.6f
            } else if (sleepTimerMinutes == 1) {
                exoPlayer.volume = 0.3f
            } else if (sleepTimerMinutes == 0) {
                exoPlayer.volume = 1.0f
            }
        }
    }

    fun toggleCurrentChannelFavorite() {
        if (!isLive) return
        val nowFav = AppCacheManager.toggleFavoriteChannel(context, currentTitle)
        isCurrentChannelFav = nowFav
        aspectBadgeText = if (nowFav) "⭐ ĐÃ GHIM '$currentTitle' VÀO YÊU THÍCH" else "Đã bỏ ghim '$currentTitle'"
        scope.launch {
            delay(2200)
            aspectBadgeText = null
        }
    }

    fun applyQualitySetting(mode: String) {
        selectedQualityMode = mode
        exoPlayer.trackSelectionParameters = when (mode) {
            "12K" -> exoPlayer.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .setMinVideoSize(7680, 4320)
                .setForceHighestSupportedBitrate(true)
                .build()
            "8K" -> exoPlayer.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(7680, 4320)
                .setMinVideoSize(3840, 2160)
                .setForceHighestSupportedBitrate(true)
                .build()
            "4K" -> exoPlayer.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(3840, 2160)
                .setMinVideoSize(2560, 1440)
                .setForceHighestSupportedBitrate(true)
                .build()
            "2K" -> exoPlayer.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(2560, 1440)
                .setMinVideoSize(1920, 1080)
                .setForceHighestSupportedBitrate(true)
                .build()
            "1080P" -> exoPlayer.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(1920, 1080)
                .setMinVideoSize(1280, 720)
                .setForceHighestSupportedBitrate(true)
                .build()
            "720P" -> exoPlayer.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(1280, 720)
                .build()
            else -> exoPlayer.trackSelectionParameters.buildUpon()
                .clearVideoSizeConstraints()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .setMaxAudioBitrate(Int.MAX_VALUE)
                .setForceHighestSupportedBitrate(true)
                .build()
        }
        showQualityMenu = false
    }

    fun cyclePlaybackSpeed() {
        val nextSpeed = when (playbackSpeed) {
            1.0f -> 1.25f
            1.25f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        playbackSpeed = nextSpeed
        exoPlayer.setPlaybackSpeed(nextSpeed)
        showControls = true
    }

    fun triggerSeek(deltaSeconds: Int) {
        if (isLive) return
        val deltaMs = deltaSeconds * 1000L
        val basePos = pendingSeekPosMs ?: exoPlayer.currentPosition
        val newTarget = (basePos + deltaMs).coerceIn(0L, maxOf(0L, totalDurationMs - 1000L))
        pendingSeekPosMs = newTarget
        currentPosMs = newTarget
        showControls = true

        val diffSec = (newTarget - exoPlayer.currentPosition) / 1000
        val sign = if (diffSec >= 0) "+" else "-"
        val absSec = abs(diffSec)
        val m = absSec / 60
        val s = absSec % 60
        seekDeltaText = if (m > 0) {
            String.format(Locale.getDefault(), "%s%d:%02d", sign, m, s)
        } else {
            String.format(Locale.getDefault(), "%s%ds", sign, s)
        }

        seekJob?.cancel()
        seekJob = scope.launch {
            delay(500)
            pendingSeekPosMs?.let { finalPos ->
                exoPlayer.seekTo(finalPos)
            }
            delay(400)
            pendingSeekPosMs = null
            seekDeltaText = ""
        }
    }

    fun handleSeek(forward: Boolean, isDedicatedMediaKey: Boolean, repeatCount: Int = 0) {
        val now = System.currentTimeMillis()
        if (now - lastSeekTimestamp < 800) {
            seekConsecutiveCount++
        } else {
            seekConsecutiveCount = 0
        }
        lastSeekTimestamp = now

        val stepSeconds = if (isDedicatedMediaKey) {
            // Nút >> hoặc << chuyên dụng trên Remote TV Sharp / Android TV:
            // Nhảy siêu tốc, dứt khoát!
            when {
                repeatCount >= 10 -> 300 // Giữ chặt: 5 phút / nhịp!
                repeatCount >= 4  -> 120 // Giữ phím: 2 phút / nhịp!
                repeatCount >= 1  -> 60  // Giữ phím: 1 phút / nhịp!
                seekConsecutiveCount >= 6 -> 300 // Bấm dồn dập: 5 phút / lần!
                seekConsecutiveCount >= 4 -> 120 // Bấm dồn dập: 2 phút / lần!
                seekConsecutiveCount >= 2 -> 60  // Bấm dồn dập: 1 phút / lần!
                seekConsecutiveCount >= 1 -> 45  // Bấm phát 2: 45s
                else                      -> 30  // Bấm phát 1: 30s
            }
        } else {
            // Phím mũi tên D-Pad Trái / Phải: Tinh chỉnh
            when {
                repeatCount >= 15 -> 180 // 3 phút
                repeatCount >= 8  -> 60  // 1 phút
                repeatCount >= 3  -> 30  // 30s
                seekConsecutiveCount >= 10 -> 120 // 2 phút
                seekConsecutiveCount >= 6  -> 60  // 1 phút
                seekConsecutiveCount >= 3  -> 30  // 30s
                else                       -> 10  // 10s
            }
        }
        triggerSeek(if (forward) stepSeconds else -stepSeconds)
    }

    fun switchToChannel(targetIndex: Int) {
        val channels = availableChannels
        if (channels.isEmpty()) return
        val safeIndex = (targetIndex % channels.size + channels.size) % channels.size
        if (currentChannelIndex >= 0 && currentChannelIndex != safeIndex) {
            previousVisitedChannelIndex = currentChannelIndex
        }
        currentChannelIndex = safeIndex
        currentBackupIndex = 0
        errorMessage = null
        val ch = channels[safeIndex]
        currentTitle = ch.name
        currentPosterUrl = ch.logoUrl
        currentGroupName = ch.groupTitle

        val bestUrl = AppCacheManager.rankHealthyStreams(ch.name, ch.allStreamUrls()).firstOrNull() ?: ch.streamUrl
        loadLiveStreamUrl(bestUrl)
        showChannelOsd(ch, safeIndex, channels.size, sourceIdx = 0)
    }

    fun nextChannel() {
        val nextIdx = if (currentChannelIndex < 0) 0 else currentChannelIndex + 1
        switchToChannel(nextIdx)
    }

    fun previousChannel() {
        val prevIdx = if (currentChannelIndex < 0) availableChannels.size - 1 else currentChannelIndex - 1
        switchToChannel(prevIdx)
    }

    fun handleNumberKeyInput(digit: Int) {
        if (!isLive || availableChannels.isEmpty()) return
        // #10 Phím số 0 trên Remote TV: Đảo nhanh về kênh vừa xem trước đó (Last Channel Quick Recall)
        if (digit == 0 && numberInputBuffer.isEmpty() && previousVisitedChannelIndex in availableChannels.indices) {
            switchToChannel(previousVisitedChannelIndex)
            return
        }
        val nextBuf = if (numberInputBuffer.length >= 3) "$digit" else "$numberInputBuffer$digit"
        numberInputBuffer = nextBuf
        val chNum = nextBuf.toIntOrNull() ?: return
        val previewCh = availableChannels.getOrNull(chNum - 1)
        channelOsdName = "🔢 KÊNH SỐ: $nextBuf"
        channelOsdSub = if (previewCh != null) "Sắp chuyển tới: ${previewCh.name}" else "Tối đa ${availableChannels.size} kênh"
        channelOsdLogo = previewCh?.logoUrl
        channelOsdVisible = true
        numberInputJob?.cancel()
        numberInputJob = scope.launch {
            delay(1300)
            val targetNum = numberInputBuffer.toIntOrNull()
            numberInputBuffer = ""
            if (targetNum != null && targetNum in 1..availableChannels.size) {
                switchToChannel(targetNum - 1)
            } else {
                channelOsdVisible = false
            }
        }
    }

    fun switchBackupSource() {
        val allUrls = getActiveLiveUrls()
        if (allUrls.size <= 1) return
        val nextIdx = (currentBackupIndex + 1) % allUrls.size
        currentBackupIndex = nextIdx
        errorMessage = null
        loadLiveStreamUrl(allUrls[nextIdx])
        val ch = if (isLive) availableChannels.getOrNull(currentChannelIndex) else null
        if (ch != null) {
            showChannelOsd(ch, currentChannelIndex, availableChannels.size, sourceIdx = nextIdx)
        } else {
            showControls = false
            channelOsdName = if (isLive) currentTitle else "$currentTitle • $currentEpisodeName"
            channelOsdSub = "⚡ Đã chuyển sang Đường truyền dự phòng ${nextIdx + 1}/${allUrls.size}"
            channelOsdLogo = currentPosterUrl
            channelOsdVisible = true
            channelOsdJob?.cancel()
            channelOsdJob = scope.launch {
                delay(3500)
                channelOsdVisible = false
            }
        }
    }

    val currentShowQualityMenu by rememberUpdatedState(showQualityMenu)
    val currentShowQuickActionSheet by rememberUpdatedState(showQuickActionSheet)
    val currentShowChannelRibbon by rememberUpdatedState(showChannelRibbon)
    val currentShowControls by rememberUpdatedState(showControls)
    val currentIsTouchLocked by rememberUpdatedState(isTouchLocked)
    val currentPendingSeekPosMs by rememberUpdatedState(pendingSeekPosMs)
    val currentIsPlaying by rememberUpdatedState(isPlaying)
    val currentExoPlayer by rememberUpdatedState(exoPlayer)
    val currentAvailableChannels by rememberUpdatedState(availableChannels)
    val currentAvailableEpisodes by rememberUpdatedState(availableEpisodes)
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    val currentOnClose by rememberUpdatedState(onClose)
    val osdPlayPauseFocusRequester = remember { FocusRequester() }
    val sheetCloseFocusRequester = remember { FocusRequester() }

    val ttsAssistant = remember { VoiceTtsFeedback(context) }

    val voiceManager = remember {
        VoiceAssistantManager(context) { recognizedText ->
            val player = currentExoPlayer
            fun speakAndReport(message: String, ttsPhrase: String = message, onDone: (() -> Unit)? = null) {
                reportSuccess(message)
                player?.volume = 0.05f
                val executed = java.util.concurrent.atomic.AtomicBoolean(false)
                val safeDone = {
                    if (executed.compareAndSet(false, true)) {
                        player?.volume = 1.0f
                        onDone?.invoke()
                    }
                }
                ttsAssistant.speak(
                    text = ttsPhrase,
                    onStart = {
                        player?.volume = 0.05f
                    },
                    onDone = {
                        safeDone()
                    }
                )
                if (onDone != null) {
                    scope.launch {
                        delay(1200)
                        safeDone()
                    }
                }
            }

            val executeVoiceCommand: (VoiceCommand) -> Unit = { cmd ->
                when (cmd) {
                    is VoiceCommand.SwitchChannel -> {
                        speakAndReport("Đang mở: ${cmd.channel.name}", "Dạ, đang mở kênh ${cmd.channel.name}")
                        switchToChannel(cmd.index)
                    }
                    is VoiceCommand.OpenApp -> {
                        speakAndReport("Đang mở: ${cmd.target.uppercase()}", "Dạ, đang mở ứng dụng ${cmd.target}") {
                            launchAppByQuery(context, cmd.target)
                        }
                    }
                    is VoiceCommand.YouTubeSearch -> {
                        speakAndReport("YouTube: ${cmd.query.uppercase()}", "Dạ, đang tìm trên YouTube: ${cmd.query}") {
                            launchYouTubeSearch(context, cmd.query)
                        }
                    }
                    is VoiceCommand.AdjustVolume -> {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        val direction = if (cmd.isIncrease) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                        repeat(3) {
                            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                        }
                        if (cmd.isIncrease && audioBoostPercent < 150) {
                            audioBoostPercent = 150
                            AppCacheManager.savePreferredAudioBoost(context, 150)
                        }
                        val phrase = if (cmd.isIncrease) "Dạ, đã tăng âm lượng to rõ rồi anh nhé" else "Dạ, đã giảm âm lượng"
                        speakAndReport(if (cmd.isIncrease) "Đã tăng âm lượng (+3)" else "Đã giảm âm lượng (-3)", phrase)
                    }
                    VoiceCommand.ToggleMute -> {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                        audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                        speakAndReport("Đã bật/tắt tiếng", "Dạ, đã bật tắt tiếng")
                    }
                    is VoiceCommand.SystemAction -> {
                        when (cmd.type) {
                            VoiceCommand.SystemActionType.HOME -> {
                                speakAndReport("Về trang chủ", "Dạ, về trang chủ")
                                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_HOME)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(homeIntent)
                            }
                            VoiceCommand.SystemActionType.BACK -> {
                                speakAndReport("Quay lại", "Dạ, quay lại")
                                onClose()
                            }
                            VoiceCommand.SystemActionType.POWER -> {
                                speakAndReport("Đang tắt...", "Dạ, đang tắt tivi")
                                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                    addCategory(Intent.CATEGORY_HOME)
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(homeIntent)
                            }
                        }
                    }
                    VoiceCommand.NextChannel -> {
                        speakAndReport("Chuyển kênh tiếp theo", "Dạ, kênh tiếp theo")
                        nextChannel()
                    }
                    VoiceCommand.PreviousChannel -> {
                        speakAndReport("Quay lại kênh trước", "Dạ, quay lại kênh trước")
                        previousChannel()
                    }
                    VoiceCommand.Pause -> {
                        speakAndReport("Tạm dừng phát", "Dạ, đã tạm dừng")
                        exoPlayer.pause()
                        isPlaying = false
                    }
                    VoiceCommand.Resume -> {
                        speakAndReport("Tiếp tục phát", "Dạ, tiếp tục phát")
                        exoPlayer.play()
                        isPlaying = true
                    }
                    VoiceCommand.SwitchBackupSource -> {
                        speakAndReport("Đổi server dự phòng", "Dạ, đã đổi server")
                        switchBackupSource()
                    }
                    VoiceCommand.ToggleStretch -> {
                        val modes = VideoAspectMode.values()
                        val nextIdx = (currentAspectMode.ordinal + 1) % modes.size
                        currentAspectMode = modes[nextIdx]
                        AppCacheManager.savePreferredAspectModeName(context, currentAspectMode.name)
                        speakAndReport(currentAspectMode.label, "Dạ, đã đổi tỷ lệ màn hình")
                    }
                    is VoiceCommand.SeekTo -> {
                        if (!isLive) {
                            val safePos = cmd.positionMs.coerceIn(0L, maxOf(1L, exoPlayer.duration))
                            exoPlayer.seekTo(safePos)
                            currentPosMs = safePos
                            speakAndReport("Tua đến: ${cmd.label}", "Dạ, đang tua đến ${cmd.label}")
                        } else {
                            speakAndReport("Truyền hình không hỗ trợ tua", "Dạ, truyền hình trực tiếp không hỗ trợ tua")
                        }
                    }
                    is VoiceCommand.SeekDelta -> {
                        if (!isLive) {
                            triggerSeek(cmd.deltaSeconds)
                            speakAndReport("Tua: ${cmd.label}", "Dạ, đã tua ${cmd.label}")
                        } else {
                            speakAndReport("Truyền hình không hỗ trợ tua", "Dạ, truyền hình trực tiếp không hỗ trợ tua")
                        }
                    }
                    is VoiceCommand.SetPlaybackSpeed -> {
                        playbackSpeed = cmd.speed
                        currentExoPlayer?.setPlaybackSpeed(cmd.speed)
                        speakAndReport("Tốc độ: ${cmd.label}", "Dạ, đã chuyển tốc độ phát ${cmd.label}")
                    }
                    VoiceCommand.ToggleClearDialog -> {
                        isDrcVoiceEnabled = !isDrcVoiceEnabled
                        val msg = if (isDrcVoiceEnabled) "Lọc thoại rõ tiếng: BẬT (+12dB)" else "Lọc thoại rõ tiếng: TẮT"
                        speakAndReport(msg, "Dạ, đã ${if (isDrcVoiceEnabled) "bật lọc rõ lời thoại" else "tắt lọc lời thoại"}")
                    }
                    VoiceCommand.ToggleSubtitle -> {
                        val currentDisabled = exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                            .buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !currentDisabled)
                            .build()
                        val msg = if (currentDisabled) "Bật phụ đề" else "Tắt phụ đề"
                        speakAndReport(msg, "Dạ, đã $msg")
                    }
                    VoiceCommand.SkipIntro -> {
                        if (!isLive) {
                            triggerSeek(85)
                            speakAndReport("Bỏ qua mở đầu (+85s)", "Dạ, đã bỏ qua phần mở đầu")
                        } else {
                            speakAndReport("Truyền hình không hỗ trợ tua", "Dạ, truyền hình trực tiếp không hỗ trợ tua")
                        }
                    }
                    VoiceCommand.SwitchAudioTrack -> {
                        cycleAudioBoost()
                        speakAndReport("Khuếch đại âm lượng: $audioBoostPercent%", "Dạ, đã đổi mức khuếch đại âm lượng $audioBoostPercent phần trăm")
                    }
                    is VoiceCommand.SwitchEpisode -> {
                        if (!isLive && availableEpisodes.isNotEmpty()) {
                            val targetIdx = availableEpisodes.indexOfFirst {
                                it.name.contains("${cmd.episodeNumber}")
                            }.takeIf { it >= 0 } ?: (cmd.episodeNumber - 1).coerceIn(availableEpisodes.indices)
                            
                            if (targetIdx in availableEpisodes.indices) {
                                val ep = availableEpisodes[targetIdx]
                                speakAndReport("Đang mở: ${ep.name}", "Dạ, đang mở ${ep.name}")
                                playVodEpisodeAt(targetIdx)
                            } else {
                                speakAndReport("Không có tập ${cmd.episodeNumber}", "Dạ, phim không có tập ${cmd.episodeNumber}")
                            }
                        } else {
                            speakAndReport("Chỉ dùng khi xem phim bộ", "Dạ, lệnh này chỉ dùng khi xem phim bộ")
                        }
                    }
                    VoiceCommand.NextEpisode -> {
                        if (!isLive && availableEpisodes.isNotEmpty() && currentEpisodeIndex + 1 < availableEpisodes.size) {
                            val nextIdx = currentEpisodeIndex + 1
                            val ep = availableEpisodes[nextIdx]
                            speakAndReport("Tập tiếp: ${ep.name}", "Dạ, đang mở tập tiếp theo")
                            playVodEpisodeAt(nextIdx)
                        } else {
                            speakAndReport("Đã ở tập cuối", "Dạ, đây là tập cuối cùng rồi ạ")
                        }
                    }
                    VoiceCommand.PreviousEpisode -> {
                        if (!isLive && availableEpisodes.isNotEmpty() && currentEpisodeIndex > 0) {
                            val prevIdx = currentEpisodeIndex - 1
                            val ep = availableEpisodes[prevIdx]
                            speakAndReport("Tập trước: ${ep.name}", "Dạ, đang mở tập trước")
                            playVodEpisodeAt(prevIdx)
                        } else {
                            speakAndReport("Đã ở tập đầu tiên", "Dạ, đây là tập đầu tiên rồi ạ")
                        }
                    }
                    is VoiceCommand.BrowseCategory -> {
                        speakAndReport("Đang mở: ${cmd.categoryName}", "Dạ, đang mở danh mục ${cmd.categoryName}")
                        onNavigate?.invoke(com.duytv.app.AppScreen.CategoryHome(cmd.categoryId))
                    }
                    is VoiceCommand.OpenMovie -> {
                        speakAndReport("Đang tìm: ${cmd.query}", "Dạ, em đang tìm ${cmd.query}")
                        scope.launch {
                            try {
                                val repo = repository ?: MovieRepository()
                                val results = repo.searchMovies(cmd.query)
                                val first = results.firstOrNull()
                                if (first != null) {
                                    val detail = repo.getMovieDetail(first.slug)
                                    val epList = detail?.episodes?.firstOrNull()?.serverData.orEmpty()
                                    val ep1 = epList.firstOrNull()
                                    if (ep1 != null && !ep1.linkM3u8.isNullOrBlank()) {
                                        speakAndReport("Đang chiếu: ${first.name} - ${ep1.name}", "Dạ, đang chiếu phim ${first.name}")
                                        onNavigate?.invoke(
                                            com.duytv.app.AppScreen.Player(
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
                                        )
                                    } else {
                                        speakAndReport("Đang mở: ${first.name}", "Dạ, đang mở phim ${first.name}")
                                        onNavigate?.invoke(com.duytv.app.AppScreen.Detail(first.slug))
                                    }
                                } else {
                                    // Kiểm tra nếu là tên kênh truyền hình
                                    val chIdx = currentAvailableChannels.indexOfFirst {
                                        it.name.contains(cmd.query, ignoreCase = true)
                                    }
                                    if (chIdx >= 0) {
                                        speakAndReport("Đang mở: ${currentAvailableChannels[chIdx].name}", "Dạ, đang mở kênh ${currentAvailableChannels[chIdx].name}")
                                        switchToChannel(chIdx)
                                    } else {
                                        // Smart Fallback to YouTube: Nếu không có kênh và không có phim trong DuyTV, tự động mở YouTube!
                                        speakAndReport("YouTube: ${cmd.query.uppercase()}", "Dạ, em mở ${cmd.query} trên YouTube cho anh") {
                                            launchYouTubeSearch(context, cmd.query)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                speakAndReport("YouTube: ${cmd.query.uppercase()}", "Dạ, em mở ${cmd.query} trên YouTube cho anh") {
                                    launchYouTubeSearch(context, cmd.query)
                                }
                            }
                        }
                    }
                    is VoiceCommand.SearchMovie -> {
                        speakAndReport("Tìm kiếm: ${cmd.query}", "Dạ, đang tìm kiếm ${cmd.query}")
                        currentOnNavigate?.invoke(com.duytv.app.AppScreen.SearchQuery(cmd.query))
                    }
                    is VoiceCommand.OpenSetting -> {
                        speakAndReport("Đang mở cài đặt...", "Dạ, đang mở cài đặt")
                        launchSettingIntent(context, cmd.settingType)
                    }
                    is VoiceCommand.Unknown -> {
                        if (cmd.rawText.isNotBlank()) {
                            speakAndReport("YouTube: ${cmd.rawText.uppercase()}", "Dạ, em mở nội dung này trên YouTube cho anh") {
                                launchYouTubeSearch(context, cmd.rawText)
                            }
                        } else {
                            speakAndReport("Chưa rõ lệnh", "Dạ, em chưa hiểu câu lệnh này")
                        }
                    }
                }
            }

            val rawQuery = recognizedText.trim()
            android.util.Log.i("VoiceCommandParser", "🎤 [VOICE INPUT]: '$rawQuery'")

            // 1. Phản hồi tức thì 0ms cho các khẩu lệnh điều khiển nhanh cục bộ
            if (VoiceCommandParser.isQuickTriggerCommand(rawQuery)) {
                val cmd = VoiceCommandParser.parse(rawQuery, currentAvailableChannels)
                executeVoiceCommand(cmd)
            } else {
                // 2. Với các câu nói phức tạp: Thử suy luận qua dàn 12 tài khoản Gemini Pro (Hermes LAN Gateway)
                scope.launch {
                    val aiAction = GeminiVoiceClient.query(rawQuery)
                    if (aiAction != null) {
                        when (aiAction.action) {
                            "search_youtube" -> {
                                val q = aiAction.query ?: rawQuery
                                val tts = aiAction.ttsReply ?: "Dạ, em mở ngay trên YouTube cho anh"
                                speakAndReport("YouTube: ${q.uppercase()}", tts) {
                                    launchYouTubeSearch(context, q)
                                }
                            }
                            "play_tv" -> {
                                val q = aiAction.query ?: rawQuery
                                val chIdx = currentAvailableChannels.indexOfFirst {
                                    it.name.contains(q, ignoreCase = true)
                                }
                                if (chIdx >= 0) {
                                    val tts = aiAction.ttsReply ?: "Dạ, đang mở kênh ${currentAvailableChannels[chIdx].name}"
                                    speakAndReport("Đang mở: ${currentAvailableChannels[chIdx].name}", tts)
                                    switchToChannel(chIdx)
                                } else {
                                    executeVoiceCommand(VoiceCommandParser.parse(rawQuery, currentAvailableChannels))
                                }
                            }
                            "play_movie" -> {
                                val q = aiAction.query ?: rawQuery
                                val tts = aiAction.ttsReply ?: "Dạ, đang mở phim $q cho anh"
                                speakAndReport("Đang tìm: $q", tts)
                                executeVoiceCommand(VoiceCommand.OpenMovie(q))
                            }
                            "chat" -> {
                                val reply = aiAction.ttsReply ?: "Dạ, em nghe đây anh Duy"
                                speakAndReport(reply, reply)
                            }
                            else -> {
                                executeVoiceCommand(VoiceCommandParser.parse(rawQuery, currentAvailableChannels))
                            }
                        }
                    } else {
                        // Fallback sang bộ phân tích cục bộ 0ms nếu không thể kết nối tới máy chủ Gemini Pro
                        val cmd = VoiceCommandParser.parse(rawQuery, currentAvailableChannels)
                        executeVoiceCommand(cmd)
                    }
                }
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            voiceManager.destroy()
            ttsAssistant.destroy()
        }
    }

    // Bắt toàn diện 100% phím bấm phần cứng từ Remote Sharp TV ở cấp độ Activity
    DisposableEffect(exoPlayer) {
        MainActivity.onKeyEventListener = listener@{ event ->
            val keyCode = event.keyCode
            val action = event.action

            // 🎙️ Phím Micro trên Remote TV (KEYWORDS_VOICE_ASSIST, KEYCODE_ASSIST hoặc KEYCODE_SEARCH):
            val isVoiceKey = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST ||
                             keyCode == KeyEvent.KEYCODE_ASSIST ||
                             keyCode == KeyEvent.KEYCODE_SEARCH

            if (isVoiceKey) {
                val now = System.currentTimeMillis()
                if (action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) {
                        // Khử rung / chống dội phím (Debounce 800ms) để triệt tiêu hoàn toàn trùng lặp tín hiệu
                        if (now - lastVoiceTriggerTimestampMs < 800L) {
                            return@listener true
                        }
                        lastVoiceTriggerTimestampMs = now
                        voiceKeyDownTimeMs = now

                        // Ẩn tất cả nội dung / OSD để giao diện chuyên nghiệp, sạch sẽ
                        showControls = false
                        showChannelRibbon = false
                        showQuickActionSheet = false
                        showQualityMenu = false

                        // Bắt đầu lắng nghe ổn định - Tuyệt đối không hủy nếu đang nghe (tránh nhấp nháy UI)
                        if (voiceManager.state.value is VoiceState.Idle) {
                            voiceManager.startListening()
                        }
                    }
                } else if (action == KeyEvent.ACTION_UP) {
                    val pressDurationMs = now - voiceKeyDownTimeMs
                    // Nếu bấm giữ nói (> 350ms, Push-To-Talk) thì khi nhả nút chốt lệnh ngay lập tức (phản hồi 0ms!)
                    // Nếu bấm nhả nhanh (Tap thông thường) thì để SpeechRecognizer tự lắng nghe tự nhiên!
                    if (pressDurationMs > 350L && voiceManager.state.value is VoiceState.Listening) {
                        voiceManager.stopListening()
                    }
                }
                return@listener true
            }

            // 🎙️ Nếu Voice Overlay đang hiển thị mà bấm BACK / ESCAPE: Hủy lắng nghe
            if (voiceManager.state.value !is VoiceState.Idle) {
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (action == KeyEvent.ACTION_DOWN) {
                        voiceManager.cancel()
                    }
                    return@listener true
                }
            }

            if (currentIsTouchLocked) {
                if (action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK)) {
                    isTouchLocked = false
                    showControls = true
                }
                return@listener true
            }

            // Khi Bảng Tiện Ích, Menu Chất Lượng hoặc Thanh Chọn Kênh đang mở:
            // Nhường toàn quyền D-Pad (Lên/Xuống/Trái/Phải/OK) cho Compose Focus để điều khiển TV chọn nút mượt mà!
            val isModalOrRibbonOpen = currentShowQuickActionSheet || currentShowQualityMenu || currentShowChannelRibbon
            if (isModalOrRibbonOpen) {
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    if (action == KeyEvent.ACTION_DOWN) {
                        showQuickActionSheet = !showQuickActionSheet
                    }
                    return@listener true
                }
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (action == KeyEvent.ACTION_DOWN) {
                        when {
                            currentShowQuickActionSheet -> showQuickActionSheet = false
                            currentShowQualityMenu -> showQualityMenu = false
                            currentShowChannelRibbon -> showChannelRibbon = false
                        }
                    }
                    return@listener true
                }
                return@listener false
            }

            // Khi thanh điều khiển OSD đang hiển thị (và người dùng không đang giữ tua nhanh):
            // Nhường D-Pad cho các nút bấm trên OSD (Tập tiếp, Tạm dừng, Tiện ích, Tràn màn hình, Đổi nguồn...)
            if (currentShowControls && currentPendingSeekPosMs == null) {
                if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    if (action == KeyEvent.ACTION_DOWN) {
                        showControls = false
                    }
                    return@listener true
                }
                if (keyCode == KeyEvent.KEYCODE_MENU) {
                    if (action == KeyEvent.ACTION_DOWN) {
                        showQuickActionSheet = true
                    }
                    return@listener true
                }
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER ||
                    keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                ) {
                    return@listener false
                }
            }

            val digitPressed = when (keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }

            if (isLive && digitPressed in 0..9) {
                if (action == KeyEvent.ACTION_DOWN) {
                    handleNumberKeyInput(digitPressed)
                }
                return@listener true
            }

            val isChannelUpKey = when (keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP -> true
                KeyEvent.KEYCODE_PAGE_UP -> isLive
                KeyEvent.KEYCODE_DPAD_UP -> isLive && !currentShowQualityMenu && !currentShowChannelRibbon
                else -> false
            }

            val isChannelDownKey = when (keyCode) {
                KeyEvent.KEYCODE_CHANNEL_DOWN -> true
                KeyEvent.KEYCODE_PAGE_DOWN -> isLive
                KeyEvent.KEYCODE_DPAD_DOWN -> isLive && !currentShowQualityMenu && !currentShowChannelRibbon
                else -> false
            }

            val isDedicatedFastForward = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                KeyEvent.KEYCODE_FORWARD,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_BUTTON_R1 -> true
                KeyEvent.KEYCODE_PAGE_DOWN -> !isLive
                KeyEvent.KEYCODE_CHANNEL_UP -> !isLive
                else -> false
            }

            val isDedicatedRewind = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_BUTTON_L1 -> true
                KeyEvent.KEYCODE_PAGE_UP -> !isLive
                KeyEvent.KEYCODE_CHANNEL_DOWN -> !isLive
                else -> false
            }

            val isPlayPause = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_HEADSETHOOK,
                KeyEvent.KEYCODE_MEDIA_STOP -> true
                else -> false
            }

            when {
                isLive && isChannelDownKey -> {
                    if (action == KeyEvent.ACTION_DOWN) {
                        nextChannel()
                    }
                    true
                }
                isLive && isChannelUpKey -> {
                    if (action == KeyEvent.ACTION_DOWN) {
                        previousChannel()
                    }
                    true
                }
                isDedicatedFastForward -> {
                    if (action == KeyEvent.ACTION_DOWN) {
                        handleSeek(forward = true, isDedicatedMediaKey = true, repeatCount = event.repeatCount)
                    }
                    true
                }
                isDedicatedRewind -> {
                    if (action == KeyEvent.ACTION_DOWN) {
                        handleSeek(forward = false, isDedicatedMediaKey = true, repeatCount = event.repeatCount)
                    }
                    true
                }
                isPlayPause -> {
                    if (action == KeyEvent.ACTION_DOWN) {
                        togglePlayPause()
                    }
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !currentShowQualityMenu -> {
                    if (action == KeyEvent.ACTION_DOWN) {
                        handleSeek(forward = true, isDedicatedMediaKey = false, repeatCount = event.repeatCount)
                    }
                    true
                }
                keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !currentShowQualityMenu -> {
                    if (action == KeyEvent.ACTION_DOWN) {
                        handleSeek(forward = false, isDedicatedMediaKey = false, repeatCount = event.repeatCount)
                    }
                    true
                }
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) && !currentShowQualityMenu -> {
                    if (action == KeyEvent.ACTION_DOWN) {
                        if (currentPendingSeekPosMs != null) {
                            seekJob?.cancel()
                            exoPlayer.seekTo(currentPendingSeekPosMs!!)
                            pendingSeekPosMs = null
                            seekDeltaText = ""
                            showControls = true
                        } else {
                            togglePlayPause()
                        }
                    }
                    true
                }
                (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) && !currentShowQualityMenu -> {
                    if (action == KeyEvent.ACTION_DOWN) {
                        if (isLive) {
                            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) previousChannel() else nextChannel()
                        } else {
                            showControls = !showControls
                        }
                    }
                    true
                }
                keyCode == KeyEvent.KEYCODE_MENU -> {
                    if (action == KeyEvent.ACTION_DOWN) {
                        showQuickActionSheet = !showQuickActionSheet
                        showControls = true
                    }
                    true
                }
                keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE -> {
                    if (currentShowQuickActionSheet) {
                        if (action == KeyEvent.ACTION_DOWN) {
                            showQuickActionSheet = false
                        }
                        true
                    } else if (currentShowQualityMenu) {
                        if (action == KeyEvent.ACTION_DOWN) {
                            showQualityMenu = false
                        }
                        true
                    } else if (currentPendingSeekPosMs != null) {
                        if (action == KeyEvent.ACTION_DOWN) {
                            seekJob?.cancel()
                            pendingSeekPosMs = null
                            seekDeltaText = ""
                        }
                        true
                    } else {
                        false
                    }
                }
                else -> false
            }
        }
        onDispose {
            MainActivity.onKeyEventListener = null
        }
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(200)
        focusRequester.requestFocus()
        if (com.duytv.app.MainActivity.pendingVoiceTrigger) {
            com.duytv.app.MainActivity.pendingVoiceTrigger = false
            delay(150)
            showControls = false
            showChannelRibbon = false
            showQuickActionSheet = false
            showQualityMenu = false
            voiceManager.startListening()
        }
    }

    LaunchedEffect(showControls, showQuickActionSheet, showQualityMenu) {
        if (showQuickActionSheet) {
            delay(80)
            try { sheetCloseFocusRequester.requestFocus() } catch (_: Exception) {}
        } else if (showControls && !showQualityMenu && pendingSeekPosMs == null) {
            delay(80)
            try { osdPlayPauseFocusRequester.requestFocus() } catch (_: Exception) {}
        } else if (!showControls && !showQualityMenu) {
            delay(50)
            try { focusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .pointerInput(isTouchLocked, isLive) {
                detectTapGestures(
                    onDoubleTap = { offset ->
                        if (isTouchLocked) {
                            showUnlockPill = true
                            return@detectTapGestures
                        }
                        val widthPx = size.width.toFloat().coerceAtLeast(1f)
                        val ratioX = offset.x / widthPx
                        if (isLive) {
                            // #6 Chạm đúp thông minh khi xem Live TV / Bóng đá trên Tablet Xiaomi
                            if (ratioX < 0.32f) previousChannel()
                            else if (ratioX > 0.68f) nextChannel()
                            else cycleAspectMode()
                        } else {
                            if (ratioX < 0.32f) triggerSeek(-10)
                            else if (ratioX > 0.68f) triggerSeek(10)
                            else cycleAspectMode()
                        }
                    },
                    onTap = {
                        if (isTouchLocked) {
                            showUnlockPill = !showUnlockPill
                        } else {
                            showControls = !showControls
                        }
                    }
                )
            }
            .pointerInput(isTouchLocked) {
                if (!isTouchLocked) {
                    var dragStartX = 0f
                    detectVerticalDragGestures(
                        onDragStart = { startOffset -> dragStartX = startOffset.x },
                        onVerticalDrag = { change, dragAmount ->
                            if (kotlin.math.abs(dragAmount) > 4f) {
                                change.consume()
                                val isLeft = dragStartX < (size.width * 0.5f)
                                aspectBadgeText = adjustPlayerTouchBrightnessOrVolume(
                                    context = context,
                                    isLeftHalf = isLeft,
                                    dragDeltaY = dragAmount,
                                    screenHeightPx = size.height.toFloat().coerceAtLeast(400f)
                                )
                            }
                        },
                        onDragEnd = {
                            scope.launch {
                                delay(1200)
                                aspectBadgeText = null
                            }
                        }
                    )
                }
            }
            .onKeyEvent { event ->
                if (showQuickActionSheet || showQualityMenu || showChannelRibbon) {
                    return@onKeyEvent false
                }
                if (showControls && pendingSeekPosMs == null) {
                    val kc = event.nativeKeyEvent.keyCode
                    if (kc == KeyEvent.KEYCODE_DPAD_LEFT ||
                        kc == KeyEvent.KEYCODE_DPAD_RIGHT ||
                        kc == KeyEvent.KEYCODE_DPAD_UP ||
                        kc == KeyEvent.KEYCODE_DPAD_DOWN ||
                        kc == KeyEvent.KEYCODE_DPAD_CENTER ||
                        kc == KeyEvent.KEYCODE_ENTER ||
                        kc == KeyEvent.KEYCODE_NUMPAD_ENTER
                    ) {
                        return@onKeyEvent false
                    }
                }
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (showQualityMenu) {
                                return@onKeyEvent false
                            }
                            if (pendingSeekPosMs != null) {
                                seekJob?.cancel()
                                pendingSeekPosMs?.let { exoPlayer.seekTo(it) }
                                pendingSeekPosMs = null
                                seekDeltaText = ""
                                showControls = true
                                return@onKeyEvent true
                            }
                            togglePlayPause()
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            if (!showQualityMenu) {
                                val isDedicated = event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
                                handleSeek(forward = false, isDedicatedMediaKey = isDedicated, repeatCount = event.nativeKeyEvent.repeatCount)
                                return@onKeyEvent true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            if (!showQualityMenu) {
                                val isDedicated = event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                                handleSeek(forward = true, isDedicatedMediaKey = isDedicated, repeatCount = event.nativeKeyEvent.repeatCount)
                                return@onKeyEvent true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (isLive && !showQualityMenu) {
                                previousChannel()
                                return@onKeyEvent true
                            }
                            showControls = !showControls
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (isLive && !showQualityMenu) {
                                nextChannel()
                                return@onKeyEvent true
                            }
                            showControls = !showControls
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_CHANNEL_UP -> {
                            if (isLive) {
                                previousChannel()
                                return@onKeyEvent true
                            }
                        }
                        KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                            if (isLive) {
                                nextChannel()
                                return@onKeyEvent true
                            }
                        }
                        KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_SEARCH -> {
                            showControls = false
                            showChannelRibbon = false
                            showQuickActionSheet = false
                            showQualityMenu = false
                            val vs = voiceManager.state.value
                            if (vs is VoiceState.Idle) {
                                voiceManager.startListening()
                            } else if (vs is VoiceState.Listening) {
                                voiceManager.stopListening()
                            }
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_WINDOW,
                        KeyEvent.KEYCODE_PROG_GREEN,
                        KeyEvent.KEYCODE_CAPTIONS,
                        KeyEvent.KEYCODE_INFO -> {
                            cycleAspectMode()
                            showControls = true
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_MENU -> {
                            showQuickActionSheet = !showQuickActionSheet
                            showControls = true
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                            if (voiceManager.state.value !is VoiceState.Idle) {
                                voiceManager.cancel()
                                return@onKeyEvent true
                            }
                            if (showQuickActionSheet) {
                                showQuickActionSheet = false
                                return@onKeyEvent true
                            }
                            if (showQualityMenu) {
                                showQualityMenu = false
                                return@onKeyEvent true
                            }
                            onClose()
                            return@onKeyEvent true
                        }
                    }
                }
                false
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = currentAspectMode.mode
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    playerViewRef = this
                }
            },
            update = { pv ->
                pv.player = exoPlayer
                pv.resizeMode = currentAspectMode.mode
                pv.subtitleView?.let { subView ->
                    if (currentAspectMode == VideoAspectMode.ZOOM) {
                        subView.setBottomPaddingFraction(0.12f)
                    } else {
                        subView.setBottomPaddingFraction(0.04f)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 🌌 Ánh Sáng Viền Vô Cực (Virtual Ambilight Glow)
        if (isAmbilightEnabled && !isScreenOffAudioMode) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color(0x44FFD54F), Color.Transparent)
                            )
                        )
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0x44FFD54F))
                            )
                        )
                )
            }
        }

        // 🎬 Bộ Lọc Màu & Độ Nét Điện Ảnh (Cinematic Filter)
        if (cinematicFilter == CinematicFilter.VINTAGE_MARTIAL_ARTS && !isScreenOffAudioMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x15FFA000))
            )
        } else if (cinematicFilter == CinematicFilter.VIBRANT_CINEMA && !isScreenOffAudioMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x1000E5FF))
            )
        }

        // 📺 OSD BANNER KHI BẤM PHÍM LÊN / XUỐNG ĐỂ CHUYỂN KÊNH TRUYỀN HÌNH
        AnimatedVisibility(
            visible = channelOsdVisible && !showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 40.dp, start = 48.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xF00A0E17))
                    .border(2.dp, AccentRed, RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp)
            ) {
                if (!channelOsdLogo.isNullOrBlank()) {
                    AsyncImage(
                        model = channelOsdLogo,
                        contentDescription = null,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                } else {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(CardBg, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📺", fontSize = 24.sp)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                }

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = channelOsdName,
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .background(AccentRed, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(if (isLive) "LIVE" else "TẬP MỚI", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = channelOsdSub,
                        color = Color(0xFF00E676),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // HUD Pill Badge hiển thị khi đổi tỷ lệ màn hình (Double-Tap hoặc bấm nút)
        AnimatedVisibility(
            visible = aspectBadgeText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 80.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xEE111827))
                    .border(1.5.dp, Color(0xFF00E676), RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = aspectBadgeText ?: "",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (aspectBadgeText == currentAspectMode.label) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = currentAspectMode.desc,
                            color = Color(0xFFA0AAB8),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }

        if (isBuffering && errorMessage == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentRed, modifier = Modifier.size(54.dp))
            }
        }

        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xE60A0E17), RoundedCornerShape(8.dp))
                    .padding(20.dp)
            ) {
                Text(
                    text = errorMessage ?: "",
                    color = AccentRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        val activeChannelTotalSources = getActiveLiveUrls().size.coerceAtLeast(1)
        // ⚙️ BẢNG ĐIỀU KHIỂN TIỆN ÍCH HỢP NHẤT (UNIFIED QUICK-ACTION SHEET)
        if (showQuickActionSheet) {
            PlayerQuickActionSheet(
                quickActionTab = quickActionTab,
                onSelectTab = { quickActionTab = it },
                cinematicFilter = cinematicFilter,
                onSelectCinematicFilter = { cinematicFilter = it },
                isAmbilightEnabled = isAmbilightEnabled,
                onSetAmbilight = { isAmbilightEnabled = it },
                currentAspectMode = currentAspectMode,
                onSelectAspectMode = { currentAspectMode = it },
                isNightWarmMode = isNightWarmMode,
                onSetNightWarmMode = { isNightWarmMode = it },
                selectedQualityMode = selectedQualityMode,
                onSelectQuality = { applyQualitySetting(it) },
                audioBoostPercent = audioBoostPercent,
                isDrcVoiceEnabled = isDrcVoiceEnabled,
                onSelectAudioBoost = { boost ->
                    isDrcVoiceEnabled = false
                    audioBoostPercent = boost
                },
                onSetDrcVoice = { isDrcVoiceEnabled = it },
                sleepTimerMinutes = sleepTimerMinutes,
                onSelectSleepTimer = { sleepTimerMinutes = it },
                onActivateScreenOffAudio = {
                    isScreenOffAudioMode = true
                    showQuickActionSheet = false
                    showControls = false
                },
                isHlsAdBlockEnabled = isHlsAdBlockEnabled,
                adsBlockedSessionCount = adsBlockedSessionCount,
                onSetHlsAdBlock = { enabled ->
                    isHlsAdBlockEnabled = enabled
                    com.duytv.app.util.HlsAdBlockEngine.isEnabled = enabled
                },
                playbackSpeed = playbackSpeed,
                onSelectPlaybackSpeed = { spd ->
                    playbackSpeed = spd
                    exoPlayer.playbackParameters = PlaybackParameters(spd)
                },
                zeroStallFailoverEnabled = zeroStallFailoverEnabled,
                onSetZeroStallFailover = { zeroStallFailoverEnabled = it },
                autoSkipIntroEnabled = autoSkipIntroEnabled,
                onSetAutoSkipIntro = { autoSkipIntroEnabled = it },
                isLive = isLive,
                activeChannelTotalSources = activeChannelTotalSources,
                currentBackupIndex = currentBackupIndex,
                onSwitchBackupSource = { switchBackupSource() },
                onActivateTouchLock = {
                    isTouchLocked = true
                    showQuickActionSheet = false
                    showControls = false
                    showUnlockPill = true
                },
                closeFocusRequester = sheetCloseFocusRequester,
                onCloseSheet = { showQuickActionSheet = false }
            )
        }

        // HUD Overlay Controls
        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        val hudHorizontalPad = if (screenWidthDp < 600) 16.dp else if (screenWidthDp < 900) 24.dp else 48.dp
        val hudVerticalPad = if (screenWidthDp < 600) 14.dp else 28.dp

        // 🌆 Lớp Lọc Ánh Sáng Xanh Ban Đêm (Night Warm Eye-Care Filter)
        if (isNightWarmMode && !isScreenOffAudioMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x28FF8F00))
            )
        }

        // ⏭️ Thẻ nổi "Sắp Qua Tập Tiếp Theo" ở 20 giây cuối của tập phim
        val hasNextEpisode = !isLive && availableEpisodes.size > 1 && currentEpisodeIndex >= 0 && currentEpisodeIndex + 1 < availableEpisodes.size
        val isFinal20Seconds = hasNextEpisode && totalDurationMs > 120_000L && (totalDurationMs - currentPosMs) in 1000L..20_000L
        AnimatedVisibility(
            visible = isFinal20Seconds && !showControls && !isScreenOffAudioMode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 44.dp, end = 48.dp)
        ) {
            val nextEpTitle = availableEpisodes.getOrNull(currentEpisodeIndex + 1)?.name ?: ""
            val remainSec = ((totalDurationMs - currentPosMs) / 1000L).coerceAtLeast(1L)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xF00A0E17))
                    .border(2.dp, Color(0xFF00E676), RoundedCornerShape(12.dp))
                    .clickable {
                        playVodEpisodeAt(currentEpisodeIndex + 1, isAutoAdvance = false)
                    }
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Text(
                    text = "⏭ QUA TẬP TIẾP ($nextEpTitle) SAU ${remainSec}S — BẤM ĐỂ PHÁT NGAY",
                    color = Color(0xFF00E676),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        // 🌙 Chế độ Tắt Màn Hình Chỉ Nghe Tiếng (Tiết kiệm điện TV & chống chói mắt cho bé An)
        if (isScreenOffAudioMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable {
                        isScreenOffAudioMode = false
                        showControls = true
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                Text(
                    text = "🌙 ĐANG TẮT MÀN HÌNH (CHỈ NGHE ÂM THANH) • Chạm hoặc bấm phím bất kỳ để bật lại",
                    color = Color(0x55FFFFFF),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 24.dp)
                )
            }
        }

        // 🔓 Nút Mở Khóa Màn Hình Cảm Ứng (Chế độ Khóa Trẻ Em trên Tablet / Điện thoại)
        AnimatedVisibility(
            visible = isTouchLocked && showUnlockPill,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 36.dp, start = 36.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0xE6101622))
                    .border(2.dp, Color(0xFFFFD600), RoundedCornerShape(24.dp))
                    .clickable {
                        isTouchLocked = false
                        showUnlockPill = false
                        showControls = true
                    }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "🔓 BẤM VÀO ĐÂY ĐỂ MỞ KHÓA MÀN HÌNH",
                    color = Color(0xFFFFD600),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }

        AnimatedVisibility(
            visible = showControls && !showQualityMenu && !showQuickActionSheet && !isTouchLocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color(0xCC000000), Color.Transparent, Color(0xCC000000))
                        )
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        showControls = false
                    }
            ) {
                // Top Header info
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = hudHorizontalPad, vertical = hudVerticalPad),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        val exitInter = remember { MutableInteractionSource() }
                        val isExitFoc by exitInter.collectIsFocusedAsState()
                        val isExitPress by exitInter.collectIsPressedAsState()
                        val isExitActive = isExitFoc || isExitPress
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isExitActive) AccentRed else Color(0xFF222938))
                                .border(
                                    width = if (isExitActive) 1.5.dp else 1.dp,
                                    color = if (isExitActive) Color.White else Color(0x44FFFFFF),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable(interactionSource = exitInter, indication = null, onClick = onClose)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Thoát",
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "THOÁT",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = currentTitle,
                                color = Color.White,
                                fontSize = if (screenWidthDp < 600) 16.sp else 22.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isLive) "🔴 TRUYỀN HÌNH TRỰC TIẾP ${if (currentGroupName.isNotBlank()) "• $currentGroupName" else ""} • $videoResolution • ${screenConfig.screenSummaryBadge}" else "Đang phát: $currentEpisodeName ${if (availableEpisodes.size > 1 && currentEpisodeIndex >= 0) "(${currentEpisodeIndex + 1}/${availableEpisodes.size})" else ""} • $videoResolution • ${screenConfig.screenSummaryBadge}",
                                color = Color(0xFFA0AAB8),
                                fontSize = if (screenWidthDp < 600) 11.sp else 14.sp,
                                maxLines = 1
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        if (!isLive) {
                            val adInter = remember { MutableInteractionSource() }
                            val isAdFoc by adInter.collectIsFocusedAsState()
                            val isAdPress by adInter.collectIsPressedAsState()
                            val isAdActive = isAdFoc || isAdPress
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isAdActive) AccentRed else if (isHlsAdBlockEnabled) Color(0xFF0D3B2E) else Color(0xFF222938))
                                    .border(1.5.dp, if (isAdActive) Color.White else if (isHlsAdBlockEnabled) Color(0xFF00E676) else Color(0x44FFFFFF), RoundedCornerShape(6.dp))
                                    .clickable(interactionSource = adInter, indication = null) {
                                        val nextAdState = !isHlsAdBlockEnabled
                                        isHlsAdBlockEnabled = nextAdState
                                        com.duytv.app.util.HlsAdBlockEngine.isEnabled = nextAdState
                                        aspectBadgeText = if (nextAdState) "🛡️ ĐÃ BẬT LÁ CHẮN CHẶN 100% QUẢNG CÁO GIỮA PHIM" else "Đã tắt chặn quảng cáo"
                                        scope.launch {
                                            delay(2200)
                                            aspectBadgeText = null
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isHlsAdBlockEnabled) "🛡️ DIỆT QC${if (adsBlockedSessionCount > 0) " ($adsBlockedSessionCount)" else ": BẬT"}" else "🛡️ DIỆT QC: TẮT",
                                    color = if (isHlsAdBlockEnabled) Color(0xFF00E676) else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }

                        if (!activeSubtitleData.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .background(Color(0xFF00E676), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "💬 PHỤ ĐỀ TIẾNG VIỆT",
                                    color = Color.Black,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }

                        if (isLive) {
                            val favInter = remember { MutableInteractionSource() }
                            val isFavFoc by favInter.collectIsFocusedAsState()
                            val isFavPress by favInter.collectIsPressedAsState()
                            val isFavActive = isFavFoc || isFavPress
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isFavActive) AccentRed else if (isCurrentChannelFav) Color(0xFF3E2723) else Color(0xFF222938))
                                    .border(
                                        width = if (isFavActive || isCurrentChannelFav) 1.5.dp else 0.dp,
                                        color = if (isFavActive) Color.White else if (isCurrentChannelFav) Color(0xFFFFD600) else Color.Transparent,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .clickable(interactionSource = favInter, indication = null) {
                                        toggleCurrentChannelFavorite()
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = if (isCurrentChannelFav) "★ ĐÃ GHIM YÊU THÍCH" else "☆ GHIM KÊNH",
                                    color = if (isCurrentChannelFav) Color(0xFFFFD600) else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        val utilInter = remember { MutableInteractionSource() }
                        val isUtilFoc by utilInter.collectIsFocusedAsState()
                        val isUtilPress by utilInter.collectIsPressedAsState()
                        val isUtilActive = isUtilFoc || isUtilPress
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isUtilActive) AccentRed else Color(0xFF6200EA))
                                .border(1.5.dp, if (isUtilActive) Color.White else Color(0xFFBB86FC), RoundedCornerShape(6.dp))
                                .clickable(interactionSource = utilInter, indication = null) {
                                    showQuickActionSheet = true
                                }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "⚙️ TIỆN ÍCH",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                // Center Play/Pause indicator or Fast Seeking Bubble
                if (pendingSeekPosMs != null && seekDeltaText.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xE6101622))
                            .border(1.5.dp, AccentRed, RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                seekJob?.cancel()
                                pendingSeekPosMs?.let { exoPlayer.seekTo(it) }
                                pendingSeekPosMs = null
                                seekDeltaText = ""
                            }
                            .padding(horizontal = 32.dp, vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (seekDeltaText.startsWith("+")) "⏩ $seekDeltaText" else "⏪ $seekDeltaText",
                                color = Color.White,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Tới: ${formatTime(currentPosMs)} / ${formatTime(totalDurationMs)}",
                                color = Color(0xFF00E676),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "(Bấm OK để xem ngay)",
                                color = Color(0xFFA0AAB8),
                                fontSize = 11.sp
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier.align(Alignment.Center),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier
                                .size(72.dp)
                                .background(Color(0x66000000), RoundedCornerShape(36.dp))
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    togglePlayPause()
                                }
                                .padding(16.dp)
                        )
                    }
                }

                // Bottom Timeline and D-pad remote guide
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = hudHorizontalPad, vertical = hudVerticalPad)
                ) {
                    if (isLive && showChannelRibbon && availableChannels.isNotEmpty()) {
                        val ribbonState = rememberLazyListState(
                            initialFirstVisibleItemIndex = currentChannelIndex.coerceAtLeast(0)
                        )
                        LazyRow(
                            state = ribbonState,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            itemsIndexed(availableChannels) { idx, ch ->
                                val isCurrentCh = idx == currentChannelIndex
                                val chInter = remember { MutableInteractionSource() }
                                val isChFoc by chInter.collectIsFocusedAsState()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when {
                                                isChFoc -> AccentRed
                                                isCurrentCh -> Color(0xFF00C853)
                                                else -> Color(0xE6161C28)
                                            }
                                        )
                                        .border(
                                            width = if (isChFoc || isCurrentCh) 2.dp else 1.dp,
                                            color = if (isChFoc || isCurrentCh) Color.White else Color(0x33FFFFFF),
                                            shape = RoundedCornerShape(10.dp)
                                        )
                                        .clickable(interactionSource = chInter, indication = null) {
                                            switchToChannel(idx)
                                        }
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "${idx + 1}. ${ch.name}",
                                        color = if (isCurrentCh && !isChFoc) Color.Black else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isLive) {
                            if (availableChannels.isNotEmpty()) {
                                QuickSeekPill(
                                    text = if (showChannelRibbon) "🔽 ẨN DANH SÁCH KÊNH" else "📋 DANH SÁCH KÊNH (${availableChannels.size})",
                                    highlight = showChannelRibbon,
                                    onClick = { showChannelRibbon = !showChannelRibbon }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                if (previousVisitedChannelIndex in availableChannels.indices && previousVisitedChannelIndex != currentChannelIndex) {
                                    QuickSeekPill(
                                        text = "🔁 KÊNH VỪA NÃY (${availableChannels[previousVisitedChannelIndex].name})",
                                        highlight = true,
                                        onClick = { switchToChannel(previousVisitedChannelIndex) }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                QuickSeekPill(text = "⏮ KÊNH TRƯỚC", onClick = { previousChannel() })
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            QuickSeekPill(
                                text = if (isPlaying) "⏸️ TẠM DỪNG" else "▶️ PHÁT",
                                highlight = true,
                                modifier = Modifier.focusRequester(osdPlayPauseFocusRequester),
                                onClick = { togglePlayPause() }
                            )
                            if (availableChannels.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(8.dp))
                                QuickSeekPill(text = "⏭ KÊNH TIẾP", onClick = { nextChannel() })
                            }
                            if (activeChannelTotalSources > 1) {
                                Spacer(modifier = Modifier.width(12.dp))
                                QuickSeekPill(
                                    text = "🔄 ĐỔI NGUỒN (${currentBackupIndex + 1}/$activeChannelTotalSources)",
                                    highlight = true,
                                    onClick = { switchBackupSource() }
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            QuickSeekPill(
                                text = when (currentAspectMode) {
                                    VideoAspectMode.ZOOM -> "🌟 TRÀN MÀN HÌNH (100%)"
                                    VideoAspectMode.FILL -> "↔️ CO GIÃN (FILL)"
                                    VideoAspectMode.FIT -> "📺 CHUẨN GỐC (FIT)"
                                },
                                highlight = (currentAspectMode == VideoAspectMode.ZOOM),
                                onClick = { cycleAspectMode() }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            QuickSeekPill(
                                text = "🎙️ GIỌNG NÓI",
                                highlight = voiceManager.state.value !is VoiceState.Idle,
                                onClick = { voiceManager.startListening() }
                            )
                        } else {
                            if (availableEpisodes.size > 1 && currentEpisodeIndex > 0) {
                                QuickSeekPill(
                                    text = "⏮ TẬP TRƯỚC",
                                    highlight = true,
                                    onClick = { playVodEpisodeAt(currentEpisodeIndex - 1, isAutoAdvance = false) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            if (currentPosMs < 180_000L && totalDurationMs > 600_000L) {
                                QuickSeekPill(
                                    text = "⏩ BỎ QUA MỞ ĐẦU (+90s)",
                                    highlight = true,
                                    onClick = { triggerSeek(90) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            QuickSeekPill(text = "⏪ -5p", onClick = { triggerSeek(-300) })
                            Spacer(modifier = Modifier.width(8.dp))
                            QuickSeekPill(text = "⏪ -1p", onClick = { triggerSeek(-60) })
                            Spacer(modifier = Modifier.width(8.dp))
                            QuickSeekPill(text = "⏪ -10s", onClick = { triggerSeek(-10) })
                            Spacer(modifier = Modifier.width(12.dp))
                            QuickSeekPill(
                                text = if (isPlaying) "⏸️ TẠM DỪNG" else "▶️ PHÁT",
                                highlight = true,
                                modifier = Modifier.focusRequester(osdPlayPauseFocusRequester),
                                onClick = { togglePlayPause() }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            QuickSeekPill(text = "⏩ +10s", onClick = { triggerSeek(10) })
                            Spacer(modifier = Modifier.width(8.dp))
                            QuickSeekPill(text = "⏩ +1p", onClick = { triggerSeek(60) })
                            Spacer(modifier = Modifier.width(8.dp))
                            QuickSeekPill(text = "⏩ +5p", onClick = { triggerSeek(300) })
                            if (availableEpisodes.size > 1 && currentEpisodeIndex >= 0 && currentEpisodeIndex + 1 < availableEpisodes.size) {
                                Spacer(modifier = Modifier.width(8.dp))
                                QuickSeekPill(
                                    text = "⏭ TẬP TIẾP (${availableEpisodes[currentEpisodeIndex + 1].name})",
                                    highlight = true,
                                    onClick = { playVodEpisodeAt(currentEpisodeIndex + 1, isAutoAdvance = false) }
                                )
                            }
                            if (activeChannelTotalSources > 1) {
                                Spacer(modifier = Modifier.width(12.dp))
                                QuickSeekPill(
                                    text = "🔄 ĐỔI NGUỒN (${currentBackupIndex + 1}/$activeChannelTotalSources)",
                                    highlight = true,
                                    onClick = { switchBackupSource() }
                                )
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            QuickSeekPill(
                                text = "⚡ ${if (playbackSpeed == 1.0f) "1.0x" else "${playbackSpeed}x"}",
                                onClick = { cyclePlaybackSpeed() }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            QuickSeekPill(
                                text = when (currentAspectMode) {
                                    VideoAspectMode.ZOOM -> "🌟 TRÀN MÀN HÌNH (100%)"
                                    VideoAspectMode.FILL -> "↔️ CO GIÃN (FILL)"
                                    VideoAspectMode.FIT -> "📺 CHUẨN GỐC (FIT)"
                                },
                                highlight = (currentAspectMode == VideoAspectMode.ZOOM),
                                onClick = { cycleAspectMode() }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            QuickSeekPill(
                                text = "🎙️ GIỌNG NÓI",
                                highlight = voiceManager.state.value !is VoiceState.Idle,
                                onClick = { voiceManager.startListening() }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isLive) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(Color(0xFFFF1744), CircleShape)
                            )
                            Text(
                                text = "🔴 ĐANG PHÁT TRỰC TIẾP (LIVE STREAM)",
                                color = Color(0xFFFF5252),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "TRỰC TIẾP TỰ ĐỘNG CHUẨN FHD",
                                color = Color(0xFFA0AAB8),
                                fontSize = 12.sp
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTime(currentPosMs),
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = formatTime(totalDurationMs),
                                color = Color(0xFFA0AAB8),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        val progress = if (totalDurationMs > 0) {
                            (currentPosMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        Slider(
                            value = progress,
                            onValueChange = { newProg ->
                                val newPos = (newProg * totalDurationMs).toLong()
                                currentPosMs = newPos
                                exoPlayer.seekTo(newPos)
                                showControls = true
                            },
                            colors = SliderDefaults.colors(
                                thumbColor = AccentRed,
                                activeTrackColor = AccentRed,
                                inactiveTrackColor = Color(0x66FFFFFF)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GuideItem(key = "OK / PLAY", desc = "Tạm dừng / Phát")
                        Spacer(modifier = Modifier.width(12.dp))
                        if (isLive) {
                            GuideItem(key = "▲ / ▼", desc = "Chuyển kênh siêu tốc")
                        } else {
                            GuideItem(key = "◄ / ►", desc = "Tua tăng tốc (10s - 1p - 5p)")
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        GuideItem(key = "🌟 TỶ LỆ", desc = "Tràn viền (100%)")
                        Spacer(modifier = Modifier.width(12.dp))
                        GuideItem(key = "MENU", desc = "Băng thông (4K/12K)")
                        Spacer(modifier = Modifier.width(12.dp))
                        GuideItem(key = "🎙️ MIC", desc = "Nói tên đài")
                        Spacer(modifier = Modifier.width(12.dp))
                        GuideItem(key = "BACK", desc = "Thoát")
                    }
                }
            }
        }

        val voiceState by voiceManager.state.collectAsState()
        LaunchedEffect(voiceState) {
            if (voiceState !is VoiceState.Idle) {
                showControls = false
                showChannelRibbon = false
                showQuickActionSheet = false
                showQualityMenu = false
                // Giảm nhỏ âm lượng stream TV (Ducking) để micro remote thu âm giọng nói rõ nét 100%, không bị tiếng loa TV làm nhiễu
                try { exoPlayer.volume = 0.05f } catch (_: Exception) {}
            } else {
                // Khôi phục âm lượng đầy đủ khi kết thúc phiên nhận diện
                try { exoPlayer.volume = 1.0f } catch (_: Exception) {}
            }
        }
        VoiceOverlay(
            state = voiceState,
            onDismiss = { voiceManager.cancel() }
        )
    }
}

@Composable
private fun PlayerQuickActionSheet(
    quickActionTab: Int,
    onSelectTab: (Int) -> Unit,
    cinematicFilter: CinematicFilter,
    onSelectCinematicFilter: (CinematicFilter) -> Unit,
    isAmbilightEnabled: Boolean,
    onSetAmbilight: (Boolean) -> Unit,
    currentAspectMode: VideoAspectMode,
    onSelectAspectMode: (VideoAspectMode) -> Unit,
    isNightWarmMode: Boolean,
    onSetNightWarmMode: (Boolean) -> Unit,
    selectedQualityMode: String,
    onSelectQuality: (String) -> Unit,
    audioBoostPercent: Int,
    isDrcVoiceEnabled: Boolean,
    onSelectAudioBoost: (Int) -> Unit,
    onSetDrcVoice: (Boolean) -> Unit,
    sleepTimerMinutes: Int,
    onSelectSleepTimer: (Int) -> Unit,
    onActivateScreenOffAudio: () -> Unit,
    isHlsAdBlockEnabled: Boolean,
    adsBlockedSessionCount: Int,
    onSetHlsAdBlock: (Boolean) -> Unit,
    playbackSpeed: Float,
    onSelectPlaybackSpeed: (Float) -> Unit,
    zeroStallFailoverEnabled: Boolean,
    onSetZeroStallFailover: (Boolean) -> Unit,
    autoSkipIntroEnabled: Boolean,
    onSetAutoSkipIntro: (Boolean) -> Unit,
    isLive: Boolean,
    activeChannelTotalSources: Int,
    currentBackupIndex: Int,
    onSwitchBackupSource: () -> Unit,
    onActivateTouchLock: () -> Unit,
    closeFocusRequester: FocusRequester,
    onCloseSheet: () -> Unit
) {
    LaunchedEffect(Unit) {
        delay(80)
        try { closeFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC0A0E17))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onCloseSheet() })
            }
            .padding(horizontal = 20.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF141923))
                .border(1.5.dp, Color(0xFF2E384D), RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {})
                }
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚙️", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "BẢNG TIỆN ÍCH & TÙY CHỈNH THÔNG MINH",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black
                    )
                }
                val closeInter = remember { MutableInteractionSource() }
                val isCloseFoc by closeInter.collectIsFocusedAsState()
                val isClosePress by closeInter.collectIsPressedAsState()
                Box(
                    modifier = Modifier
                        .focusRequester(closeFocusRequester)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isCloseFoc || isClosePress) AccentRed else Color(0xFF252E40))
                        .border(1.dp, if (isCloseFoc || isClosePress) Color.White else Color(0x44FFFFFF), RoundedCornerShape(6.dp))
                        .clickable(interactionSource = closeInter, indication = null, onClick = onCloseSheet)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "✕ ĐÓNG",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tabs = listOf(
                    "🎬 HÌNH ẢNH",
                    "🎧 ÂM THANH",
                    "⚡ TỐC ĐỘ & LUỒNG",
                    "📱 ĐIỀU KHIỂN"
                )
                tabs.forEachIndexed { idx, title ->
                    val isSelected = (quickActionTab == idx)
                    val tInter = remember { MutableInteractionSource() }
                    val isTFoc by tInter.collectIsFocusedAsState()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isTFoc) AccentRed else if (isSelected) Color(0xFF252E40) else Color(0xFF1B202D))
                            .border(
                                width = if (isTFoc) 2.dp else if (isSelected) 1.dp else 0.dp,
                                color = if (isTFoc) Color.White else if (isSelected) Color(0xFF00E676) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable(interactionSource = tInter, indication = null) {
                                onSelectTab(idx)
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) Color(0xFF00E676) else Color.White,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (quickActionTab) {
                    0 -> {
                        SettingSectionHeader("🎬 BỘ LỌC ĐIỆN ẢNH CỔ TRANG / PHIM CŨ")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            CinematicFilter.values().forEach { filter ->
                                SettingChoicePill(
                                    title = filter.title,
                                    isSelected = (cinematicFilter == filter),
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSelectCinematicFilter(filter) }
                                )
                            }
                        }

                        SettingSectionHeader("🌌 ÁNH SÁNG VIỀN VÔ CỰC (VIRTUAL AMBILIGHT)")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SettingChoicePill(
                                title = "TẮT",
                                isSelected = !isAmbilightEnabled,
                                modifier = Modifier.weight(1f),
                                onClick = { onSetAmbilight(false) }
                            )
                            SettingChoicePill(
                                title = "🌌 BẬT HÀO QUANG",
                                isSelected = isAmbilightEnabled,
                                modifier = Modifier.weight(1f),
                                onClick = { onSetAmbilight(true) }
                            )
                        }

                        SettingSectionHeader("📺 TỶ LỆ KHUNG HÌNH (ASPECT RATIO)")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SettingChoicePill(
                                title = "📺 CHUẨN GỐC",
                                isSelected = (currentAspectMode == VideoAspectMode.FIT),
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectAspectMode(VideoAspectMode.FIT) }
                            )
                            SettingChoicePill(
                                title = "🌟 TRÀN VIỀN 100%",
                                isSelected = (currentAspectMode == VideoAspectMode.ZOOM),
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectAspectMode(VideoAspectMode.ZOOM) }
                            )
                            SettingChoicePill(
                                title = "↔️ CO GIÃN",
                                isSelected = (currentAspectMode == VideoAspectMode.FILL),
                                modifier = Modifier.weight(1f),
                                onClick = { onSelectAspectMode(VideoAspectMode.FILL) }
                            )
                        }

                        SettingSectionHeader("🌆 CHẾ ĐỘ DỊU MẮT BAN ĐÊM")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SettingChoicePill(
                                title = "TẮT",
                                isSelected = !isNightWarmMode,
                                modifier = Modifier.weight(1f),
                                onClick = { onSetNightWarmMode(false) }
                            )
                            SettingChoicePill(
                                title = "🌆 BẬT DỊU MẮT",
                                isSelected = isNightWarmMode,
                                modifier = Modifier.weight(1f),
                                onClick = { onSetNightWarmMode(true) }
                            )
                        }

                        SettingSectionHeader("💎 ĐỘ PHÂN GIẢI & BĂNG THÔNG")
                        val qualityOptions = listOf("AUTO", "4K", "2K", "1080P", "720P")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            qualityOptions.forEach { q ->
                                SettingChoicePill(
                                    title = q,
                                    isSelected = (selectedQualityMode == q),
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSelectQuality(q) }
                                )
                            }
                        }
                    }
                    1 -> {
                        SettingSectionHeader("🔊 KHUẾCH ĐẠI ÂM LƯỢNG DSP (PHẦN CỨNG)")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf(100 to "100% GỐC", 150 to "150% TO RÕ", 200 to "200% CỰC ĐẠI").forEach { (boost, label) ->
                                SettingChoicePill(
                                    title = label,
                                    isSelected = (audioBoostPercent == boost && !isDrcVoiceEnabled),
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSelectAudioBoost(boost) }
                                )
                            }
                        }

                        SettingSectionHeader("🌙 CHẾ ĐỘ BAN ĐÊM & LỌC GIỌNG (DRC VOICE CLEAR)")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SettingChoicePill(
                                title = "TẮT",
                                isSelected = !isDrcVoiceEnabled,
                                modifier = Modifier.weight(1f),
                                onClick = { onSetDrcVoice(false) }
                            )
                            SettingChoicePill(
                                title = "🌙 LỌC GIỌNG RÕ THOẠI",
                                isSelected = isDrcVoiceEnabled,
                                modifier = Modifier.weight(1f),
                                onClick = { onSetDrcVoice(true) }
                            )
                        }

                        SettingSectionHeader("⏲️ HẸN GIỜ TỰ TẮT GIẢM DẦN ÂM LƯỢNG (FADE-OUT)")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf(0 to "TẮT", 15 to "15P", 30 to "30P", 60 to "60P", 90 to "90P").forEach { (min, label) ->
                                SettingChoicePill(
                                    title = label,
                                    isSelected = (sleepTimerMinutes == min),
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSelectSleepTimer(min) }
                                )
                            }
                        }

                        SettingSectionHeader("🌙 TIẾT KIỆM ĐIỆN")
                        Row(modifier = Modifier.fillMaxWidth()) {
                            SettingChoicePill(
                                title = "🌙 TẮT MÀN HÌNH CHỈ NGHE TIẾNG",
                                isSelected = false,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onActivateScreenOffAudio
                            )
                        }
                    }
                    2 -> {
                        SettingSectionHeader("🛡️ LÁ CHẮN DIỆT 100% QUẢNG CÁO CHÈN GIỮA PHIM (HLS AD-BLOCK)")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SettingChoicePill(
                                title = "🛡️ BẬT CHẶN 100% QUẢNG CÁO (ĐÃ DIỆT $adsBlockedSessionCount QC)",
                                isSelected = isHlsAdBlockEnabled,
                                modifier = Modifier.weight(1f),
                                onClick = { onSetHlsAdBlock(true) }
                            )
                            SettingChoicePill(
                                title = "TẮT",
                                isSelected = !isHlsAdBlockEnabled,
                                modifier = Modifier.weight(0.4f),
                                onClick = { onSetHlsAdBlock(false) }
                            )
                        }

                        SettingSectionHeader("⚡ TỐC ĐỘ PHÁT (NHỚ RIÊNG CHO PHIM BỘ)")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                SettingChoicePill(
                                    title = "${spd}x",
                                    isSelected = (playbackSpeed == spd),
                                    modifier = Modifier.weight(1f),
                                    onClick = { onSelectPlaybackSpeed(spd) }
                                )
                            }
                        }

                        SettingSectionHeader("🛡️ TỰ ĐỘNG TRÁO SERVER CHỐNG LAG (> 3S)")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SettingChoicePill(
                                title = "🛡️ BẬT (KHUYÊN DÙNG)",
                                isSelected = zeroStallFailoverEnabled,
                                modifier = Modifier.weight(1f),
                                onClick = { onSetZeroStallFailover(true) }
                            )
                            SettingChoicePill(
                                title = "TẮT",
                                isSelected = !zeroStallFailoverEnabled,
                                modifier = Modifier.weight(1f),
                                onClick = { onSetZeroStallFailover(false) }
                            )
                        }

                        SettingSectionHeader("⏭️ TỰ ĐỘNG BỎ QUA MỞ ĐẦU 85S")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            SettingChoicePill(
                                title = "⏭️ BẬT",
                                isSelected = autoSkipIntroEnabled,
                                modifier = Modifier.weight(1f),
                                onClick = { onSetAutoSkipIntro(true) }
                            )
                            SettingChoicePill(
                                title = "TẮT",
                                isSelected = !autoSkipIntroEnabled,
                                modifier = Modifier.weight(1f),
                                onClick = { onSetAutoSkipIntro(false) }
                            )
                        }

                        if (activeChannelTotalSources > 1) {
                            SettingSectionHeader("🔄 ĐỔI SERVER PHÁT DỰ PHÒNG THỦ CÔNG")
                            Row(modifier = Modifier.fillMaxWidth()) {
                                SettingChoicePill(
                                    title = "🔄 TRÁO SANG SERVER DỰ PHÒNG (${currentBackupIndex + 1}/$activeChannelTotalSources)",
                                    isSelected = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = onSwitchBackupSource
                                )
                            }
                        }
                    }
                    3 -> {
                        SettingSectionHeader("📱 REMOTE ẢO QUA MẠNG WI-FI")
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF1B202D))
                                .padding(14.dp)
                        ) {
                            Text(
                                text = "📱 Dùng điện thoại / tablet làm Remote điều khiển:",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Kết nối cùng mạng Wi-Fi, mở trình duyệt trên điện thoại và gõ IP của thiết bị để điều khiển điều hướng D-pad, âm lượng và nhảy kênh mượt mà.",
                                color = Color(0xFFA0AAB8),
                                fontSize = 11.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        SettingSectionHeader("🔒 KHÓA CẢM ỨNG MÀN HÌNH")
                        Row(modifier = Modifier.fillMaxWidth()) {
                            SettingChoicePill(
                                title = "🔒 KÍCH HOẠT KHÓA MÀN HÌNH (Chống chạm nhầm)",
                                isSelected = false,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onActivateTouchLock
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickSeekPill(
    text: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val inter = remember { MutableInteractionSource() }
    val isFoc by inter.collectIsFocusedAsState()
    val isPress by inter.collectIsPressedAsState()
    val isHighlighted = isFoc || isPress

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHighlighted) AccentRed else if (highlight) Color(0xFF222938) else Color(0x33FFFFFF))
            .border(
                width = if (isHighlighted) 1.5.dp else 1.dp,
                color = if (isHighlighted) Color.White else Color(0x44FFFFFF),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(interactionSource = inter, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun GuideItem(key: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .background(Color(0x44FFFFFF), RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(text = key, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = desc, color = Color(0xFFC0CAD6), fontSize = 11.sp)
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600

    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}
@Composable
fun SettingSectionHeader(title: String) {
    Text(
        text = title,
        color = Color(0xFF64B5F6),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
fun SettingChoicePill(
    title: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val inter = remember { MutableInteractionSource() }
    val isFoc by inter.collectIsFocusedAsState()
    val isPress by inter.collectIsPressedAsState()
    val isHighlighted = isFoc || isPress
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHighlighted) AccentRed else if (isSelected) Color(0xFF263238) else Color(0xFF181D28))
            .border(
                width = if (isHighlighted) 1.5.dp else if (isSelected) 1.dp else 0.dp,
                color = if (isHighlighted) Color.White else if (isSelected) Color(0xFF00E676) else Color.Transparent,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(interactionSource = inter, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (isSelected) Color(0xFF00E676) else Color.White,
            fontSize = 11.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

private fun adjustPlayerTouchBrightnessOrVolume(
    context: Context,
    isLeftHalf: Boolean,
    dragDeltaY: Float,
    screenHeightPx: Float
): String {
    val stepRatio = (-dragDeltaY / screenHeightPx) * 1.35f
    if (isLeftHalf) {
        val window = (context as? Activity)?.window ?: return "☀️ ĐỘ SÁNG"
        val lp = window.attributes
        val currentB = if (lp.screenBrightness < 0f) 0.65f else lp.screenBrightness
        val nextB = (currentB + stepRatio).coerceIn(0.05f, 1.0f)
        lp.screenBrightness = nextB
        window.attributes = lp
        val pct = (nextB * 100).toInt()
        return "☀️ ĐỘ SÁNG MÀN HÌNH: $pct%"
    } else {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return "🔊 ÂM LƯỢNG"
        val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val curVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val deltaVol = if (dragDeltaY < -10f) 1 else if (dragDeltaY > 10f) -1 else 0
        val nextVol = (curVol + deltaVol).coerceIn(0, maxVol)
        if (nextVol != curVol) {
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, nextVol, 0)
        }
        val pct = ((nextVol.toFloat() / maxVol.toFloat()) * 100).toInt()
        return "🔊 ÂM LƯỢNG HỆ THỐNG: $pct%"
    }
}

@OptIn(UnstableApi::class)
private fun buildStreamMediaSource(
    context: Context,
    url: String,
    isLive: Boolean,
    subtitleUrl: String?
): androidx.media3.exoplayer.source.MediaSource {
    val userAgent = if (url.contains("hainghia.net")) {
        "TiviMate/4.7.0"
    } else {
        "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 DuyTV/1.0"
    }
    val headers = mutableMapOf<String, String>()
    when {
        url.contains("cdnfastter") || url.contains("khoga") -> {
            headers["Referer"] = "https://khoga1.live/"
            headers["Origin"] = "https://khoga1.live"
        }
        url.contains("livetl") || url.contains("thapcam") -> {
            headers["Referer"] = "https://thapcamtvt1.com/"
            headers["Origin"] = "https://thapcamtvt1.com"
        }
    }
    val dsFactory = com.duytv.app.util.HlsAdBlockEngine.buildAdBlockingDataSourceFactory(
        context = context,
        userAgent = userAgent,
        headers = headers
    )
    val extractorsFactory = DefaultExtractorsFactory()
        .setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS
        )
    val msFactory = DefaultMediaSourceFactory(dsFactory, extractorsFactory)
    val mediaItemB = MediaItem.Builder().setUri(url)
    if (url.contains("/rtp/") || url.contains("video/mp2t") || url.contains("a3iptv.goitoi.co") || url.contains("r2h-token=") || url.substringBefore("?").endsWith(".ts")) {
        mediaItemB.setMimeType(MimeTypes.VIDEO_MP2T)
    } else if (url.substringBefore("?").endsWith(".flv")) {
        mediaItemB.setMimeType(MimeTypes.VIDEO_FLV)
    } else if (url.contains(".m3u8") || url.contains("/hls/")) {
        mediaItemB.setMimeType(MimeTypes.APPLICATION_M3U8)
    }
    if (isLive) {
        mediaItemB.setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(4000L)
                .setMinOffsetMs(2000L)
                .setMaxOffsetMs(10000L)
                .setMinPlaybackSpeed(0.97f)
                .setMaxPlaybackSpeed(1.04f)
                .build()
        )
    }
    if (!subtitleUrl.isNullOrBlank()) {
        val subMime = when {
            subtitleUrl.startsWith("data:application/x-subrip") -> MimeTypes.APPLICATION_SUBRIP
            subtitleUrl.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
            .setMimeType(subMime)
            .setLanguage("vi")
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        mediaItemB.setSubtitleConfigurations(listOf(subConfig))
    }
    return msFactory.createMediaSource(mediaItemB.build())
}

@OptIn(UnstableApi::class)
// [✅ ĐÃ CHUẨN - CẤM SỬA] Bắt buộc giữ nguyên:
// 1. TrackSelector ưu tiên chất lượng cao nhất (Int.MAX_VALUE + setForceHighestSupportedBitrate(true))
// 2. setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT) kết hợp với PlayerView RESIZE_MODE_FILL
//    để co giãn tràn 100% mọi màn hình (16:9, 3:2, 4:3, 20:9, 23:9) không viền đen VÀ không bị cắt mất logo/phụ đề/tỷ số.
private fun createConfiguredExoPlayer(
    context: Context,
    streamUrl: String,
    isLive: Boolean,
    subtitleUrl: String?,
    initialPositionMs: Long
): ExoPlayer {
    val loadControl = com.duytv.app.util.HardwareOptimizer.buildLoadControl(context, isLive)
    val renderersFactory = com.duytv.app.util.HardwareOptimizer.buildRenderersFactory(context)
    val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
        setParameters(
            buildUponParameters()
                .clearVideoSizeConstraints()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .setMaxAudioBitrate(Int.MAX_VALUE)
                .setForceHighestSupportedBitrate(true)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
        )
    }
    val mediaSource = buildStreamMediaSource(context, streamUrl, isLive, subtitleUrl)
    return ExoPlayer.Builder(context, renderersFactory)
        .setTrackSelector(trackSelector)
        .setLoadControl(loadControl)
        .setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        .build()
        .apply {
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            setMediaSource(mediaSource)
            prepare()
            if (initialPositionMs > 0) {
                seekTo(initialPositionMs)
            }
            playWhenReady = true
        }
}

private fun launchAppByQuery(context: Context, query: String) {
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
            // Đặc biệt với YouTube trên TV: ShellActivity hoặc Component trực tiếp
            if (intent == null && (directPkg == "com.google.android.youtube.tv" || directPkg == "com.google.android.youtube")) {
                val shellIntent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName("com.google.android.youtube.tv", "com.google.android.apps.youtube.tv.activity.ShellActivity")
                    addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                }
                if (shellIntent.resolveActivity(pm) != null) {
                    intent = shellIntent
                } else {
                    intent = pm.getLeanbackLaunchIntentForPackage("com.liskovsoft.smarttubetv.beta")
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
            val apps = pm.queryIntentActivities(mainIntent, 0)
            val matched = apps.find { 
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
            val stdApps = pm.queryIntentActivities(stdIntent, 0)
            val matched = stdApps.find { 
                val label = it.loadLabel(pm).toString().lowercase()
                label.contains(q) || q.contains(label)
            }
            if (matched != null) {
                val pkg = matched.activityInfo.packageName
                intent = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
            }
        }
        // Fallback chung cho YouTube nếu chưa có Intent Launcher nào
        if (intent == null && (q.contains("youtube") || q.contains("you tube"))) {
            intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
        }

        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            android.util.Log.i("PlayerScreen", "Khởi chạy ứng dụng thành công: $intent")
            context.startActivity(intent)
        } else {
            android.util.Log.w("PlayerScreen", "Không tìm thấy ứng dụng phù hợp cho: $query")
        }
    } catch (e: Exception) {
        android.util.Log.e("PlayerScreen", "Lỗi launchAppByQuery: ${e.message}", e)
    }
}

private fun launchSettingIntent(context: Context, type: VoiceCommand.SettingType) {
    try {
        val action = when (type) {
            VoiceCommand.SettingType.WIFI -> android.provider.Settings.ACTION_WIFI_SETTINGS
            VoiceCommand.SettingType.BLUETOOTH -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
            VoiceCommand.SettingType.SOUND -> android.provider.Settings.ACTION_SOUND_SETTINGS
            VoiceCommand.SettingType.DISPLAY -> android.provider.Settings.ACTION_DISPLAY_SETTINGS
            VoiceCommand.SettingType.APPS -> android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS
            VoiceCommand.SettingType.DATE -> android.provider.Settings.ACTION_DATE_SETTINGS
            VoiceCommand.SettingType.SYSTEM -> android.provider.Settings.ACTION_SETTINGS
        }
        val intent = Intent(action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        try {
            val fallback = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        } catch (_: Exception) {}
    }
}

private fun launchYouTubeSearch(context: Context, query: String) {
    try {
        val uri = android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query))
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
            if (installedPkg != null) {
                setPackage(installedPkg)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        android.util.Log.e("PlayerScreen", "Lỗi mở YouTube Search: ${e.message}")
    }
}
