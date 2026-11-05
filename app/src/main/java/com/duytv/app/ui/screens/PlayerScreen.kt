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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.duytv.app.data.WatchHistoryManager
import com.duytv.app.ui.theme.AccentRed
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.util.Locale

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

    // Keep TV screen awake during playback
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            // Optimized Low-Memory LoadControl for MediaTek MT5891 (RAM < 25MB)
            val allocator = DefaultAllocator(true, 32 * 1024)
            val loadControl = DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(5_000, 15_000, 1_500, 2_500)
                .setTargetBufferBytes(16 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val renderersFactory = DefaultRenderersFactory(context).apply {
                setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                setEnableDecoderFallback(true)
            }

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 14; Google TV) AppleWebKit/537.36 DuyTV/1.0")
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(20000)
                .setAllowCrossProtocolRedirects(true)

            val mediaItemBuilder = MediaItem.Builder().setUri(streamUrl)

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
                .build()
                .apply {
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

    // Track playback position & Save history
    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(1000)
            if (exoPlayer.isPlaying && pendingSeekPosMs == null) {
                currentPosMs = exoPlayer.currentPosition
                totalDurationMs = maxOf(1L, exoPlayer.duration)
                WatchHistoryManager.saveProgress(
                    context = context,
                    slug = slug,
                    movieTitle = movieTitle,
                    episodeName = episodeName,
                    streamUrl = streamUrl,
                    posterUrl = posterUrl,
                    positionMs = currentPosMs,
                    durationMs = totalDurationMs
                )
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
                    WatchHistoryManager.saveProgress(
                        context, slug, movieTitle, episodeName, streamUrl, posterUrl, pos, dur
                    )
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

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = (playbackState == Player.STATE_BUFFERING)
                if (playbackState == Player.STATE_READY) {
                    totalDurationMs = maxOf(1L, exoPlayer.duration)
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                val width = videoSize.width
                val height = videoSize.height
                videoResolution = when {
                    width >= 7680 || height >= 4320 -> "8K Ultra HD"
                    width >= 3840 || height >= 2160 -> "4K Ultra HD"
                    width >= 2560 || height >= 1440 -> "2K QHD"
                    width >= 1920 || height >= 1080 -> "1080p FHD"
                    width >= 1280 || height >= 720 -> "720p HD"
                    else -> if (width > 0) "${width}x${height}" else "Auto HD"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                errorMessage = "Lỗi đường truyền: ${error.errorCodeName}. Đang tự động kết nối lại..."
                exoPlayer.prepare()
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            val pos = exoPlayer.currentPosition
            val dur = maxOf(1L, exoPlayer.duration)
            WatchHistoryManager.saveProgress(
                context, slug, movieTitle, episodeName, streamUrl, posterUrl, pos, dur
            )
            exoPlayer.removeListener(listener)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    fun applyQualitySetting(mode: String) {
        selectedQualityMode = mode
        exoPlayer.trackSelectionParameters = when (mode) {
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

    val currentShowQualityMenu by rememberUpdatedState(showQualityMenu)
    val currentPendingSeekPosMs by rememberUpdatedState(pendingSeekPosMs)
    val currentIsPlaying by rememberUpdatedState(isPlaying)

    // Bắt toàn diện 100% phím bấm phần cứng từ Remote Sharp TV ở cấp độ Activity
    DisposableEffect(exoPlayer) {
        MainActivity.onKeyEventListener = { event ->
            val keyCode = event.keyCode
            val action = event.action

            val isDedicatedFastForward = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_FORWARD,
                KeyEvent.KEYCODE_FORWARD,
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_PAGE_DOWN,
                KeyEvent.KEYCODE_CHANNEL_UP -> true
                else -> false
            }

            val isDedicatedRewind = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_PAGE_UP,
                KeyEvent.KEYCODE_CHANNEL_DOWN -> true
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
                        showControls = !showControls
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
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
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                            showControls = !showControls
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
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

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
                        Triple("AUTO", "⚡ TỰ ĐỘNG (Thích ứng mạng)", "Tự động đẩy lên 4K/2K nếu mạng tốt"),
                        Triple("4K", "💎 4K Ultra HD (2160p)", "Ưu tiên độ nét tối đa (yêu cầu mạng > 30Mbps)"),
                        Triple("2K", "🌟 2K QHD (1440p)", "Cân bằng siêu nét cho màn hình lớn"),
                        Triple("1080P", "📺 Full HD (1080p)", "Chuẩn nét ổn định & mượt mà"),
                        Triple("720P", "⚡ HD (720p)", "Tiết kiệm băng thông tối đa")
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for ((key, title, desc) in qualityOptions) {
                            val isSelected = (selectedQualityMode == key)
                            val qInter = remember { MutableInteractionSource() }
                            val isQFoc by qInter.collectIsFocusedAsState()

                            Row(
                                modifier = Modifier
                                    .width(540.dp)
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
        AnimatedVisibility(
            visible = showControls && !showQualityMenu,
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
                        .padding(horizontal = 48.dp, vertical = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = movieTitle,
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Đang phát: $episodeName • Độ phân giải: $videoResolution • Chế độ: $selectedQualityMode",
                            color = Color(0xFFA0AAB8),
                            fontSize = 14.sp
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
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
                        .padding(horizontal = 48.dp, vertical = 24.dp)
                ) {
                    // Quick Fast-Forward / Rewind Chips & Playback Speed
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        QuickSeekPill(text = "⏪ -5p", onClick = { triggerSeek(-300) })
                        Spacer(modifier = Modifier.width(8.dp))
                        QuickSeekPill(text = "⏪ -1p", onClick = { triggerSeek(-60) })
                        Spacer(modifier = Modifier.width(8.dp))
                        QuickSeekPill(text = "⏪ -10s", onClick = { triggerSeek(-10) })
                        Spacer(modifier = Modifier.width(16.dp))
                        QuickSeekPill(
                            text = if (isPlaying) "⏸️ TẠM DỪNG" else "▶️ PHÁT",
                            highlight = true,
                            onClick = {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                                showControls = true
                            }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        QuickSeekPill(text = "⏩ +10s", onClick = { triggerSeek(10) })
                        Spacer(modifier = Modifier.width(8.dp))
                        QuickSeekPill(text = "⏩ +1p", onClick = { triggerSeek(60) })
                        Spacer(modifier = Modifier.width(8.dp))
                        QuickSeekPill(text = "⏩ +5p", onClick = { triggerSeek(300) })
                        Spacer(modifier = Modifier.width(16.dp))
                        QuickSeekPill(
                            text = "⚡ ${if (playbackSpeed == 1.0f) "1.0x" else "${playbackSpeed}x"}",
                            onClick = { cyclePlaybackSpeed() }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

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

                    Spacer(modifier = Modifier.height(14.dp))

                    // D-Pad Remote Legend
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GuideItem(key = "OK / PLAY", desc = "Tạm dừng / Phát")
                        Spacer(modifier = Modifier.width(16.dp))
                        GuideItem(key = "◄ / ►", desc = "Tua tăng tốc (10s - 1p - 5p)")
                        Spacer(modifier = Modifier.width(16.dp))
                        GuideItem(key = "⚡ 1.0x - 2.0x", desc = "Tốc độ phát")
                        Spacer(modifier = Modifier.width(16.dp))
                        GuideItem(key = "MENU", desc = "Băng thông (4K/2K/Auto)")
                        Spacer(modifier = Modifier.width(16.dp))
                        GuideItem(key = "BACK", desc = "Thoát xem")
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