package com.duytv.app.ui.screens

import android.app.Activity
import android.net.Uri
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator
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

enum class VideoAspectMode(val mode: Int, val label: String, val desc: String) {
    ZOOM(AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "🌟 TRÀN MÀN HÌNH (100%)", "Xóa sạch 100% viền đen, lấp kín toàn màn hình"),
    FILL(AspectRatioFrameLayout.RESIZE_MODE_FILL, "↔️ CO GIÃN ĐẦY MÀN HÌNH", "Kéo phẳng vừa khít toàn bộ diện tích tấm nền"),
    FIT(AspectRatioFrameLayout.RESIZE_MODE_FIT, "📺 VỪA VẶN CHUẨN GỐC", "Giữ nguyên tỷ lệ chuẩn điện ảnh gốc")
}

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showControls by remember { mutableStateOf(true) }
    var showQualityMenu by remember { mutableStateOf(false) }
    var selectedQualityMode by remember { mutableStateOf("AUTO") }
    var videoResolution by remember { mutableStateOf("1080p FHD") }
    var currentPosMs by remember { mutableStateOf(initialPositionMs) }
    var totalDurationMs by remember { mutableStateOf(1L) }
    var pendingSeekPosMs by remember { mutableStateOf<Long?>(null) }
    var seekDeltaText by remember { mutableStateOf("") }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var lastSeekTimestamp by remember { mutableLongStateOf(0L) }
    var seekConsecutiveCount by remember { mutableIntStateOf(0) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    val isLive = remember(streamUrl, slug) {
        slug.startsWith("live_") || streamUrl.contains("/live/") || streamUrl.contains("akamaized.net") || streamUrl.contains("fptplay")
    }

    var currentTitle by remember(movieTitle) { mutableStateOf(movieTitle) }
    var currentEpisodeName by remember(episodeName) { mutableStateOf(episodeName) }
    var currentStreamUrl by remember(streamUrl) { mutableStateOf(streamUrl) }
    var currentPosterUrl by remember(posterUrl) { mutableStateOf(posterUrl) }
    var currentGroupName by remember { mutableStateOf("") }

    // Danh sách tập phim để tự động nhảy tập tiếp theo khi hết tập (Auto-Next Episode)
    var availableEpisodes by remember(episodes) { mutableStateOf(episodes) }
    val currentEpisodeIndex = remember(availableEpisodes, currentEpisodeName, currentStreamUrl) {
        availableEpisodes.indexOfFirst {
            it.name.equals(currentEpisodeName, ignoreCase = true) ||
                (!it.linkM3u8.isNullOrBlank() && it.linkM3u8 == currentStreamUrl)
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
                    availableEpisodes = serverEps
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
        if (!isLive) return listOf(currentStreamUrl)
        val ch = availableChannels.getOrNull(currentChannelIndex)
        return if (ch != null) {
            (ch.allStreamUrls() + backupUrls).filter { it.isNotBlank() }.distinct()
        } else {
            (listOf(streamUrl) + backupUrls).filter { it.isNotBlank() }.distinct()
        }
    }

    LaunchedEffect(showControls, isPlaying, showQualityMenu) {
        if (showControls && isPlaying && !showQualityMenu) {
            delay(5000)
            showControls = false
        }
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
    var audioBoostPercent by remember { mutableIntStateOf(100) }
    var loudnessEnhancer by remember { mutableStateOf<LoudnessEnhancer?>(null) }
    var numberInputBuffer by remember { mutableStateOf("") }
    var numberInputJob by remember { mutableStateOf<Job?>(null) }
    var previousVisitedChannelIndex by remember { mutableIntStateOf(-1) }
    var isScreenOffAudioMode by remember { mutableStateOf(false) }
    var isNightWarmMode by remember { mutableStateOf(false) }
    var isCurrentChannelFav by remember(currentTitle) {
        mutableStateOf(AppCacheManager.isFavoriteChannel(currentTitle))
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

    // Mặc định tự động TRÀN VIỀN 100% (AUTO FULL) xóa sổ viền đen trên Tablet & TV
    var currentAspectMode by remember { mutableStateOf(VideoAspectMode.ZOOM) }
    var aspectBadgeText by remember { mutableStateOf<String?>(null) }
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }

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
        aspectBadgeText = currentAspectMode.label
        scope.launch {
            delay(2200)
            aspectBadgeText = null
        }
    }

    // Keep screen awake & Immersive Sticky Fullscreen (Ẩn 100% thanh điều hướng và thanh trạng thái)
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
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

    // Auto-hide controls timer (4 seconds)
    LaunchedEffect(showControls, isPlaying, showQualityMenu) {
        if (showControls && isPlaying && !showQualityMenu) {
            delay(4000)
            showControls = false
        }
    }

    val isMagnet = streamUrl.startsWith("magnet:?")

    val exoPlayer = remember(streamUrl) {
        if (isMagnet) {
            null
        } else {
            // Universal Multi-SoC Hardware Engine (Qualcomm, Amlogic, Rockchip, Realtek, Exynos, MediaTek, x86_64)
            val loadControl = com.duytv.app.util.HardwareOptimizer.buildLoadControl(context, isLive)
            val renderersFactory = com.duytv.app.util.HardwareOptimizer.buildRenderersFactory(context)

            val userAgent = if (streamUrl.contains("hainghia.net")) {
                "TiviMate/4.7.0"
            } else {
                "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 DuyTV/1.0"
            }

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true)

            val mediaItemBuilder = MediaItem.Builder().setUri(streamUrl)
            if (streamUrl.contains("/rtp/") || streamUrl.contains("video/mp2t") || streamUrl.endsWith(".ts")) {
                mediaItemBuilder.setMimeType(MimeTypes.VIDEO_MP2T)
            } else if (streamUrl.contains(".m3u8") || streamUrl.contains("/hls/")) {
                mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }

            if (isLive) {
                mediaItemBuilder.setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(4000L)
                        .setMinOffsetMs(2000L)
                        .setMaxOffsetMs(10000L)
                        .build()
                )
            }

            // Inject Vietnamese Subtitles if available
            if (!subtitleUrl.isNullOrBlank()) {
                val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                    .setMimeType(if (subtitleUrl.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                    .setLanguage("vi")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                mediaItemBuilder.setSubtitleConfigurations(listOf(subConfig))
            }

            val mediaItem = mediaItemBuilder.build()
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            ExoPlayer.Builder(context, renderersFactory)
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
                        .focusable(interactionSource = backInter)
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

    // Track playback position & Save history mỗi 10s (bỏ qua nếu đang xem luồng trực tiếp / TV)
    LaunchedEffect(exoPlayer) {
        var tickCount = 0
        while (true) {
            delay(1000)
            if (exoPlayer.isPlaying && pendingSeekPosMs == null) {
                currentPosMs = exoPlayer.currentPosition
                totalDurationMs = maxOf(1L, exoPlayer.duration)
                tickCount++
                if (!isLive && tickCount % 10 == 0) {
                    WatchHistoryManager.saveProgress(
                        context = context,
                        slug = slug,
                        movieTitle = currentTitle,
                        episodeName = currentEpisodeName,
                        streamUrl = currentStreamUrl,
                        posterUrl = currentPosterUrl,
                        positionMs = currentPosMs,
                        durationMs = totalDurationMs
                    )
                }
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    exoPlayer.pause()
                    exoPlayer.playWhenReady = false
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
        val totalSources = channel.allStreamUrls().size
        val sourceLabel = if (totalSources > 1) " • Nguồn ${sourceIdx + 1}/$totalSources" else ""
        showControls = false
        channelOsdName = channel.name
        channelOsdSub = "${channel.groupTitle} • Kênh ${index + 1}/$total$sourceLabel"
        channelOsdLogo = channel.logoUrl
        channelOsdVisible = true
        channelOsdJob?.cancel()
        channelOsdJob = scope.launch {
            delay(3500)
            channelOsdVisible = false
        }
    }

    fun loadLiveStreamUrl(url: String) {
        val player = exoPlayer ?: return
        currentStreamUrl = url
        try {
            val userAgent = if (url.contains("hainghia.net")) {
                "TiviMate/4.7.0"
            } else {
                "Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 DuyTV/1.0"
            }
            val dsFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true)
            val msFactory = DefaultMediaSourceFactory(dsFactory)
            val mediaItemB = MediaItem.Builder().setUri(url)
            if (url.contains("/rtp/") || url.contains("video/mp2t") || url.endsWith(".ts")) {
                mediaItemB.setMimeType(MimeTypes.VIDEO_MP2T)
            } else if (url.contains(".m3u8") || url.contains("/hls/")) {
                mediaItemB.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            mediaItemB.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(4000L)
                    .setMinOffsetMs(2000L)
                    .setMaxOffsetMs(10000L)
                    .build()
            )
            val newMediaSource = msFactory.createMediaSource(mediaItemB.build())
            errorRetryJob?.cancel()
            player.stop()
            player.clearMediaItems()
            player.setMediaSource(newMediaSource)
            player.prepare()
            player.play()
        } catch (_: Exception) {}
    }

    fun playVodEpisodeAt(targetIndex: Int, isAutoAdvance: Boolean = false) {
        if (isLive || targetIndex !in availableEpisodes.indices) return
        val ep = availableEpisodes[targetIndex]
        val player = exoPlayer ?: return

        scope.launch {
            var nextUrl = ep.linkM3u8 ?: ""
            if (nextUrl.isBlank() && ep.slug.startsWith("cobe_ep_") && repository != null) {
                val epId = ep.slug.split("_").getOrNull(2)?.toLongOrNull()
                if (epId != null) {
                    nextUrl = try { repository.resolveCoBeEpisode(epId) ?: "" } catch (_: Exception) { "" }
                }
            }
            if (nextUrl.isBlank()) {
                nextUrl = ep.linkEmbed ?: ""
            }
            if (nextUrl.isBlank()) return@launch

            currentEpisodeName = ep.name
            currentStreamUrl = nextUrl
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
                val dsFactory = DefaultHttpDataSource.Factory()
                    .setUserAgent("Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 DuyTV/1.0")
                    .setConnectTimeoutMs(8000)
                    .setReadTimeoutMs(20000)
                    .setAllowCrossProtocolRedirects(true)
                val msFactory = DefaultMediaSourceFactory(dsFactory)
                val mediaItemB = MediaItem.Builder().setUri(nextUrl)
                if (nextUrl.contains(".m3u8") || nextUrl.contains("/hls/")) {
                    mediaItemB.setMimeType(MimeTypes.APPLICATION_M3U8)
                }
                if (!subtitleUrl.isNullOrBlank()) {
                    val subConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                        .setMimeType(if (subtitleUrl.endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("vi")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                    mediaItemB.setSubtitleConfigurations(listOf(subConfig))
                }
                val newMediaSource = msFactory.createMediaSource(mediaItemB.build())
                errorRetryJob?.cancel()
                player.stop()
                player.clearMediaItems()
                player.setMediaSource(newMediaSource)
                player.prepare()
                player.seekTo(0L)
                player.play()
            } catch (_: Exception) {}

            showControls = false
            channelOsdName = "$currentTitle • ${ep.name}"
            channelOsdSub = if (isAutoAdvance) {
                "⏭️ Hết tập • Tự động chuyển sang ${ep.name} (${targetIndex + 1}/${availableEpisodes.size})"
            } else {
                "🎬 Đang phát: ${ep.name} (${targetIndex + 1}/${availableEpisodes.size})"
            }
            channelOsdLogo = currentPosterUrl
            channelOsdVisible = true
            channelOsdJob?.cancel()
            channelOsdJob = scope.launch {
                delay(4000)
                channelOsdVisible = false
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    errorRetryJob?.cancel()
                    errorMessage = null
                    totalDurationMs = maxOf(1L, exoPlayer.duration)
                } else if (playbackState == Player.STATE_ENDED && !isLive) {
                    val activeIdx = availableEpisodes.indexOfFirst {
                        it.name.equals(currentEpisodeName, ignoreCase = true) ||
                            (!it.linkM3u8.isNullOrBlank() && it.linkM3u8 == currentStreamUrl)
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
                val currentChannel = if (isLive && currentChannelIndex in availableChannels.indices) {
                    availableChannels[currentChannelIndex]
                } else null
                val allUrls = getActiveLiveUrls()

                if (isLive && currentBackupIndex + 1 < allUrls.size) {
                    val nextBackupIdx = currentBackupIndex + 1
                    currentBackupIndex = nextBackupIdx
                    errorMessage = null
                    loadLiveStreamUrl(allUrls[nextBackupIdx])
                    channelOsdName = currentChannel?.name ?: currentTitle
                    channelOsdSub = "⚡ Đường truyền lỗi • Tự chuyển Dự phòng ${nextBackupIdx + 1}/${allUrls.size}"
                    channelOsdLogo = currentChannel?.logoUrl ?: currentPosterUrl
                    channelOsdVisible = true
                    channelOsdJob?.cancel()
                    channelOsdJob = scope.launch {
                        delay(4000)
                        channelOsdVisible = false
                    }
                } else {
                    errorMessage = "Lỗi đường truyền: ${error.errorCodeName}. Đang tự động kết nối lại..."
                    errorRetryJob?.cancel()
                    errorRetryJob = scope.launch {
                        delay(2500)
                        if (isLive && allUrls.isNotEmpty()) {
                            currentBackupIndex = 0
                            loadLiveStreamUrl(allUrls.first())
                        } else {
                            exoPlayer.prepare()
                            exoPlayer.play()
                        }
                    }
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
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
                .build()
            "8K" -> exoPlayer.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(7680, 4320)
                .setMinVideoSize(3840, 2160)
                .build()
            "4K" -> exoPlayer.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(3840, 2160)
                .setMinVideoSize(2560, 1440)
                .build()
            "2K" -> exoPlayer.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(2560, 1440)
                .setMinVideoSize(1920, 1080)
                .build()
            "1080P" -> exoPlayer.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(1920, 1080)
                .setMinVideoSize(1280, 720)
                .build()
            "720P" -> exoPlayer.trackSelectionParameters.buildUpon()
                .setMaxVideoSize(1280, 720)
                .build()
            else -> exoPlayer.trackSelectionParameters.buildUpon()
                .clearVideoSizeConstraints()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setMaxVideoBitrate(Int.MAX_VALUE)
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

        loadLiveStreamUrl(ch.streamUrl)
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
        if (!isLive) return
        val allUrls = getActiveLiveUrls()
        if (allUrls.size <= 1) return
        val nextIdx = (currentBackupIndex + 1) % allUrls.size
        currentBackupIndex = nextIdx
        errorMessage = null
        loadLiveStreamUrl(allUrls[nextIdx])
        val ch = availableChannels.getOrNull(currentChannelIndex)
        if (ch != null) {
            showChannelOsd(ch, currentChannelIndex, availableChannels.size, sourceIdx = nextIdx)
        } else {
            showControls = false
            channelOsdName = currentTitle
            channelOsdSub = "⚡ Đã chuyển sang Đường truyền ${nextIdx + 1}/${allUrls.size}"
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
    val currentShowChannelRibbon by rememberUpdatedState(showChannelRibbon)
    val currentIsTouchLocked by rememberUpdatedState(isTouchLocked)
    val currentPendingSeekPosMs by rememberUpdatedState(pendingSeekPosMs)
    val currentIsPlaying by rememberUpdatedState(isPlaying)

    // Bắt toàn diện 100% phím bấm phần cứng từ Remote Sharp TV ở cấp độ Activity
    DisposableEffect(exoPlayer) {
        MainActivity.onKeyEventListener = listener@{ event ->
            val keyCode = event.keyCode
            val action = event.action

            if (currentIsTouchLocked) {
                if (action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_BACK)) {
                    isTouchLocked = false
                    showControls = true
                }
                return@listener true
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
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        showControls = true
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
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            showControls = true
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
                        showQualityMenu = !showQualityMenu
                        showControls = true
                    }
                    true
                }
                keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE -> {
                    if (currentShowQualityMenu) {
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
                        when {
                            !isLive && ratioX < 0.32f -> triggerSeek(-10)
                            !isLive && ratioX > 0.68f -> triggerSeek(10)
                            else -> cycleAspectMode()
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
            .onKeyEvent { event ->
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
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            showControls = true
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
                        KeyEvent.KEYCODE_WINDOW,
                        KeyEvent.KEYCODE_PROG_GREEN,
                        KeyEvent.KEYCODE_CAPTIONS,
                        KeyEvent.KEYCODE_INFO -> {
                            cycleAspectMode()
                            showControls = true
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_MENU -> {
                            showQualityMenu = !showQualityMenu
                            showControls = true
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
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
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = currentAspectMode.desc,
                        color = Color(0xFFA0AAB8),
                        fontSize = 11.sp
                    )
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

        // Quality Selection Overlay Modal
        if (showQualityMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xCC0A0E17))
                    .padding(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF161B26))
                        .border(1.dp, Color(0xFF30363D), RoundedCornerShape(12.dp))
                        .padding(28.dp)
                ) {
                    Text(
                        text = "⚙️ TÙY CHỈNH BĂNG THÔNG & ĐỘ PHÂN GIẢI",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Chế độ Tự Động sẽ thích ứng theo đường truyền mạng: mạng khỏe tự động phát 4K/2K, mạng yếu tự hạ bitrate để không bị lag.",
                        color = Color(0xFFA0AAB8),
                        fontSize = 12.sp,
                        modifier = Modifier.width(550.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    val qualityOptions = listOf(
                        Triple("AUTO", "⚡ TỰ ĐỘNG (Thích ứng tới 12K)", "Tự động đẩy lên 4K / 8K / 12K tối đa theo mạng"),
                        Triple("12K", "👑 12K / 8K Ultra HD (Không giới hạn)", "Mở khóa 100% băng thông & độ phân giải tối đa"),
                        Triple("4K", "💎 4K Ultra HD (2160p)", "Ưu tiên độ nét 4K Cinema (yêu cầu mạng > 25Mbps)"),
                        Triple("2K", "🌟 2K QHD (1440p)", "Cân bằng siêu nét cho màn hình lớn"),
                        Triple("1080P", "📺 Full HD (1080p)", "Chuẩn nét ổn định & mượt mà"),
                        Triple("720P", "⚡ HD (720p)", "Tiết kiệm băng thông tối đa")
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((key, title, desc) in qualityOptions) {
                            val isSelected = (selectedQualityMode == key)
                            val qInter = remember { MutableInteractionSource() }
                            val isQFoc by qInter.collectIsFocusedAsState()

                            Row(
                                modifier = Modifier
                                    .widthIn(max = 540.dp)
                                    .fillMaxWidth(0.92f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isQFoc) AccentRed else if (isSelected) Color(0xFF252B3B) else Color(0xFF1F2430))
                                    .border(
                                        width = if (isQFoc) 2.dp else if (isSelected) 1.dp else 0.dp,
                                        color = if (isQFoc) Color.White else if (isSelected) AccentRed else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .focusable(interactionSource = qInter)
                                    .clickable(interactionSource = qInter, indication = null) {
                                        applyQualitySetting(key)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = title,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected || isQFoc) FontWeight.Bold else FontWeight.Medium
                                    )
                                    Text(
                                        text = desc,
                                        color = if (isQFoc) Color(0xFFEEEEEE) else Color(0xFF8B949E),
                                        fontSize = 11.sp
                                    )
                                }
                                if (isSelected) {
                                    Text(
                                        text = "✓ ĐANG DÙNG",
                                        color = Color(0xFF00E676),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Nhấn BACK trên điều khiển để đóng menu này", color = Color(0xFF6E7681), fontSize = 11.sp)
                }
            }
        }

        // HUD Overlay Controls
        val screenWidthDp = LocalConfiguration.current.screenWidthDp
        val hudHorizontalPad = if (screenWidthDp < 600) 16.dp else if (screenWidthDp < 900) 24.dp else 48.dp
        val hudVerticalPad = if (screenWidthDp < 600) 14.dp else 28.dp
        val activeChannelTotalSources = if (isLive) getActiveLiveUrls().size else 1

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
            visible = showControls && !showQualityMenu && !isTouchLocked,
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isExitFoc) AccentRed else Color(0xFF222938))
                                .border(
                                    width = if (isExitFoc) 1.5.dp else 1.dp,
                                    color = if (isExitFoc) Color.White else Color(0x44FFFFFF),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .focusable(interactionSource = exitInter)
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
                                text = if (isLive) "🔴 TRUYỀN HÌNH TRỰC TIẾP ${if (currentGroupName.isNotBlank()) "• $currentGroupName" else ""} • $videoResolution" else "Đang phát: $currentEpisodeName ${if (availableEpisodes.size > 1 && currentEpisodeIndex >= 0) "(${currentEpisodeIndex + 1}/${availableEpisodes.size})" else ""} • $videoResolution • $selectedQualityMode",
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
                        if (!subtitleUrl.isNullOrBlank()) {
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

                        // ⭐ Ghim Kênh Yêu Thích (Khi đang xem Truyền hình / Bóng đá)
                        if (isLive) {
                            val favInter = remember { MutableInteractionSource() }
                            val isFavFoc by favInter.collectIsFocusedAsState()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isFavFoc) AccentRed else if (isCurrentChannelFav) Color(0xFF3E2723) else Color(0xFF222938))
                                    .border(
                                        width = if (isFavFoc || isCurrentChannelFav) 1.5.dp else 0.dp,
                                        color = if (isFavFoc) Color.White else if (isCurrentChannelFav) Color(0xFFFFD600) else Color.Transparent,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .focusable(interactionSource = favInter)
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

                        // 🔊 Nút Khuếch Đại Âm Thanh (100% / 150% / 200%)
                        val boostInter = remember { MutableInteractionSource() }
                        val isBoostFoc by boostInter.collectIsFocusedAsState()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isBoostFoc) AccentRed else Color(0xFF222938))
                                .border(
                                    width = if (isBoostFoc || audioBoostPercent > 100) 1.5.dp else 0.dp,
                                    color = if (isBoostFoc) Color.White else if (audioBoostPercent > 100) Color(0xFF00E676) else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .focusable(interactionSource = boostInter)
                                .clickable(interactionSource = boostInter, indication = null) {
                                    cycleAudioBoost()
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "🔊 ÂM LƯỢNG: ${audioBoostPercent}%",
                                color = if (audioBoostPercent > 100) Color(0xFF00E676) else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // ⏲️ Nút Hẹn Giờ Ngủ Thông Minh
                        val sleepInter = remember { MutableInteractionSource() }
                        val isSleepFoc by sleepInter.collectIsFocusedAsState()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isSleepFoc) AccentRed else Color(0xFF222938))
                                .border(
                                    width = if (isSleepFoc || sleepTimerMinutes > 0) 1.5.dp else 0.dp,
                                    color = if (isSleepFoc) Color.White else if (sleepTimerMinutes > 0) Color(0xFFFFD600) else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .focusable(interactionSource = sleepInter)
                                .clickable(interactionSource = sleepInter, indication = null) {
                                    cycleSleepTimer()
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = if (sleepTimerMinutes > 0) "⏲️ HẸN GIỜ: ${sleepTimerMinutes}P" else "⏲️ HẸN GIỜ: TẮT",
                                color = if (sleepTimerMinutes > 0) Color(0xFFFFD600) else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 🔒 Nút Khóa Cảm Ứng Trẻ Em (Bé An cầm máy không bị bấm nhầm)
                        val lockInter = remember { MutableInteractionSource() }
                        val isLockFoc by lockInter.collectIsFocusedAsState()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isLockFoc) AccentRed else Color(0xFF222938))
                                .border(
                                    width = if (isLockFoc) 1.5.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .focusable(interactionSource = lockInter)
                                .clickable(interactionSource = lockInter, indication = null) {
                                    isTouchLocked = true
                                    showControls = false
                                    showUnlockPill = true
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "🔒 KHÓA TRẺ EM",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 🌆 Nút Lọc Ánh Sáng Xanh Ban Đêm (Bảo vệ mắt Bé An & Gia Đình)
                        val nightInter = remember { MutableInteractionSource() }
                        val isNightFoc by nightInter.collectIsFocusedAsState()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isNightFoc) AccentRed else if (isNightWarmMode) Color(0xFF3E2723) else Color(0xFF222938))
                                .border(
                                    width = if (isNightFoc || isNightWarmMode) 1.5.dp else 0.dp,
                                    color = if (isNightFoc) Color.White else if (isNightWarmMode) Color(0xFFFFD600) else Color.Transparent,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .focusable(interactionSource = nightInter)
                                .clickable(interactionSource = nightInter, indication = null) {
                                    isNightWarmMode = !isNightWarmMode
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = if (isNightWarmMode) "🌆 DỊU MẮT: BẬT" else "🌆 DỊU MẮT",
                                color = if (isNightWarmMode) Color(0xFFFFD600) else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 🌙 Nút Tắt Đèn Màn Hình Chỉ Nghe Tiếng
                        val screenOffInter = remember { MutableInteractionSource() }
                        val isScreenOffFoc by screenOffInter.collectIsFocusedAsState()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isScreenOffFoc) AccentRed else Color(0xFF222938))
                                .border(
                                    width = if (isScreenOffFoc) 1.5.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .focusable(interactionSource = screenOffInter)
                                .clickable(interactionSource = screenOffInter, indication = null) {
                                    isScreenOffAudioMode = true
                                    showControls = false
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "🌙 TẮT MÀN HÌNH",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // 🖼️ Nút Thu Nhỏ Cửa Sổ Nổi Hình-Trong-Hình (PiP trên Máy tính bảng & Điện thoại)
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            val pipInter = remember { MutableInteractionSource() }
                            val isPipFoc by pipInter.collectIsFocusedAsState()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(if (isPipFoc) AccentRed else Color(0xFF222938))
                                    .border(
                                        width = if (isPipFoc) 1.5.dp else 0.dp,
                                        color = Color.White,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .focusable(interactionSource = pipInter)
                                    .clickable(interactionSource = pipInter, indication = null) {
                                        try {
                                            val act = context as? Activity
                                            if (act != null) {
                                                showControls = false
                                                val params = android.app.PictureInPictureParams.Builder()
                                                    .setAspectRatio(android.util.Rational(16, 9))
                                                    .build()
                                                act.enterPictureInPictureMode(params)
                                            }
                                        } catch (_: Exception) {}
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "🖼️ CỬA SỔ NỔI (PiP)",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Nút Tỷ Lệ Màn Hình / Full Màn Hình (Chạm hoặc D-Pad trên TV)
                        val aspectInter = remember { MutableInteractionSource() }
                        val isAspectFoc by aspectInter.collectIsFocusedAsState()

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isAspectFoc) AccentRed else Color(0xFF222938))
                                .border(
                                    width = if (isAspectFoc) 1.5.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .focusable(interactionSource = aspectInter)
                                .clickable(interactionSource = aspectInter, indication = null) {
                                    cycleAspectMode()
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = when (currentAspectMode) {
                                    VideoAspectMode.ZOOM -> "🌟 MÀN HÌNH: TRÀN VIỀN (100%)"
                                    VideoAspectMode.FILL -> "↔️ MÀN HÌNH: CĂNG ĐẦY (FILL)"
                                    VideoAspectMode.FIT -> "📺 MÀN HÌNH: CHUẨN GỐC (FIT)"
                                },
                                color = if (currentAspectMode == VideoAspectMode.ZOOM) Color(0xFF00E676) else Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Quality Switcher Button on HUD
                        val qualInter = remember { MutableInteractionSource() }
                        val isQualFoc by qualInter.collectIsFocusedAsState()

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isQualFoc) AccentRed else Color(0xFF222938))
                                .border(
                                    width = if (isQualFoc) 1.5.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .focusable(interactionSource = qualInter)
                                .clickable(interactionSource = qualInter, indication = null) {
                                    showQualityMenu = true
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "BĂNG THÔNG: $selectedQualityMode",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Box(
                            modifier = Modifier
                                .background(Color(0xFF1F2430), RoundedCornerShape(4.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = videoResolution,
                                color = Color(0xFF00E676),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
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
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    showControls = true
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
                    // 📋 Thanh Ruy-Băng Chọn Kênh Nhanh (Quick-Zapping Channel Ribbon ngay trong màn phát)
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
                                        .focusable(interactionSource = chInter)
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

                    // Quick Fast-Forward / Rewind Chips, Live Channel Switcher & Playback Speed
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
                                onClick = {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    showControls = true
                                }
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
                                onClick = {
                                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    showControls = true
                                }
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
                        // Time Progress
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

                    // D-Pad Remote Legend
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
                        GuideItem(key = "BACK", desc = "Thoát")
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
    onClick: () -> Unit
) {
    val inter = remember { MutableInteractionSource() }
    val isFoc by inter.collectIsFocusedAsState()

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (isFoc) AccentRed else if (highlight) Color(0xFF222938) else Color(0x33FFFFFF))
            .border(
                width = if (isFoc) 1.5.dp else 1.dp,
                color = if (isFoc) Color.White else Color(0x44FFFFFF),
                shape = RoundedCornerShape(6.dp)
            )
            .focusable(interactionSource = inter)
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