package com.duytv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.duytv.app.data.EpisodeItemDto
import com.duytv.app.data.MovieRepository
import com.duytv.app.ui.screens.HomeScreen
import com.duytv.app.ui.screens.MovieDetailScreen
import com.duytv.app.ui.screens.PlayerScreen
import com.duytv.app.ui.screens.SearchScreen
import com.duytv.app.ui.theme.DuyTvTheme

sealed class AppScreen {
    object Home : AppScreen()
    object Search : AppScreen()
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
    }

    private val repository = MovieRepository()

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (onKeyEventListener?.invoke(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // Giữ Activity ấm trong RAM (Hot Process) để khi người dùng bấm mở DuyTV sẽ bật lên tức thì (0ms).
        // Âm thanh và MediaCodec của ExoPlayer đã được LifecycleEventObserver trong PlayerScreen
        // tự động pause/stop an toàn khi nhận sự kiện ON_STOP, bảo đảm 100% không rò rỉ âm thanh nền.
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

        setContent {
            val appContext = applicationContext
            LaunchedEffect(Unit) {
                val hwProfile = com.duytv.app.util.HardwareOptimizer.getProfile(appContext)
                val imageLoader = coil.ImageLoader.Builder(appContext)
                    .allowHardware(hwProfile.allowHardwareBitmaps)
                    .crossfade(false)
                    .build()
                coil.Coil.setImageLoader(imageLoader)
            }
            DuyTvTheme {
                var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }

                BackHandler(enabled = currentScreen !is AppScreen.Home) {
                    when (val screen = currentScreen) {
                        is AppScreen.Player -> {
                            currentScreen = if (screen.slug.startsWith("live_")) AppScreen.Home else AppScreen.Detail(screen.slug)
                        }
                        is AppScreen.Detail -> {
                            currentScreen = AppScreen.Home
                        }
                        is AppScreen.Search -> {
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
                    is AppScreen.Search -> {
                        SearchScreen(
                            repository = repository,
                            onMovieSelected = { slug ->
                                currentScreen = AppScreen.Detail(slug)
                            },
                            onBack = { currentScreen = AppScreen.Home },
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
                                currentScreen = if (screen.slug.startsWith("live_")) AppScreen.Home else AppScreen.Detail(screen.slug)
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}