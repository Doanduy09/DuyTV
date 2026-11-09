package com.duytv.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.duytv.app.data.*
import com.duytv.app.ui.components.FootballMatchCard
import com.duytv.app.ui.components.MovieCard
import com.duytv.app.ui.components.TvBanner
import com.duytv.app.ui.components.TvChannelCard
import com.duytv.app.ui.theme.*
import com.duytv.app.util.AppUpdateManager
import com.duytv.app.util.AppVersionInfo
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

data class NavCategory(
    val id: String,
    val title: String,
    val endpoint: String
)

val NAV_CATEGORIES = listOf(
    NavCategory("all", "🔥 TẤT CẢ", ""),
    NavCategory("tv-live", "⚽ BÓNG ĐÁ & TV", "tv-live"),
    NavCategory("co-trang", "🏯 CỔ TRANG & TVB", "the-loai/co-trang"),
    NavCategory("phim-chieu-rap", "🎟️ PHIM CHIẾU RẠP", "phim-chieu-rap"),
    NavCategory("torrent-4k", "💎 4K CINEMA TORRENT", "torrent-4k"),
    NavCategory("phim-bo", "📺 PHIM BỘ", "phim-bo"),
    NavCategory("phim-le", "🎬 PHIM LẺ", "phim-le"),
    NavCategory("hoat-hinh", "🌸 HOẠT HÌNH", "hoat-hinh"),
    NavCategory("tv-shows", "🎙️ TV SHOWS", "tv-shows"),
    NavCategory("hanh-dong", "⚡ HÀNH ĐỘNG", "the-loai/hanh-dong"),
    NavCategory("kinh-di", "👻 KINH DỊ", "the-loai/kinh-di"),
    NavCategory("vien-tuong", "🚀 VIỄN TƯỞNG", "the-loai/vien-tuong"),
    NavCategory("hai-huoc", "😂 HÀI HƯỚC", "the-loai/hai-huoc")
)

@Composable
fun HomeScreen(
    repository: MovieRepository,
    onMovieSelected: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onResumePlayback: (WatchHistoryItem) -> Unit,
    onPlayLiveStream: (title: String, streamUrl: String, posterUrl: String?, backupUrls: List<String>) -> Unit = { _, _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Khởi tạo ngay tức thì từ bộ nhớ đệm L1 RAM / L2 Disk để mở app không độ trễ (< 30ms)
    val cachedHome = AppCacheManager.cachedHomeData
    val initialTvSports = AppCacheManager.cachedTvSports ?: TvSportsData(channels = AppCacheManager.activeChannels)
    val hasCache = (cachedHome != null && cachedHome.latestMovies.isNotEmpty()) || initialTvSports.channels.isNotEmpty()

    // Luôn mở giao diện ngay lập tức mà không chặn bằng màn hình xoay xoay (Stale-While-Revalidate)
    var isLoading by remember { mutableStateOf(false) }
    var isSilentSyncing by remember { mutableStateOf(!hasCache) }
    var selectedCategory by remember { mutableStateOf(NAV_CATEGORIES.first()) }

    // Swimlane lists for "TẤT CẢ" view - Nạp trực tiếp từ Cache
    var latestMovies by remember { mutableStateOf<List<MovieItemDto>>(cachedHome?.latestMovies ?: emptyList()) }
    var cinemaMovies by remember { mutableStateOf<List<MovieItemDto>>(cachedHome?.cinemaMovies ?: emptyList()) }
    var torrentMovies by remember { mutableStateOf<List<MovieItemDto>>(cachedHome?.torrentMovies ?: emptyList()) }
    var seriesMovies by remember { mutableStateOf<List<MovieItemDto>>(cachedHome?.seriesMovies ?: emptyList()) }
    var singleMovies by remember { mutableStateOf<List<MovieItemDto>>(cachedHome?.singleMovies ?: emptyList()) }
    var animeMovies by remember { mutableStateOf<List<MovieItemDto>>(cachedHome?.animeMovies ?: emptyList()) }
    var watchHistory by remember { mutableStateOf<List<WatchHistoryItem>>(emptyList()) }

    // Dữ liệu Bóng đá & TV Live đa luồng
    var tvSportsData by remember { mutableStateOf<TvSportsData?>(initialTvSports) }
    var currentHeartbeatMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var selectedMatchForStreams by remember { mutableStateOf<FootballMatchGroup?>(null) }
    var tvSubFilter by remember { mutableStateOf("all") }

    // 1 duy nhất Heartbeat Clock coroutine (cập nhật mỗi 30 giây để tránh recompose toàn màn hình mỗi giây trên chip TV)
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            currentHeartbeatMillis = System.currentTimeMillis()
        }
    }

    // Specific Category list & pagination state
    var categoryMovies by remember { mutableStateOf<List<MovieItemDto>>(emptyList()) }
    var categoryPage by remember { mutableIntStateOf(1) }
    var isCategoryLoadingMore by remember { mutableStateOf(false) }
    var showSourceSettingsDialog by remember { mutableStateOf(false) }
    var showSportsSourceDialog by remember { mutableStateOf(false) }
    var showAppUpdateDialog by remember { mutableStateOf(false) }
    var remoteUpdateInfo by remember { mutableStateOf<AppVersionInfo?>(null) }
    var favoriteMoviesList by remember { mutableStateOf(AppCacheManager.favoriteMovies) }
    val currentVersionCode = remember { AppUpdateManager.getCurrentVersionCode(context) }
    val currentVersionName = remember { AppUpdateManager.getCurrentVersionName(context) }

    val currentTime = remember(currentHeartbeatMillis / 60_000L) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.format(Date(currentHeartbeatMillis))
    }

    fun reloadAllMovies(silent: Boolean = true, forceRefreshSports: Boolean = false, showSuccessToast: Boolean = false) {
        scope.launch {
            if (!silent) {
                isLoading = true
            } else {
                isSilentSyncing = true
            }
            try {
                if (selectedCategory.id == "all") {
                    val latestDef = async { try { repository.getNewUpdatedMovies(1) } catch (_: Exception) { emptyList() } }
                    val cinemaDef = async { try { repository.getMoviesByCategory("phim-chieu-rap", 1) } catch (_: Exception) { emptyList() } }
                    val torrentDef = async { try { repository.getTop4KTorrents(1) } catch (_: Exception) { emptyList() } }
                    val seriesDef = async { try { repository.getMoviesByCategory("phim-bo", 1) } catch (_: Exception) { emptyList() } }
                    val singleDef = async { try { repository.getMoviesByCategory("phim-le", 1) } catch (_: Exception) { emptyList() } }
                    val animeDef = async { try { repository.getMoviesByCategory("hoat-hinh", 1) } catch (_: Exception) { emptyList() } }
                    val tvSportsDef = async { try { repository.getTvAndSportsData(forceRefresh = forceRefreshSports) } catch (_: Exception) { TvSportsData() } }

                    val resLatest = latestDef.await()
                    val resCinema = cinemaDef.await()
                    val resTorrent = torrentDef.await()
                    val resSeries = seriesDef.await()
                    val resSingle = singleDef.await()
                    val resAnime = animeDef.await()
                    val resTvSports = tvSportsDef.await()

                    // Kiểm tra diff trước khi gán để chống nhảy focus trên remote Android TV
                    if (resLatest.isNotEmpty() && resLatest != latestMovies) latestMovies = resLatest
                    if (resCinema.isNotEmpty() && resCinema != cinemaMovies) cinemaMovies = resCinema
                    if (resTorrent.isNotEmpty() && resTorrent != torrentMovies) torrentMovies = resTorrent
                    if (resSeries.isNotEmpty() && resSeries != seriesMovies) seriesMovies = resSeries
                    if (resSingle.isNotEmpty() && resSingle != singleMovies) singleMovies = resSingle
                    if (resAnime.isNotEmpty() && resAnime != animeMovies) animeMovies = resAnime
                    if (resTvSports.channels.isNotEmpty() || resTvSports.matches.isNotEmpty()) {
                        if (resTvSports != tvSportsData) {
                            tvSportsData = resTvSports
                            AppCacheManager.saveTvSports(resTvSports, context)
                        }
                    }

                    // Lưu snapshot vào cache L1/L2
                    AppCacheManager.saveHomeData(
                        HomeCacheData(
                            latestMovies = latestMovies,
                            cinemaMovies = cinemaMovies,
                            torrentMovies = torrentMovies,
                            seriesMovies = seriesMovies,
                            singleMovies = singleMovies,
                            animeMovies = animeMovies
                        ),
                        context
                    )
                } else if (selectedCategory.id == "tv-live") {
                    val res = try { repository.getTvAndSportsData(forceRefresh = true) } catch (_: Exception) { TvSportsData() }
                    if (res.channels.isNotEmpty() || res.matches.isNotEmpty()) {
                        tvSportsData = res
                        AppCacheManager.saveTvSports(res, context)
                    }
                } else {
                    categoryPage = 1
                    categoryMovies = try { repository.getMoviesByCategory(selectedCategory.endpoint, 1) } catch (_: Exception) { emptyList() }
                }
                if (showSuccessToast) {
                    Toast.makeText(context, "✅ Đã cập nhật kho phim mới & lịch bóng đá trực tiếp!", Toast.LENGTH_SHORT).show()
                }
            } finally {
                isLoading = false
                isSilentSyncing = false
            }
        }
    }

    fun reloadSportsOnly() {
        scope.launch {
            isSilentSyncing = true
            try {
                val res = try { repository.getTvAndSportsData(forceRefresh = true) } catch (_: Exception) { TvSportsData() }
                if (res.channels.isNotEmpty() || res.matches.isNotEmpty()) {
                    tvSportsData = res
                    AppCacheManager.saveTvSports(res, context)
                }
            } finally {
                isSilentSyncing = false
            }
        }
    }

    // [✅ ĐÃ CHUẨN - CẤM SỬA]: Khởi tạo lastAutoSyncMillis = System.currentTimeMillis() để tránh ON_RESUME
    // chạy trùng lặp reloadAllMovies() lần thứ 2 ngay lúc vừa mở app.
    var lastAutoSyncMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastAutoPromptedVersionCode by remember { mutableIntStateOf(currentVersionCode) }

    fun checkAndPromptGitHubUpdate() {
        scope.launch {
            val latestVer = AppUpdateManager.checkForUpdate()
            if (latestVer != null) {
                remoteUpdateInfo = latestVer
                if (latestVer.versionCode > currentVersionCode && latestVer.versionCode > lastAutoPromptedVersionCode) {
                    lastAutoPromptedVersionCode = latestVer.versionCode
                    showAppUpdateDialog = true
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        AppCacheManager.init(context, MovieRepository.getVerifiedOfficialChannels())
        RemoteConfigManager.loadLocalCustomSources(context)
        watchHistory = WatchHistoryManager.getHistory(context)
        favoriteMoviesList = AppCacheManager.favoriteMovies
        scope.launch {
            RemoteConfigManager.fetchRemoteConfig()
            lastAutoSyncMillis = System.currentTimeMillis()
            reloadAllMovies(silent = true, forceRefreshSports = true)
        }
    }

    // Tự động đồng bộ ngầm định kỳ (Auto-Sync Daemon):
    // - Mỗi 3 phút kiểm tra bản cập nhật mới trên GitHub + cập nhật lịch & luồng bóng đá trực tiếp
    // - Mỗi 6 phút cập nhật phim mới từ tất cả các trang web
    LaunchedEffect(Unit) {
        var cycle = 0
        while (true) {
            delay(180_000L) // 3 phút
            cycle++
            lastAutoSyncMillis = System.currentTimeMillis()
            checkAndPromptGitHubUpdate()
            if (cycle % 2 == 0) {
                reloadAllMovies(silent = true, forceRefreshSports = true)
            } else {
                reloadSportsOnly()
            }
        }
    }

    // Tự động đồng bộ thông minh mỗi khi mở lại ứng dụng (ON_RESUME) trên Tivi / Box
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                watchHistory = WatchHistoryManager.getHistory(context)
                favoriteMoviesList = AppCacheManager.favoriteMovies
                checkAndPromptGitHubUpdate()
                val now = System.currentTimeMillis()
                if (now - lastAutoSyncMillis > 60_000L) {
                    lastAutoSyncMillis = now
                    reloadAllMovies(silent = true, forceRefreshSports = true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // When switching category tabs
    fun switchCategory(category: NavCategory) {
        selectedCategory = category
        if (category.id == "tv-live") {
            if (tvSportsData == null) {
                scope.launch {
                    isCategoryLoadingMore = true
                    tvSportsData = try { repository.getTvAndSportsData() } catch (_: Exception) { TvSportsData() }
                    isCategoryLoadingMore = false
                }
            }
        } else if (category.id != "all") {
            scope.launch {
                isCategoryLoadingMore = true
                categoryPage = 1
                categoryMovies = if (category.id == "torrent-4k") {
                    repository.getTop4KTorrents(1)
                } else {
                    repository.getMoviesByCategory(category.endpoint, 1)
                }
                isCategoryLoadingMore = false
            }
        }
    }

    // Function to load more movies for specific category
    fun loadMoreCategoryMovies() {
        if (isCategoryLoadingMore) return
        scope.launch {
            isCategoryLoadingMore = true
            val nextPage = categoryPage + 1
            val newItems = if (selectedCategory.id == "torrent-4k") {
                repository.getTop4KTorrents(nextPage)
            } else {
                repository.getMoviesByCategory(selectedCategory.endpoint, nextPage)
            }
            if (newItems.isNotEmpty()) {
                categoryMovies = categoryMovies + newItems
                categoryPage = nextPage
            }
            isCategoryLoadingMore = false
        }
    }

    val config = LocalConfiguration.current
    val screenWidthDp = config.screenWidthDp
    val screenHeightDp = config.screenHeightDp
    val screenConfig = remember(context, screenWidthDp, screenHeightDp, config.orientation) {
        com.duytv.app.util.HardwareOptimizer.detectScreenConfig(context, screenWidthDp, screenHeightDp)
    }
    val isCompactScreen = screenWidthDp < 960
    val horizontalPad = screenConfig.horizontalPaddingDp.dp
    val isTvDeviceCheck = screenConfig.isTv
    val verticalPad = screenConfig.verticalPaddingDp.dp

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = horizontalPad, vertical = verticalPad),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Top TV Header (Brand, Status, Search, Clock)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = "DUY",
                                color = AccentRed,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "TV",
                                color = TextWhite,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Black
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(CardBg, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isSilentSyncing || isLoading) "⚡ ĐANG ĐỒNG BỘ..." else "4K • ${screenConfig.screenSummaryBadge}",
                                    color = if (isSilentSyncing || isLoading) Color(0xFFFFD600) else Color(0xFF00E676),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Search Button
                            val searchInter = remember { MutableInteractionSource() }
                            val searchFoc by searchInter.collectIsFocusedAsState()
                            val searchPress by searchInter.collectIsPressedAsState()
                            val searchActive = searchFoc || searchPress

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (searchActive) AccentRed else CardBg)
                                    .border(
                                        width = if (searchActive) 1.5.dp else 0.dp,
                                        color = Color.White,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable(interactionSource = searchInter, indication = null, onClick = onOpenSearch)
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Tìm kiếm",
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "TÌM KIẾM",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Nút 1-Chạm Mở Nhanh Kênh Truyền Hình Vừa Xem (#16)
                            val lastWatchedChName = AppCacheManager.lastWatchedChannelName
                            val lastWatchedChObj = remember(lastWatchedChName, tvSportsData) {
                                if (lastWatchedChName.isBlank()) null
                                else AppCacheManager.activeChannels.firstOrNull {
                                    it.name.equals(lastWatchedChName, ignoreCase = true)
                                }
                            }
                            if (lastWatchedChObj != null) {
                                val lastChInter = remember { MutableInteractionSource() }
                                val lastChFoc by lastChInter.collectIsFocusedAsState()
                                val lastChPress by lastChInter.collectIsPressedAsState()
                                val lastChActive = lastChFoc || lastChPress

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .widthIn(max = 175.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (lastChActive) AccentRed else Color(0xFF065F46))
                                        .border(
                                            width = if (lastChActive) 1.5.dp else 1.dp,
                                            color = if (lastChActive) Color.White else Color(0xFF10B981),
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable(interactionSource = lastChInter, indication = null) {
                                            val ranked = AppCacheManager.rankHealthyStreams(lastWatchedChObj.name, lastWatchedChObj.allStreamUrls())
                                            val primary = ranked.firstOrNull() ?: lastWatchedChObj.streamUrl
                                            onPlayLiveStream(lastWatchedChObj.name, primary, lastWatchedChObj.logoUrl, ranked.drop(1))
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "📺 ${lastWatchedChObj.name}",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            }

                            // Manual Refresh New Movies & Football Button (Cập nhật phim mới thủ công)
                            val refreshMoviesInter = remember { MutableInteractionSource() }
                            val refreshMoviesFoc by refreshMoviesInter.collectIsFocusedAsState()
                            val refreshMoviesPress by refreshMoviesInter.collectIsPressedAsState()
                            val refreshMoviesActive = refreshMoviesFoc || refreshMoviesPress
                            val isRefreshingNow = isLoading || isSilentSyncing

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when {
                                            refreshMoviesActive -> AccentRed
                                            isRefreshingNow -> Color(0xFF065F46)
                                            else -> Color(0xFF1E293B)
                                        }
                                    )
                                    .border(
                                        width = if (refreshMoviesActive) 1.5.dp else 1.dp,
                                        color = if (refreshMoviesActive) Color.White else Color(0xFF00E676),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable(interactionSource = refreshMoviesInter, indication = null) {
                                        lastAutoSyncMillis = System.currentTimeMillis()
                                        reloadAllMovies(silent = true, forceRefreshSports = true, showSuccessToast = true)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isRefreshingNow) "⏳ ĐANG TẢI..." else "🔄 LÀM MỚI",
                                    color = if (refreshMoviesActive) Color.White else Color(0xFF00E676),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Source Settings Button
                            val sourceInter = remember { MutableInteractionSource() }
                            val sourceFoc by sourceInter.collectIsFocusedAsState()
                            val sourcePress by sourceInter.collectIsPressedAsState()
                            val sourceActive = sourceFoc || sourcePress

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sourceActive) AccentRed else CardBg)
                                    .border(
                                        width = if (sourceActive) 1.5.dp else 0.dp,
                                        color = Color.White,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable(interactionSource = sourceInter, indication = null) {
                                        showSourceSettingsDialog = true
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Cài đặt nguồn",
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "NGUỒN PHIM",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // Sports Source Settings Button
                            val sportsSourceInter = remember { MutableInteractionSource() }
                            val sportsSourceFoc by sportsSourceInter.collectIsFocusedAsState()
                            val sportsSourcePress by sportsSourceInter.collectIsPressedAsState()
                            val sportsSourceActive = sportsSourceFoc || sportsSourcePress

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sportsSourceActive) AccentRed else CardBg)
                                    .border(
                                        width = if (sportsSourceActive) 1.5.dp else 0.dp,
                                        color = Color.White,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable(interactionSource = sportsSourceInter, indication = null) {
                                        showSportsSourceDialog = true
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Thêm nguồn bóng đá",
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "BÓNG ĐÁ",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(6.dp))

                            // 🚀 In-App OTA Self-Update Button (Cập nhật trực tiếp ngay trên App)
                            val updateInter = remember { MutableInteractionSource() }
                            val updateFoc by updateInter.collectIsFocusedAsState()
                            val updatePress by updateInter.collectIsPressedAsState()
                            val updateActive = updateFoc || updatePress
                            val hasNewUpdate = remoteUpdateInfo != null && remoteUpdateInfo!!.versionCode > currentVersionCode

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when {
                                            updateActive -> AccentRed
                                            hasNewUpdate -> Color(0xFF00C853)
                                            else -> Color(0xFF1E293B)
                                        }
                                    )
                                    .border(
                                        width = 1.dp,
                                        color = if (updateActive) Color.White else if (hasNewUpdate) Color(0xFF00E676) else Color(0xFF38BDF8),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable(interactionSource = updateInter, indication = null) {
                                        showAppUpdateDialog = true
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (hasNewUpdate) {
                                        "🚀 BẢN MỚI v${remoteUpdateInfo!!.versionName}"
                                    } else {
                                        "🔄 v$currentVersionName"
                                    },
                                    color = if (hasNewUpdate && !updateActive) Color.Black else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }

                        val isTv = remember(context) { com.duytv.app.util.HardwareOptimizer.isTvDevice(context) }
                        if (isTv && screenWidthDp >= 960) {
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = currentTime,
                                color = TextWhite,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // 2. Category Selector Bar (D-pad navigable tabs)
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(NAV_CATEGORIES) { cat ->
                            val isSelected = (selectedCategory.id == cat.id)
                            val catInter = remember { MutableInteractionSource() }
                            val isCatFoc by catInter.collectIsFocusedAsState()
                            val isCatPress by catInter.collectIsPressedAsState()
                            val isCatActive = isCatFoc || isCatPress

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        if (isCatActive) AccentRed
                                        else if (isSelected) Color(0xFF252B3B)
                                        else CardBg
                                    )
                                    .border(
                                        width = if (isCatActive) 2.dp else if (isSelected) 1.dp else 0.dp,
                                        color = if (isCatActive) Color.White else if (isSelected) AccentRed else Color.Transparent,
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                    .clickable(interactionSource = catInter, indication = null) {
                                        switchCategory(cat)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = cat.title,
                                    color = if (isCatActive || isSelected) TextWhite else TextDim,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected || isCatFoc) FontWeight.Bold else FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                // 3. MAIN CONTENT: "TẤT CẢ" view vs SPECIFIC CATEGORY view
                if (selectedCategory.id == "all") {
                    // Spotlight Hero Banner
                    if (latestMovies.isNotEmpty()) {
                        item {
                            TvBanner(
                                featuredMovie = latestMovies.firstOrNull(),
                                onPlayClick = { movie -> onMovieSelected(movie.slug) }
                            )
                        }
                    }

                    // Watch History / Continue Watching Row (if any)
                    if (watchHistory.isNotEmpty()) {
                        item {
                            Column {
                                Text(
                                    text = "⏱️ TIẾP TỤC XEM (TỰ NHỚ TẬP & PHÚT ĐANG COI)",
                                    color = TextWhite,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    items(watchHistory, key = { it.slug + it.episodeName }) { item ->
                                        val itemInter = remember { MutableInteractionSource() }
                                        val isFoc by itemInter.collectIsFocusedAsState()
                                        val totalSec = (item.positionMs / 1000L).coerceAtLeast(0L)
                                        val minStr = String.format(Locale.US, "%02d:%02d", totalSec / 60L, totalSec % 60L)
                                        val progressFraction = if (item.durationMs > 0L) {
                                            (item.positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0.06f, 1f)
                                        } else 0.15f

                                        Box(
                                            modifier = Modifier
                                                .width(225.dp)
                                                .height(136.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(CardBg)
                                                .border(
                                                    width = if (isFoc) 2.5.dp else 0.dp,
                                                    color = if (isFoc) AccentRed else Color.Transparent,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .clickable(interactionSource = itemInter, indication = null) {
                                                    onResumePlayback(item)
                                                }
                                        ) {
                                            val historyImgRequest = remember(item.posterUrl, context) {
                                                coil.request.ImageRequest.Builder(context)
                                                    .data(item.posterUrl)
                                                    .size(300, 450)
                                                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                                                    .allowHardware(false)
                                                    .crossfade(false)
                                                    .build()
                                            }
                                            AsyncImage(
                                                model = historyImgRequest,
                                                contentDescription = item.movieTitle,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color(0xA60A0E17))
                                            )

                                            // Nút ✕ xóa nhanh khỏi danh sách đang xem
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(6.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xCC1F2430))
                                                    .clickable {
                                                        WatchHistoryManager.removeHistoryItem(context, item.slug)
                                                        watchHistory = WatchHistoryManager.getHistory(context)
                                                    }
                                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    text = "✕",
                                                    color = Color(0xFFA0AAB8),
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }

                                            Column(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                                    Text(
                                                        text = item.movieTitle,
                                                        color = TextWhite,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Spacer(modifier = Modifier.height(2.dp))
                                                    Text(
                                                        text = "▶ Tập ${item.episodeName} • $minStr",
                                                        color = Color(0xFF00E676),
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                // Thanh tiến độ màu đỏ chuẩn Netflix dưới đáy thẻ
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(4.dp)
                                                        .background(Color(0x55FFFFFF))
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth(progressFraction)
                                                            .fillMaxHeight()
                                                            .background(AccentRed)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // ❤️ TỦ PHIM YÊU THÍCH ĐÃ LƯU
                    if (favoriteMoviesList.isNotEmpty()) {
                        item {
                            MovieSwimlaneWithMore(
                                title = "❤️ TỦ PHIM YÊU THÍCH ĐÃ LƯU (${favoriteMoviesList.size})",
                                movies = favoriteMoviesList,
                                onMovieClick = onMovieSelected,
                                onSeeAllClick = {
                                    if (favoriteMoviesList.isNotEmpty()) {
                                        onMovieSelected(favoriteMoviesList.first().slug)
                                    }
                                }
                            )
                        }
                    }

                    // Swimlane Live: ⚽ BÓNG ĐÁ TRỰC TIẾP & TRUYỀN HÌNH HD HÀNG NGANG
                    val sportsSnapshot = tvSportsData
                    if (sportsSnapshot != null && (sportsSnapshot.matches.isNotEmpty() || sportsSnapshot.channels.isNotEmpty())) {
                        val activeHomeMatches = sportsSnapshot.matches.filter {
                            MatchStateHelper.calculateMatchState(it.kickoffTime, currentHeartbeatMillis).status != MatchStatus.FINISHED
                        }
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "📺 TRUYỀN HÌNH TRỰC TIẾP & BÓNG ĐÁ HD",
                                            color = TextWhite,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFFFF1744), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("LIVE", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                                        }
                                    }

                                    val seeAllInter = remember { MutableInteractionSource() }
                                    val seeAllFoc by seeAllInter.collectIsFocusedAsState()
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (seeAllFoc) AccentRed else Color.Transparent)
                                            .clickable(interactionSource = seeAllInter, indication = null) {
                                                switchCategory(NAV_CATEGORIES.first { it.id == "tv-live" })
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "Xem tất cả (${activeHomeMatches.size} trận • ${sportsSnapshot.channels.size} kênh) >",
                                            color = if (seeAllFoc) Color.White else AccentRed,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Thanh Dock Kênh Truyền Hình Hàng Ngang (Phong cách Glass Dock)
                                if (sportsSnapshot.channels.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(Color(0x99121722))
                                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp))
                                            .padding(horizontal = 16.dp, vertical = 14.dp)
                                    ) {
                                        LazyRow(
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp)
                                        ) {
                                            items(sportsSnapshot.channels, key = { it.id }) { channel ->
                                                TvChannelCard(
                                                    channel = channel,
                                                    onClick = {
                                                        onPlayLiveStream(channel.name, channel.streamUrl, channel.logoUrl, channel.backupUrls)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }

                                // Hàng ngang Trận Cầu Đang Diễn Ra / Sắp Đá (Bỏ các trận đã kết thúc)
                                if (activeHomeMatches.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) {
                                        items(activeHomeMatches, key = { it.id }) { match ->
                                            FootballMatchCard(
                                                match = match,
                                                nowMillis = currentHeartbeatMillis,
                                                onSelectStream = { streamItem ->
                                                    if (match.streams.size > 1) {
                                                        selectedMatchForStreams = match
                                                    } else {
                                                        onPlayLiveStream(
                                                            "${match.homeTeam} vs ${match.awayTeam}",
                                                            streamItem.streamUrl,
                                                            match.logo,
                                                            emptyList()
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Swimlane 1 (ĐƯA LÊN ĐẦU TIÊN): Phim Mới Cập Nhật Hàng Ngày (KKPhim + NguonC + CoBePhim)
                    if (latestMovies.isNotEmpty()) {
                        item {
                            MovieSwimlaneWithMore(
                                title = "🔥 PHIM MỚI CẬP NHẬT HÔM NAY",
                                movies = latestMovies,
                                onMovieClick = onMovieSelected,
                                onSeeAllClick = {
                                    switchCategory(NAV_CATEGORIES.first { it.id == "phim-le" })
                                }
                            )
                        }
                    }

                    // Swimlane 2: Phim Chiếu Rạp Bom Tấn
                    if (cinemaMovies.isNotEmpty()) {
                        item {
                            MovieSwimlaneWithMore(
                                title = "🎟️ PHIM CHIẾU RẠP MỚI NHẤT",
                                movies = cinemaMovies,
                                onMovieClick = onMovieSelected,
                                onSeeAllClick = {
                                    switchCategory(NAV_CATEGORIES.first { it.id == "phim-chieu-rap" })
                                }
                            )
                        }
                    }

                    // Swimlane 3: Kho Phim 4K Cinema (Torrent & Remux)
                    if (torrentMovies.isNotEmpty()) {
                        item {
                            MovieSwimlaneWithMore(
                                title = "💎 KHO PHIM 4K CINEMA (TORRENT & REMUX)",
                                movies = torrentMovies,
                                onMovieClick = onMovieSelected,
                                onSeeAllClick = {
                                    switchCategory(NAV_CATEGORIES.first { it.id == "torrent-4k" })
                                }
                            )
                        }
                    }

                    // Swimlane 4: Phim Lẻ Đặc Sắc
                    if (singleMovies.isNotEmpty()) {
                        item {
                            MovieSwimlaneWithMore(
                                title = "🎬 PHIM LẺ ĐẶC SẮC",
                                movies = singleMovies,
                                onMovieClick = onMovieSelected,
                                onSeeAllClick = {
                                    switchCategory(NAV_CATEGORIES.first { it.id == "phim-le" })
                                }
                            )
                        }
                    }

                    // Swimlane 5: Phim Bộ Chọn Lọc
                    if (seriesMovies.isNotEmpty()) {
                        item {
                            MovieSwimlaneWithMore(
                                title = "📺 PHIM BỘ CHỌN LỌC",
                                movies = seriesMovies,
                                onMovieClick = onMovieSelected,
                                onSeeAllClick = {
                                    switchCategory(NAV_CATEGORIES.first { it.id == "phim-bo" })
                                }
                            )
                        }
                    }

                    // Swimlane 6: Hoạt Hình & Anime
                    if (animeMovies.isNotEmpty()) {
                        item {
                            MovieSwimlaneWithMore(
                                title = "🌸 HOẠT HÌNH & ANIME ĐỈNH CAO",
                                movies = animeMovies,
                                onMovieClick = onMovieSelected,
                                onSeeAllClick = {
                                    switchCategory(NAV_CATEGORIES.first { it.id == "hoat-hinh" })
                                }
                            )
                        }
                    }

                } else if (selectedCategory.id == "tv-live") {
                    val sports = tvSportsData ?: TvSportsData()

                    // 1. Tiêu đề mục & Bộ lọc nhánh (TẤT CẢ / ĐANG LIVE / SẮP ĐÁ / KÊNH TV)
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "⚽ TRỰC TIẾP BÓNG ĐÁ & KÊNH TRUYỀN HÌNH HD",
                                        color = TextWhite,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Gộp kênh trùng & tự nhảy link dự phòng • Đã lọc trận kết thúc • ${sports.channels.size} kênh truyền hình",
                                        color = TextDim,
                                        fontSize = 12.sp
                                    )
                                }

                                val addSportsInter = remember { MutableInteractionSource() }
                                val addSportsFoc by addSportsInter.collectIsFocusedAsState()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (addSportsFoc) AccentRed else Color(0xFF1E293B))
                                        .border(
                                            width = if (addSportsFoc) 2.dp else 1.dp,
                                            color = if (addSportsFoc) Color.White else Color(0xFF00E676),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable(interactionSource = addSportsInter, indication = null) {
                                            showSportsSourceDialog = true
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = if (addSportsFoc) Color.White else Color(0xFF00E676),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "THÊM NGUỒN BÓNG ĐÁ / TV",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            val favChannels = sports.channels.filter {
                                AppCacheManager.isFavoriteChannel(it.name)
                            }
                            val thvlCount = sports.channels.count {
                                it.groupTitle == "THVL Vĩnh Long" || it.name.contains("THVL", ignoreCase = true) || it.name.contains("Vĩnh Long", ignoreCase = true)
                            }
                            val vtvCount = sports.channels.count {
                                it.groupTitle == "VTV Quốc Gia" || (it.name.startsWith("VTV", ignoreCase = true) && !it.name.startsWith("VTVcab", ignoreCase = true)) || it.name.contains("ANTV") || it.name.contains("QPVN")
                            }
                            val htvCount = sports.channels.count {
                                it.groupTitle == "HTV TP.HCM" || it.name.startsWith("HTV", ignoreCase = true)
                            }
                            val vtvcabCount = sports.channels.count {
                                it.groupTitle == "Truyền Hình VTVcab" || it.name.startsWith("VTVcab", ignoreCase = true) || it.name.startsWith("ON ", ignoreCase = true)
                            }
                            val quocteCount = sports.channels.count {
                                it.groupTitle == "Kênh Quốc Tế VIP"
                            }
                            val tinhCount = sports.channels.count {
                                it.groupTitle == "Đài Tỉnh Thành"
                            }
                            val filters = listOf(
                                "all" to "TẤT CẢ",
                                "fav" to "⭐ YÊU THÍCH (${favChannels.size})",
                                "vtv" to "🔴 VTV QUỐC GIA ($vtvCount)",
                                "thvl" to "⭐ THVL VĨNH LONG ($thvlCount)",
                                "htv" to "📺 HTV TP.HCM ($htvCount)",
                                "vtvcab" to "🎬 VTVCAB & ON ($vtvcabCount)",
                                "quocte" to "🌍 QUỐC TẾ VIP ($quocteCount)",
                                "tinh" to "🏛️ ĐÀI TỈNH THÀNH ($tinhCount)",
                                "live" to "🔴 ĐANG LIVE",
                                "upcoming" to "⏳ SẮP ĐÁ"
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(filters, key = { it.first }) { (key, label) ->
                                    val isFiltSel = (tvSubFilter == key)
                                    val fInter = remember { MutableInteractionSource() }
                                    val fFoc by fInter.collectIsFocusedAsState()

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(
                                                if (fFoc) AccentRed
                                                else if (isFiltSel) Color(0xFF2B354F)
                                                else CardBg
                                            )
                                            .border(
                                                width = if (fFoc) 1.5.dp else if (isFiltSel) 1.dp else 0.dp,
                                                color = if (fFoc) Color.White else if (isFiltSel) AccentRed else Color.Transparent,
                                                shape = RoundedCornerShape(16.dp)
                                            )
                                            .clickable(interactionSource = fInter, indication = null) {
                                                tvSubFilter = key
                                            }
                                            .padding(horizontal = 14.dp, vertical = 7.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            color = if (fFoc || isFiltSel) Color.White else TextDim,
                                            fontSize = 11.sp,
                                            fontWeight = if (isFiltSel || fFoc) FontWeight.Bold else FontWeight.Medium,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Danh sách trận đấu: Tự động loại bỏ các trận ĐÃ KẾT THÚC (FINISHED)
                    val allMatchesSorted = sports.matches
                        .filter { MatchStateHelper.calculateMatchState(it.kickoffTime, currentHeartbeatMillis).status != MatchStatus.FINISHED }
                        .sortedBy { m ->
                            val st = MatchStateHelper.calculateMatchState(m.kickoffTime, currentHeartbeatMillis)
                            when (st.status) {
                                MatchStatus.LIVE -> 0
                                MatchStatus.UPCOMING -> 1
                                MatchStatus.FINISHED -> 2
                            }
                        }
                    val filteredMatches = when (tvSubFilter) {
                        "live" -> allMatchesSorted.filter { MatchStateHelper.calculateMatchState(it.kickoffTime, currentHeartbeatMillis).status == MatchStatus.LIVE }
                        "upcoming" -> allMatchesSorted.filter { MatchStateHelper.calculateMatchState(it.kickoffTime, currentHeartbeatMillis).status == MatchStatus.UPCOMING }
                        "tv", "fav", "thvl", "vtv", "htv", "vtvcab", "quocte", "tinh" -> emptyList()
                        else -> allMatchesSorted
                    }

                    val showChannels = (tvSubFilter == "all" || tvSubFilter == "fav" || tvSubFilter == "tv" || tvSubFilter == "thvl" || tvSubFilter == "vtv" || tvSubFilter == "htv" || tvSubFilter == "vtvcab" || tvSubFilter == "quocte" || tvSubFilter == "tinh")
                    val favChannelList = sports.channels.filter { AppCacheManager.isFavoriteChannel(it.name) }
                    val vtvChannels = sports.channels.filter {
                        it.groupTitle == "VTV Quốc Gia" || (it.name.startsWith("VTV", ignoreCase = true) && !it.name.startsWith("VTVcab", ignoreCase = true)) || it.name.contains("ANTV") || it.name.contains("QPVN")
                    }
                    val thvlChannels = sports.channels.filter {
                        it.groupTitle == "THVL Vĩnh Long" || it.name.contains("THVL", ignoreCase = true) || it.name.contains("Vĩnh Long", ignoreCase = true)
                    }
                    val htvChannels = sports.channels.filter {
                        it.groupTitle == "HTV TP.HCM" || it.name.startsWith("HTV", ignoreCase = true)
                    }
                    val vtvcabChannels = sports.channels.filter {
                        it.groupTitle == "Truyền Hình VTVcab" || it.name.startsWith("VTVcab", ignoreCase = true) || it.name.startsWith("ON ", ignoreCase = true)
                    }
                    val quocteChannels = sports.channels.filter {
                        it.groupTitle == "Kênh Quốc Tế VIP"
                    }
                    val usedIds = (vtvChannels + thvlChannels + htvChannels + vtvcabChannels + quocteChannels).map { it.id }.toSet()
                    val tinhChannels = sports.channels.filter { it.id !in usedIds }

                    if (filteredMatches.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = "🔥 DANH SÁCH TRẬN CẦU ĐANG & SẮP DIỄN RA (${filteredMatches.size} TRẬN)",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                                )
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    items(filteredMatches, key = { it.id }) { match ->
                                        FootballMatchCard(
                                            match = match,
                                            nowMillis = currentHeartbeatMillis,
                                            onSelectStream = { streamItem ->
                                                if (match.streams.size > 1) {
                                                    selectedMatchForStreams = match
                                                } else {
                                                    onPlayLiveStream("${match.homeTeam} vs ${match.awayTeam}", streamItem.streamUrl, match.logo, emptyList())
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (showChannels && sports.channels.isNotEmpty()) {
                        val channelGroupsToDisplay = when (tvSubFilter) {
                            "fav" -> listOf("⭐ KÊNH TRUYỀN HÌNH YÊU THÍCH ĐÃ GHIM" to favChannelList)
                            "vtv" -> listOf("🔴 ĐÀI TRUYỀN HÌNH QUỐC GIA (VTV)" to vtvChannels)
                            "thvl" -> listOf("⭐ ĐÀI TRUYỀN HÌNH VĨNH LONG (THVL1 - THVL5)" to thvlChannels)
                            "htv" -> listOf("📺 ĐÀI TRUYỀN HÌNH TP. HỒ CHÍ MINH (HTV & HTVC)" to htvChannels)
                            "vtvcab" -> listOf("🎬 TRUYỀN HÌNH VTVCAB & ON SPORTS (${vtvcabChannels.size} KÊNH)" to vtvcabChannels)
                            "quocte" -> listOf("🌍 KÊNH QUỐC TẾ VIP (${quocteChannels.size} KÊNH)" to quocteChannels)
                            "tinh" -> listOf("🏛️ ĐÀI TRUYỀN HÌNH TỈNH THÀNH & TỔNG HỢP" to tinhChannels)
                            else -> listOf(
                                "⭐ KÊNH YÊU THÍCH ĐÃ GHIM (${favChannelList.size} KÊNH)" to favChannelList,
                                "🔴 ĐÀI TRUYỀN HÌNH QUỐC GIA (VTV1 - VTV9, ANTV, QPVN)" to vtvChannels,
                                "⭐ ĐÀI TRUYỀN HÌNH VĨNH LONG (THVL1 - THVL5)" to thvlChannels,
                                "📺 ĐÀI TRUYỀN HÌNH TP. HỒ CHÍ MINH (HTV & HTVC)" to htvChannels,
                                "🎬 TRUYỀN HÌNH VTVCAB & ON SPORTS (${vtvcabChannels.size} KÊNH)" to vtvcabChannels,
                                "🌍 KÊNH QUỐC TẾ VIP (${quocteChannels.size} KÊNH)" to quocteChannels,
                                "🏛️ ĐÀI TRUYỀN HÌNH TỈNH THÀNH & TỔNG HỢP (${tinhChannels.size} KÊNH)" to tinhChannels
                            )
                        }

                        for ((groupHeader, groupList) in channelGroupsToDisplay) {
                            if (groupList.isNotEmpty()) {
                                item(key = "row_$groupHeader") {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(
                                            text = groupHeader,
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp)
                                        )
                                        // Thanh Dock Kênh Hàng Ngang (Phong cách Glass Dock như ảnh mẫu)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(22.dp))
                                                .background(Color(0x99121722))
                                                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(22.dp))
                                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                        ) {
                                            LazyRow(
                                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                                contentPadding = PaddingValues(horizontal = 4.dp)
                                            ) {
                                                items(groupList, key = { it.id }) { channel ->
                                                    TvChannelCard(
                                                        channel = channel,
                                                        onClick = {
                                                            onPlayLiveStream(channel.name, channel.streamUrl, channel.logoUrl, channel.backupUrls)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                } else {
                    // CATEGORY GRID VIEW WITH INFINITE SCROLL / PAGINATION
                    if (categoryMovies.isEmpty() && isCategoryLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(260.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(color = AccentRed)
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("Đang nạp danh mục ${selectedCategory.title}...", color = TextDim, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "DANH MỤC: ${selectedCategory.title}",
                                color = TextWhite,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "Đã nạp ${categoryMovies.size} phim (Trang $categoryPage)",
                                color = TextDim,
                                fontSize = 13.sp
                            )
                        }
                    }

                    // Chunk into rows of 6 cards for TV grid display
                    val rows = categoryMovies.chunked(6)
                    itemsIndexed(rows) { index, rowMovies ->
                        // Auto-load next page when user scrolls near the end of loaded rows
                        if (index >= rows.size - 2 && !isCategoryLoadingMore) {
                            LaunchedEffect(index) {
                                loadMoreCategoryMovies()
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            for (movie in rowMovies) {
                                MovieCard(
                                    movie = movie,
                                    onClick = { onMovieSelected(movie.slug) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            // Fill remaining slots in last row if not multiple of 6
                            val remaining = 6 - rowMovies.size
                            if (remaining > 0) {
                                for (i in 0 until remaining) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }

                    // Bottom "Tải thêm phim" Card
                    item {
                        val loadMoreInter = remember { MutableInteractionSource() }
                        val isLoadMoreFoc by loadMoreInter.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isLoadMoreFoc) AccentRed else CardBg)
                                .border(
                                    width = if (isLoadMoreFoc) 2.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(interactionSource = loadMoreInter, indication = null) {
                                    loadMoreCategoryMovies()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (isCategoryLoadingMore) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text("ĐANG TẢI THÊM PHIM MỚI...", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "TẢI THÊM 24 PHIM TIẾP THEO (TRANG ${categoryPage + 1})",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(20.dp))
                }
            }

        if (showSourceSettingsDialog) {
            SourceSettingsDialog(
                onDismiss = { showSourceSettingsDialog = false },
                onSaveSuccess = { reloadAllMovies() }
            )
        }

        if (showSportsSourceDialog) {
            SportsSourceSettingsDialog(
                onDismiss = { showSportsSourceDialog = false },
                onSaveSuccess = { reloadSportsOnly() }
            )
        }

        if (showAppUpdateDialog) {
            AppUpdateDialog(
                currentVersionCode = currentVersionCode,
                currentVersionName = currentVersionName,
                initialUpdateInfo = remoteUpdateInfo,
                onUpdateInfoFetched = { remoteUpdateInfo = it },
                onDismiss = { showAppUpdateDialog = false }
            )
        }

        if (selectedMatchForStreams != null) {
            val match = selectedMatchForStreams!!
            Dialog(
                onDismissRequest = { selectedMatchForStreams = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xCC000000)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.5.dp, AccentRed),
                        modifier = Modifier
                            .widthIn(max = 520.dp)
                            .fillMaxWidth(0.94f)
                            .wrapContentHeight()
                            .padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp)
                        ) {
                            Text(
                                text = "CHỌN NGUỒN PHÁT / BÌNH LUẬN VIÊN",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${match.homeTeam} vs ${match.awayTeam} • ${match.kickoffTime}",
                                color = AccentRed,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = match.tournament.ifBlank { "Trực tiếp bóng đá" },
                                color = TextDim,
                                fontSize = 11.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                // Nút Phát Ngay Tự Động Chống Lag (Gộp tất cả link dự phòng)
                                val autoInter = remember { MutableInteractionSource() }
                                val autoFoc by autoInter.collectIsFocusedAsState()
                                val autoPress by autoInter.collectIsPressedAsState()
                                val autoActive = autoFoc || autoPress
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (autoActive) Color(0xFF00C853) else Color(0xFF064E3B))
                                        .border(
                                            width = if (autoActive) 2.dp else 1.dp,
                                            color = if (autoActive) Color.White else Color(0xFF00E676),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable(interactionSource = autoInter, indication = null) {
                                            selectedMatchForStreams = null
                                            val allUrls = match.streams.map { it.streamUrl }.distinct()
                                            val primary = allUrls.firstOrNull() ?: return@clickable
                                            onPlayLiveStream(
                                                "${match.homeTeam} vs ${match.awayTeam}",
                                                primary,
                                                match.logo,
                                                allUrls.drop(1)
                                            )
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "⚡ PHÁT NGAY TỰ ĐỘNG CHỐNG LAG (${match.streams.size} SERVER DỰ PHÒNG)",
                                            color = if (autoActive) Color.Black else Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Text(
                                            text = "Tự động đổi sang link dự phòng ngay khi link chết hoặc bị đứng hình",
                                            color = if (autoActive) Color.Black.copy(alpha = 0.8f) else Color(0xFFA7F3D0),
                                            fontSize = 11.sp
                                        )
                                    }
                                    Text(
                                        text = "XEM NGAY ▶",
                                        color = if (autoActive) Color.Black else Color(0xFF00E676),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }

                                match.streams.forEachIndexed { idx, stream ->
                                    val sInter = remember { MutableInteractionSource() }
                                    val sFoc by sInter.collectIsFocusedAsState()
                                    val sPress by sInter.collectIsPressedAsState()
                                    val sActive = sFoc || sPress

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (sActive) AccentRed else Color(0xFF1E2533))
                                            .border(
                                                width = if (sActive) 1.5.dp else 1.dp,
                                                color = if (sActive) Color.White else Color(0x33FFFFFF),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .clickable(interactionSource = sInter, indication = null) {
                                                selectedMatchForStreams = null
                                                val otherStreams = match.streams.map { it.streamUrl }.filter { it != stream.streamUrl }.distinct()
                                                onPlayLiveStream(
                                                    "${match.homeTeam} vs ${match.awayTeam} (${stream.commentator})",
                                                    stream.streamUrl,
                                                    match.logo,
                                                    otherStreams
                                                )
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "🎙️",
                                                fontSize = 16.sp
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    text = stream.commentator.ifBlank { "Bình Luận Tiếng Việt ${idx + 1}" },
                                                    color = Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Text(
                                                    text = "Server ${idx + 1} • FHD HLS",
                                                    color = if (sActive) Color.White.copy(alpha = 0.8f) else TextDim,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                        Text(
                                            text = "PHÁT NGAY ▶",
                                            color = if (sActive) Color.White else AccentRed,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            val closeInter = remember { MutableInteractionSource() }
                            val closeFoc by closeInter.collectIsFocusedAsState()
                            val closePress by closeInter.collectIsPressedAsState()
                            val closeActive = closeFoc || closePress
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (closeActive) Color.White else Color(0xFF2A3142))
                                    .clickable(interactionSource = closeInter, indication = null) {
                                        selectedMatchForStreams = null
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "ĐÓNG",
                                    color = if (closeFoc) Color.Black else Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppUpdateDialog(
    currentVersionCode: Int,
    currentVersionName: String,
    initialUpdateInfo: AppVersionInfo?,
    onUpdateInfoFetched: (AppVersionInfo) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isChecking by remember { mutableStateOf(initialUpdateInfo == null) }
    var updateInfo by remember { mutableStateOf(initialUpdateInfo) }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        isChecking = true
        val fetched = AppUpdateManager.checkForUpdate()
        if (fetched != null) {
            updateInfo = fetched
            onUpdateInfoFetched(fetched)
        }
        isChecking = false
    }

    val targetInfo = updateInfo ?: AppVersionInfo(
        versionCode = currentVersionCode,
        versionName = currentVersionName,
        releaseNotes = "Tự động cập nhật trực tiếp ngay trong App, Góc Bé An, Tự nhảy tập mới, Khóa Trẻ Em, Khuếch đại âm thanh 200%, Đảo nhanh 2 kênh TV..."
    )
    val hasNewerVersion = targetInfo.versionCode > currentVersionCode

    // Tự động tải về và kích hoạt cập nhật ngay khi phát hiện bản mới trên GitHub / LAN
    LaunchedEffect(hasNewerVersion, targetInfo.versionCode) {
        if (hasNewerVersion && !isDownloading) {
            isDownloading = true
            downloadProgress = 1
            statusMessage = "🔔 Phát hiện bản mới v${targetInfo.versionName}! Đang tự động tải về để cập nhật..."
            AppUpdateManager.downloadAndInstallApk(
                context = context,
                versionInfo = targetInfo,
                onProgress = { pct -> downloadProgress = pct },
                onError = { err ->
                    isDownloading = false
                    statusMessage = err
                }
            )
            isDownloading = false
        }
    }

    Dialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xD9000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    if (!isDownloading) onDismiss()
                },
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141721)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(2.dp, if (hasNewerVersion) Color(0xFF00E676) else AccentRed),
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.88f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* Chặn click xuyên nền */ }
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // --- 1. TIÊU ĐỀ & PHIÊN BẢN (CỐ ĐỊNH Ở ĐẦU) ---
                    Text(
                        text = if (hasNewerVersion) "🚀 ĐÃ CÓ BẢN CẬP NHẬT MỚI: v${targetInfo.versionName}" else "🔄 CẬP NHẬT TRỰC TIẾP TRÊN APP (OTA)",
                        color = if (hasNewerVersion) Color(0xFF00E676) else Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Phiên bản đang cài trên máy: v$currentVersionName (Build #$currentVersionCode)\nPhiên bản mới nhất trên máy chủ: v${targetInfo.versionName} (Build #${targetInfo.versionCode})",
                        color = Color(0xFFA0AAB8),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )

                    if (isChecking) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(color = Color(0xFF00E676), modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Đang kiểm tra máy chủ LAN & GitHub Cloud...", color = Color(0xFF00E676), fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // --- 2. THÂN THÔNG BÁO CUỘN CẢM ỨNG MƯỢT MÀ BẰNG TAY (WEIGHT 1F) ---
                    Box(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0E121A))
                            .border(1.dp, Color(0xFF283042), RoundedCornerShape(10.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    ) {
                        Column {
                            if (targetInfo.releaseNotes.isNotBlank()) {
                                Text(
                                    text = "✨ TÍNH NĂNG MỚI TRONG BẢN CẬP NHẬT:",
                                    color = Color(0xFFFFD600),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = targetInfo.releaseNotes,
                                    color = Color(0xFFD0D7DE),
                                    fontSize = 13.sp,
                                    lineHeight = 19.sp
                                )
                            }

                            if (isDownloading) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "⬇️ Đang tải gói cập nhật trực tiếp... $downloadProgress%",
                                    color = Color(0xFF00E676),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(Color(0xFF252B3B))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth((downloadProgress / 100f).coerceIn(0.03f, 1f))
                                            .fillMaxHeight()
                                            .background(Color(0xFF00E676))
                                    )
                                }
                            }

                            if (!statusMessage.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(
                                    text = statusMessage!!,
                                    color = Color(0xFFFFD600),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // --- 3. BĂNG NÚT BẤM CỐ ĐỊNH Ở ĐÁY: 100% LUÔN HIỂN THỊ & 1-CHẠM TỨC THÌ ---
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val installInter = remember { MutableInteractionSource() }
                        val isInstallFoc by installInter.collectIsFocusedAsState()
                        val isInstallPress by installInter.collectIsPressedAsState()
                        val isInstallActive = isInstallFoc || isInstallPress

                        Box(
                            modifier = Modifier
                                .weight(1.6f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isDownloading) Color(0xFF2D3748)
                                    else if (isInstallActive) AccentRed
                                    else Color(0xFF00C853)
                                )
                                .border(
                                    width = if (isInstallActive) 2.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable(
                                    interactionSource = installInter,
                                    indication = null,
                                    enabled = !isDownloading
                                ) {
                                    isDownloading = true
                                    downloadProgress = 1
                                    statusMessage = "Đang tải từ máy chủ tốc độ cao..."
                                    scope.launch {
                                        AppUpdateManager.downloadAndInstallApk(
                                            context = context,
                                            versionInfo = targetInfo,
                                            onProgress = { pct -> downloadProgress = pct },
                                            onError = { err ->
                                                isDownloading = false
                                                statusMessage = err
                                            }
                                        )
                                        isDownloading = false
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isDownloading) "⏳ ĐANG TẢI ($downloadProgress%)..."
                                else if (hasNewerVersion) "⬇️ CẬP NHẬT NGAY LÊN v${targetInfo.versionName}"
                                else "⬇️ TẢI & CÀI ĐÈ BẢN MỚI NHẤT NGAY",
                                color = if (!isDownloading && !isInstallActive) Color.Black else Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        val closeInter = remember { MutableInteractionSource() }
                        val isCloseFoc by closeInter.collectIsFocusedAsState()
                        val isClosePress by closeInter.collectIsPressedAsState()
                        val isCloseActive = isCloseFoc || isClosePress

                        Box(
                            modifier = Modifier
                                .weight(0.9f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isCloseActive) AccentRed else Color(0xFF252A38))
                                .border(
                                    width = if (isCloseActive) 2.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable(
                                    interactionSource = closeInter,
                                    indication = null,
                                    enabled = !isDownloading
                                ) {
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "ĐÓNG",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MovieSwimlaneWithMore(
    title: String,
    movies: List<MovieItemDto>,
    onMovieClick: (String) -> Unit,
    onSeeAllClick: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = TextWhite,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            val seeAllInter = remember { MutableInteractionSource() }
            val isSeeAllFoc by seeAllInter.collectIsFocusedAsState()

            Text(
                text = "XEM TẤT CẢ ➔",
                color = if (isSeeAllFoc) AccentRed else TextDim,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(interactionSource = seeAllInter, indication = null, onClick = onSeeAllClick)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(end = 32.dp)
        ) {
            items(movies, key = { it.slug + it.name }) { movie ->
                MovieCard(
                    movie = movie,
                    onClick = { onMovieClick(movie.slug) }
                )
            }

            // "Xem tất cả & Tải thêm" Card at the end of the swimlane
            item {
                val moreInter = remember { MutableInteractionSource() }
                val isMoreFoc by moreInter.collectIsFocusedAsState()

                Box(
                    modifier = Modifier
                        .width(150.dp)
                        .height(235.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isMoreFoc) AccentRed else CardBg)
                        .border(
                            width = if (isMoreFoc) 2.5.dp else 1.dp,
                            color = if (isMoreFoc) Color.White else Color(0x33FFFFFF),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable(interactionSource = moreInter, indication = null, onClick = onSeeAllClick),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(if (isMoreFoc) Color.White else Color(0xFF1F2430)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "➔",
                                color = if (isMoreFoc) AccentRed else Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "XEM TẤT CẢ\n& TẢI THÊM",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SourceSettingsDialog(
    onDismiss: () -> Unit,
    onSaveSuccess: () -> Unit
) {
    val context = LocalContext.current
    var inputUrl by remember { mutableStateOf("") }
    val currentSources = remember { RemoteConfigManager.getSavedCustomSources(context) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xD9000000)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141721)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, Color(0xFF333A4D)),
                modifier = Modifier
                    .widthIn(max = 720.dp)
                    .fillMaxWidth(0.94f)
                    .wrapContentHeight()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                tint = AccentRed,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "QUẢN LÝ NGUỒN PHIM (DUYTV)",
                                color = TextWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val closeInter = remember { MutableInteractionSource() }
                        val closeFoc by closeInter.collectIsFocusedAsState()
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (closeFoc) AccentRed else Color(0xFF252A38))
                                .border(
                                    width = if (closeFoc) 1.5.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable(interactionSource = closeInter, indication = null) { onDismiss() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("✕ ĐÓNG", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Nhập đường link nguồn mới hoặc chọn nguồn nhanh bên dưới. App sẽ tự động lưu và tải phim từ nguồn mới ngay lập tức mà không cần cài lại app!",
                        color = TextDim,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Input box
                    Text(
                        text = "ĐƯỜNG LINK NGUỒN PHIM MỚI (URL):",
                        color = Color(0xFFFFD700),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        placeholder = { Text("Ví dụ: https://phimapi.com hoặc https://ophim.cc", color = Color(0xFF6E7687), fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF0F1118),
                            unfocusedContainerColor = Color(0xFF0F1118),
                            focusedIndicatorColor = AccentRed,
                            unfocusedIndicatorColor = Color(0xFF333A4D)
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick presets
                    Text(
                        text = "CHỌN NHANH NGUỒN SẴN CÓ:",
                        color = TextWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val presets = listOf(
                        "KKPhim (Chuan)" to "https://phimapi.com",
                        "OPhim" to "https://ophim.cc",
                        "NguonC" to "https://phim.nguonc.com/api/film",
                        "CoBePhim" to "https://cobephim.mom"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { (name, url) ->
                            val pInter = remember { MutableInteractionSource() }
                            val pFoc by pInter.collectIsFocusedAsState()
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (pFoc) AccentRed else Color(0xFF1E2330))
                                    .border(
                                        width = if (pFoc) 1.5.dp else 1.dp,
                                        color = if (pFoc) Color.White else Color(0xFF333A4D),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable(interactionSource = pInter, indication = null) {
                                        inputUrl = url
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = name,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // SAVE & UPDATE BUTTON
                        val saveInter = remember { MutableInteractionSource() }
                        val isSaveFoc by saveInter.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .weight(1.5f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSaveFoc) Color(0xFFE50914) else Color(0xFFB81D24))
                                .border(
                                    width = if (isSaveFoc) 2.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(interactionSource = saveInter, indication = null) {
                                    if (inputUrl.isNotBlank()) {
                                        val saved = RemoteConfigManager.saveCustomSource(context, inputUrl)
                                        Toast.makeText(context, "✅ Đã lưu: $saved. Đang làm mới dữ liệu...", Toast.LENGTH_SHORT).show()
                                        onSaveSuccess()
                                        onDismiss()
                                    } else {
                                        Toast.makeText(context, "Vui lòng nhập hoặc chọn một đường link nguồn!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "💾 LƯU & CẬP NHẬT NGAY",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // RESET BUTTON
                        val resetInter = remember { MutableInteractionSource() }
                        val isResetFoc by resetInter.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isResetFoc) Color(0xFF4A5568) else Color(0xFF2D3748))
                                .border(
                                    width = if (isResetFoc) 2.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(interactionSource = resetInter, indication = null) {
                                    RemoteConfigManager.resetToDefaults(context)
                                    Toast.makeText(context, "🔄 Đã đặt lại nguồn phim mặc định!", Toast.LENGTH_SHORT).show()
                                    onSaveSuccess()
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "🔄 ĐẶT LẠI GỐC",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Current Active Sources List
                    Text(
                        text = "NGUỒN HIỆN TẠI ĐANG SỬ DỤNG TRÊN APP:",
                        color = Color(0xFFA0AEC0),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F1118))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        currentSources.forEach { (name, url) ->
                            SourceItemRow(name = name, url = url)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceItemRow(name: String, url: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF00FF66))
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = name,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Text(
            text = url,
            color = Color(0xFF8892B0),
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SportsSourceSettingsDialog(
    onDismiss: () -> Unit,
    onSaveSuccess: () -> Unit
) {
    val context = LocalContext.current
    var inputUrl by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    val currentSportsSources = remember(refreshKey) {
        RemoteConfigManager.getSavedSportsSourcesDisplay(context)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xD9000000)),
            contentAlignment = Alignment.Center
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF141721)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, Color(0xFF00E676)),
                modifier = Modifier
                    .widthIn(max = 740.dp)
                    .fillMaxWidth(0.94f)
                    .wrapContentHeight()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "QUẢN LÝ & THÊM NGUỒN BÓNG ĐÁ / TV (DUYTV)",
                                color = TextWhite,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        val closeInter = remember { MutableInteractionSource() }
                        val closeFoc by closeInter.collectIsFocusedAsState()
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (closeFoc) AccentRed else Color(0xFF252A38))
                                .border(
                                    width = if (closeFoc) 1.5.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable(interactionSource = closeInter, indication = null) { onDismiss() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("✕ ĐÓNG", color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Nhập link danh sách M3U / IPTV bóng đá, link trực tiếp (.m3u8) hoặc cú pháp 'Tên trận | Link .m3u8'. App sẽ tự động gộp trận/kênh trùng lặp và đưa link phụ vào nguồn dự phòng!",
                        color = TextDim,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Input box
                    Text(
                        text = "ĐƯỜNG LINK NGUỒN BÓNG ĐÁ / M3U / LUỒNG .M3U8 MỚI:",
                        color = Color(0xFF00E676),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = { inputUrl = it },
                        placeholder = {
                            Text(
                                "VD: https://paste.c-net.org/... hoặc Việt Nam vs Thái Lan | https://.../live.m3u8",
                                color = Color(0xFF6E7687),
                                fontSize = 13.sp
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedContainerColor = Color(0xFF0F1118),
                            unfocusedContainerColor = Color(0xFF0F1118),
                            focusedIndicatorColor = Color(0xFF00E676),
                            unfocusedIndicatorColor = Color(0xFF333A4D)
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Quick presets
                    Text(
                        text = "CHỌN NHANH NGUỒN BÓNG ĐÁ & IPTV SẴN CÓ:",
                        color = TextWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val sportsPresets = listOf(
                        "Kho Gà Bóng Đá" to "https://paste.c-net.org/PostersMistrust",
                        "All-In-One IPTV" to "https://raw.githubusercontent.com/khanh71/All-In-One-IPTV/main/http-iptv.m3u",
                        "IPTV Sport VN" to "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/vn.m3u"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        sportsPresets.forEach { (name, url) ->
                            val pInter = remember { MutableInteractionSource() }
                            val pFoc by pInter.collectIsFocusedAsState()
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (pFoc) AccentRed else Color(0xFF1E2330))
                                    .border(
                                        width = if (pFoc) 1.5.dp else 1.dp,
                                        color = if (pFoc) Color.White else Color(0xFF333A4D),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable(interactionSource = pInter, indication = null) {
                                        inputUrl = url
                                    }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = name,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // SAVE & UPDATE BUTTON
                        val saveInter = remember { MutableInteractionSource() }
                        val isSaveFoc by saveInter.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .weight(1.5f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSaveFoc) Color(0xFF00C853) else Color(0xFF009624))
                                .border(
                                    width = if (isSaveFoc) 2.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(interactionSource = saveInter, indication = null) {
                                    if (inputUrl.isNotBlank()) {
                                        val saved = RemoteConfigManager.saveCustomSportsSource(context, inputUrl)
                                        refreshKey++
                                        Toast.makeText(context, "✅ Đã thêm nguồn bóng đá: $saved", Toast.LENGTH_SHORT).show()
                                        onSaveSuccess()
                                        onDismiss()
                                    } else {
                                        Toast.makeText(context, "Vui lòng nhập hoặc chọn đường link nguồn bóng đá!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "💾 THÊM & CẬP NHẬT BÓNG ĐÁ NGAY",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // RESET BUTTON
                        val resetInter = remember { MutableInteractionSource() }
                        val isResetFoc by resetInter.collectIsFocusedAsState()

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isResetFoc) Color(0xFF4A5568) else Color(0xFF2D3748))
                                .border(
                                    width = if (isResetFoc) 2.dp else 0.dp,
                                    color = Color.White,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable(interactionSource = resetInter, indication = null) {
                                    RemoteConfigManager.resetSportsSourcesToDefaults(context)
                                    refreshKey++
                                    Toast.makeText(context, "🔄 Đã đặt lại nguồn bóng đá & TV mặc định!", Toast.LENGTH_SHORT).show()
                                    onSaveSuccess()
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "🔄 ĐẶT LẠI GỐC",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // Current Active Sports Sources List
                    Text(
                        text = "DANH SÁCH NGUỒN BÓNG ĐÁ & TV ĐANG HOẠT ĐỘNG:",
                        color = Color(0xFFA0AEC0),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF0F1118))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        currentSportsSources.forEachIndexed { index, (name, url) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f)) {
                                    SourceItemRow(name = name, url = url)
                                }
                                if (index >= 2) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val delInter = remember { MutableInteractionSource() }
                                    val delFoc by delInter.collectIsFocusedAsState()
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(if (delFoc) AccentRed else Color(0xFF2D3748))
                                            .clickable(interactionSource = delInter, indication = null) {
                                                RemoteConfigManager.removeCustomSportsSource(context, url)
                                                refreshKey++
                                                onSaveSuccess()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) {
                                        Text("✕ XÓA", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
