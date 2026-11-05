package com.duytv.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalContext
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
    onPlayLiveStream: (title: String, streamUrl: String, posterUrl: String?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Khởi tạo ngay tức thì từ bộ nhớ đệm L1 RAM / L2 Disk để mở app không độ trễ
    val cachedHome = AppCacheManager.cachedHomeData
    val initialTvSports = AppCacheManager.cachedTvSports ?: TvSportsData(channels = AppCacheManager.activeChannels)
    val hasCache = cachedHome != null && cachedHome.latestMovies.isNotEmpty()

    var isLoading by remember { mutableStateOf(!hasCache) }
    var isSilentSyncing by remember { mutableStateOf(false) }
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

    // 1 duy nhất Heartbeat Clock coroutine cho toàn bộ đếm ngược trận đấu & đồng hồ TV
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            currentHeartbeatMillis = System.currentTimeMillis()
        }
    }

    // Specific Category list & pagination state
    var categoryMovies by remember { mutableStateOf<List<MovieItemDto>>(emptyList()) }
    var categoryPage by remember { mutableIntStateOf(1) }
    var isCategoryLoadingMore by remember { mutableStateOf(false) }
    var showSourceSettingsDialog by remember { mutableStateOf(false) }

    val currentTime = remember {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.format(Date())
    }

    fun reloadAllMovies(silent: Boolean = false) {
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
                    val tvSportsDef = async { try { repository.getTvAndSportsData() } catch (_: Exception) { TvSportsData() } }

                    val resLatest = latestDef.await()
                    val resCinema = cinemaDef.await()
                    val resTorrent = torrentDef.await()
                    val resSeries = seriesDef.await()
                    val resSingle = singleDef.await()
                    val resAnime = animeDef.await()
                    val resTvSports = tvSportsDef.await()

                    if (resLatest.isNotEmpty()) latestMovies = resLatest
                    if (resCinema.isNotEmpty()) cinemaMovies = resCinema
                    if (resTorrent.isNotEmpty()) torrentMovies = resTorrent
                    if (resSeries.isNotEmpty()) seriesMovies = resSeries
                    if (resSingle.isNotEmpty()) singleMovies = resSingle
                    if (resAnime.isNotEmpty()) animeMovies = resAnime
                    if (resTvSports.channels.isNotEmpty() || resTvSports.matches.isNotEmpty()) {
                        tvSportsData = resTvSports
                        AppCacheManager.saveTvSports(resTvSports, context)
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
                    val res = try { repository.getTvAndSportsData() } catch (_: Exception) { TvSportsData() }
                    if (res.channels.isNotEmpty() || res.matches.isNotEmpty()) {
                        tvSportsData = res
                        AppCacheManager.saveTvSports(res, context)
                    }
                } else {
                    categoryPage = 1
                    categoryMovies = try { repository.getMoviesByCategory(selectedCategory.endpoint, 1) } catch (_: Exception) { emptyList() }
                }
            } finally {
                isLoading = false
                isSilentSyncing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        AppCacheManager.init(context, MovieRepository.getVerifiedOfficialChannels())
        RemoteConfigManager.loadLocalCustomSources(context)
        watchHistory = WatchHistoryManager.getHistory(context)
        scope.launch {
            RemoteConfigManager.fetchRemoteConfig()
            val shouldBeSilent = latestMovies.isNotEmpty() || (tvSportsData?.channels?.isNotEmpty() == true)
            reloadAllMovies(silent = shouldBeSilent)
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentRed)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Đang nạp kho phim đa nguồn 4K Cinema & Phim Rạp...", color = TextDim, fontSize = 14.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 1. Top TV Header (Brand, Status, Search, Clock)
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(
                                modifier = Modifier
                                    .background(CardBg, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = if (isSilentSyncing) "⚡ ĐANG ĐỒNG BỘ..." else "ĐA NGUỒN • 4K CINEMA",
                                    color = if (isSilentSyncing) Color(0xFFFFD600) else Color(0xFF00E676),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(20.dp))

                            // Search Button
                            val searchInter = remember { MutableInteractionSource() }
                            val searchFoc by searchInter.collectIsFocusedAsState()

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (searchFoc) AccentRed else CardBg)
                                    .border(
                                        width = if (searchFoc) 1.5.dp else 0.dp,
                                        color = Color.White,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .focusable(interactionSource = searchInter)
                                    .clickable(interactionSource = searchInter, indication = null, onClick = onOpenSearch)
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Tìm kiếm",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "TÌM KIẾM TẤT CẢ NGUỒN",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            // Source Settings Button
                            val sourceInter = remember { MutableInteractionSource() }
                            val sourceFoc by sourceInter.collectIsFocusedAsState()

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (sourceFoc) AccentRed else CardBg)
                                    .border(
                                        width = if (sourceFoc) 1.5.dp else 0.dp,
                                        color = Color.White,
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .focusable(interactionSource = sourceInter)
                                    .clickable(interactionSource = sourceInter, indication = null) {
                                        showSourceSettingsDialog = true
                                    }
                                    .padding(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = "Cài đặt nguồn",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "NGUỒN PHIM",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Text(
                            text = currentTime,
                            color = TextWhite,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Medium
                        )
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

                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(
                                        if (isCatFoc) AccentRed
                                        else if (isSelected) Color(0xFF252B3B)
                                        else CardBg
                                    )
                                    .border(
                                        width = if (isCatFoc) 2.dp else if (isSelected) 1.dp else 0.dp,
                                        color = if (isCatFoc) Color.White else if (isSelected) AccentRed else Color.Transparent,
                                        shape = RoundedCornerShape(20.dp)
                                    )
                                    .focusable(interactionSource = catInter)
                                    .clickable(interactionSource = catInter, indication = null) {
                                        switchCategory(cat)
                                    }
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = cat.title,
                                    color = if (isCatFoc || isSelected) TextWhite else TextDim,
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
                                    text = "⏱️ TIẾP TỤC XEM",
                                    color = TextWhite,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    items(watchHistory, key = { it.slug + it.episodeName }) { item ->
                                        val itemInter = remember { MutableInteractionSource() }
                                        val isFoc by itemInter.collectIsFocusedAsState()

                                        Box(
                                            modifier = Modifier
                                                .width(210.dp)
                                                .height(130.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(CardBg)
                                                .border(
                                                    width = if (isFoc) 2.5.dp else 0.dp,
                                                    color = if (isFoc) AccentRed else Color.Transparent,
                                                    shape = RoundedCornerShape(8.dp)
                                                )
                                                .focusable(interactionSource = itemInter)
                                                .clickable(interactionSource = itemInter, indication = null) {
                                                    onResumePlayback(item)
                                                }
                                        ) {
                                            AsyncImage(
                                                model = item.posterUrl,
                                                contentDescription = item.movieTitle,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(Color(0x990A0E17))
                                            )
                                            Column(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(10.dp)
                                            ) {
                                                Text(
                                                    text = item.movieTitle,
                                                    color = TextWhite,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "Xem tiếp: Tập ${item.episodeName}",
                                                    color = Color(0xFF00E676),
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Swimlane Live: ⚽ BÓNG ĐÁ TRỰC TIẾP & TRUYỀN HÌNH HD
                    val sportsSnapshot = tvSportsData
                    if (sportsSnapshot != null && (sportsSnapshot.matches.isNotEmpty() || sportsSnapshot.channels.isNotEmpty())) {
                        item {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "⚽ BÓNG ĐÁ TRỰC TIẾP & TRUYỀN HÌNH HD",
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
                                            .focusable(interactionSource = seeAllInter)
                                            .clickable(interactionSource = seeAllInter, indication = null) {
                                                switchCategory(NAV_CATEGORIES.first { it.id == "tv-live" })
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "Xem tất cả (${sportsSnapshot.matches.size} trận • ${sportsSnapshot.channels.size} kênh) >",
                                            color = if (seeAllFoc) Color.White else AccentRed,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                                    contentPadding = PaddingValues(vertical = 4.dp)
                                ) {
                                    items(sportsSnapshot.matches.take(6), key = { it.id }) { match ->
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
                                                        match.logo
                                                    )
                                                }
                                            }
                                        )
                                    }

                                    items(sportsSnapshot.channels.take(8), key = { it.id }) { channel ->
                                        TvChannelCard(
                                            channel = channel,
                                            onClick = {
                                                onPlayLiveStream(channel.name, channel.streamUrl, channel.logoUrl)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Swimlane 1: Phim Chiếu Rạp Bom Tấn
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

                    // Swimlane 2: Kho Phim 4K Cinema (Torrent & Remux)
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

                    // Swimlane 3: Phim Mới Cập Nhật
                    item {
                        MovieSwimlaneWithMore(
                            title = "🔥 PHIM MỚI CẬP NHẬT",
                            movies = latestMovies,
                            onMovieClick = onMovieSelected,
                            onSeeAllClick = {
                                switchCategory(NAV_CATEGORIES.first { it.id == "phim-le" })
                            }
                        )
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
                                    Text(
                                        text = "Đa nguồn BLV Tiếng Việt • Tự động đếm ngược giờ đá • 131 kênh TV",
                                        color = TextDim,
                                        fontSize = 12.sp
                                    )
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val thvlCount = sports.channels.count {
                                        it.groupTitle == "THVL Vĩnh Long" || it.name.contains("THVL", ignoreCase = true) || it.name.contains("Vĩnh Long", ignoreCase = true)
                                    }
                                    val vtvCount = sports.channels.count {
                                        it.groupTitle == "VTV Quốc Gia" || it.name.startsWith("VTV", ignoreCase = true) || it.name.contains("ANTV") || it.name.contains("QPVN")
                                    }
                                    val htvCount = sports.channels.count {
                                        it.groupTitle == "HTV TP.HCM" || it.name.startsWith("HTV", ignoreCase = true)
                                    }
                                    val tinhCount = sports.channels.count {
                                        it.groupTitle == "Đài Tỉnh Thành"
                                    }
                                    val filters = listOf(
                                        "all" to "TẤT CẢ",
                                        "thvl" to "⭐ THVL VĨNH LONG ($thvlCount)",
                                        "vtv" to "🔴 VTV QUỐC GIA ($vtvCount)",
                                        "htv" to "📺 HTV TP.HCM ($htvCount)",
                                        "tinh" to "🏛️ ĐÀI TỈNH THÀNH ($tinhCount)",
                                        "live" to "🔴 ĐANG LIVE",
                                        "upcoming" to "⏳ SẮP ĐÁ"
                                    )
                                    for ((key, label) in filters) {
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
                                                .focusable(interactionSource = fInter)
                                                .clickable(interactionSource = fInter, indication = null) {
                                                    tvSubFilter = key
                                                }
                                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = label,
                                                color = if (fFoc || isFiltSel) Color.White else TextDim,
                                                fontSize = 11.sp,
                                                fontWeight = if (isFiltSel || fFoc) FontWeight.Bold else FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Danh sách trận đấu được sắp xếp và lọc theo trạng thái thời gian thực
                    val allMatchesSorted = sports.matches.sortedBy { m ->
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
                        "tv", "thvl", "vtv", "htv", "tinh" -> emptyList()
                        else -> allMatchesSorted
                    }

                    val showChannels = (tvSubFilter == "all" || tvSubFilter == "tv" || tvSubFilter == "thvl" || tvSubFilter == "vtv" || tvSubFilter == "htv" || tvSubFilter == "tinh")
                    val filteredChannels = when (tvSubFilter) {
                        "thvl" -> sports.channels.filter {
                            it.groupTitle == "THVL Vĩnh Long" || it.name.contains("THVL", ignoreCase = true) || it.name.contains("Vĩnh Long", ignoreCase = true)
                        }
                        "vtv" -> sports.channels.filter {
                            it.groupTitle == "VTV Quốc Gia" || it.name.startsWith("VTV", ignoreCase = true) || it.name.contains("ANTV") || it.name.contains("QPVN")
                        }
                        "htv" -> sports.channels.filter {
                            it.groupTitle == "HTV TP.HCM" || it.name.startsWith("HTV", ignoreCase = true)
                        }
                        "tinh" -> sports.channels.filter {
                            it.groupTitle == "Đài Tỉnh Thành"
                        }
                        "live", "upcoming" -> emptyList()
                        else -> sports.channels
                    }

                    if (filteredMatches.isNotEmpty()) {
                        item {
                            Text(
                                text = "🔥 DANH SÁCH TRẬN CẦU TÂM ĐIỂM (${filteredMatches.size} TRẬN)",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }

                        // Hiển thị 3 card bóng đá mỗi hàng trên màn hình TV & Tablet
                        val matchRows = filteredMatches.chunked(3)
                        itemsIndexed(matchRows) { _, rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                for (match in rowItems) {
                                    FootballMatchCard(
                                        match = match,
                                        nowMillis = currentHeartbeatMillis,
                                        onSelectStream = { streamItem ->
                                            if (match.streams.size > 1) {
                                                selectedMatchForStreams = match
                                            } else {
                                                onPlayLiveStream("${match.homeTeam} vs ${match.awayTeam}", streamItem.streamUrl, match.logo)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                val rem = 3 - rowItems.size
                                if (rem > 0) {
                                    for (i in 0 until rem) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                    if (showChannels && filteredChannels.isNotEmpty()) {
                        val headerText = if (tvSubFilter == "thvl") {
                            "⭐ ĐÀI TRUYỀN HÌNH VĨNH LONG (THVL1 - THVL5)"
                        } else {
                            "📺 KÊNH TRUYỀN HÌNH VIỆT NAM & QUỐC TẾ (${filteredChannels.size} KÊNH FHD)"
                        }
                        item {
                            Text(
                                text = headerText,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                        }

                        // Hiển thị 6 kênh TV mỗi hàng
                        val channelRows = filteredChannels.chunked(6)
                        itemsIndexed(channelRows) { _, rowItems ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                for (channel in rowItems) {
                                    TvChannelCard(
                                        channel = channel,
                                        onClick = {
                                            onPlayLiveStream(channel.name, channel.streamUrl, channel.logoUrl)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                val rem = 6 - rowItems.size
                                if (rem > 0) {
                                    for (i in 0 until rem) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }

                } else {
                    // CATEGORY GRID VIEW WITH INFINITE SCROLL / PAGINATION
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
                                .focusable(interactionSource = loadMoreInter)
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
        }

        if (showSourceSettingsDialog) {
            SourceSettingsDialog(
                onDismiss = { showSourceSettingsDialog = false },
                onSaveSuccess = { reloadAllMovies() }
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
                            .width(500.dp)
                            .wrapContentHeight()
                            .padding(24.dp)
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
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                match.streams.forEachIndexed { idx, stream ->
                                    val sInter = remember { MutableInteractionSource() }
                                    val sFoc by sInter.collectIsFocusedAsState()

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (sFoc) AccentRed else Color(0xFF1E2533))
                                            .border(
                                                width = if (sFoc) 1.5.dp else 1.dp,
                                                color = if (sFoc) Color.White else Color(0x33FFFFFF),
                                                shape = RoundedCornerShape(8.dp)
                                            )
                                            .focusable(interactionSource = sInter)
                                            .clickable(interactionSource = sInter, indication = null) {
                                                selectedMatchForStreams = null
                                                onPlayLiveStream(
                                                    "${match.homeTeam} vs ${match.awayTeam} (${stream.commentator})",
                                                    stream.streamUrl,
                                                    match.logo
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
                                                    color = if (sFoc) Color.White.copy(alpha = 0.8f) else TextDim,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                        Text(
                                            text = "PHÁT NGAY ▶",
                                            color = if (sFoc) Color.White else AccentRed,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            val closeInter = remember { MutableInteractionSource() }
                            val closeFoc by closeInter.collectIsFocusedAsState()
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (closeFoc) Color.White else Color(0xFF2A3142))
                                    .focusable(interactionSource = closeInter)
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
                    .focusable(interactionSource = seeAllInter)
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
                        .focusable(interactionSource = moreInter)
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
                    .width(720.dp)
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
                                .focusable(interactionSource = closeInter)
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
                                    .focusable(interactionSource = pInter)
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
                                .focusable(interactionSource = saveInter)
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
                                .focusable(interactionSource = resetInter)
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
