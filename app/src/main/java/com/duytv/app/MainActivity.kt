package com.duytv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        val initialPositionMs: Long = 0L
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
        // Khi người dùng bấm HOME hoặc chuyển sang app khác:
        // Đóng hẳn activity để dập tắt 100% âm thanh nền và giải phóng RAM ("thoát app tương đương với tắt app")
        if (!isChangingConfigurations) {
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DuyTvTheme {
                var currentScreen by remember { mutableStateOf<AppScreen>(AppScreen.Home) }

                BackHandler(enabled = currentScreen !is AppScreen.Home) {
                    when (val screen = currentScreen) {
                        is AppScreen.Player -> {
                            currentScreen = AppScreen.Detail(screen.slug)
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
                            onPlayEpisode = { slug, movieTitle, episodeName, streamUrl, posterUrl, subtitleUrl ->
                                currentScreen = AppScreen.Player(
                                    slug = slug,
                                    movieTitle = movieTitle,
                                    episodeName = episodeName,
                                    streamUrl = streamUrl,
                                    posterUrl = posterUrl,
                                    subtitleUrl = subtitleUrl
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
                            onClose = { currentScreen = AppScreen.Detail(screen.slug) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}