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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import com.duytv.app.ui.components.MovieCard
import com.duytv.app.ui.components.TvBanner
import com.duytv.app.ui.theme.*
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var selectedCategory by remember { mutableStateOf(NAV_CATEGORIES.first()) }

    // Swimlane lists for "TẤT CẢ" view
    var latestMovies by remember { mutableStateOf<List<MovieItemDto>>(emptyList()) }
    var cinemaMovies by remember { mutableStateOf<List<MovieItemDto>>(emptyList()) }
    var torrentMovies by remember { mutableStateOf<List<MovieItemDto>>(emptyList()) }
    var seriesMovies by remember { mutableStateOf<List<MovieItemDto>>(emptyList()) }
    var singleMovies by remember { mutableStateOf<List<MovieItemDto>>(emptyList()) }
    var animeMovies by remember { mutableStateOf<List<MovieItemDto>>(emptyList()) }
    var watchHistory by remember { mutableStateOf<List<WatchHistoryItem>>(emptyList()) }

    // Specific Category list & pagination state
    var categoryMovies by remember { mutableStateOf<List<MovieItemDto>>(emptyList()) }
    var categoryPage by remember { mutableIntStateOf(1) }
    var isCategoryLoadingMore by remember { mutableStateOf(false) }

    val currentTime = remember {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.format(Date())
    }

    LaunchedEffect(Unit) {
        scope.launch {
            watchHistory = WatchHistoryManager.getHistory(context)
            RemoteConfigManager.fetchRemoteConfig()

            // Fetch initial swimlane data
            val newOnes = repository.getNewUpdatedMovies(1)
            latestMovies = newOnes

            cinemaMovies = repository.getMoviesByCategory("phim-chieu-rap", 1)
            torrentMovies = repository.getTop4KTorrents(1)
            seriesMovies = repository.getMoviesByCategory("phim-bo", 1)
            singleMovies = repository.getMoviesByCategory("phim-le", 1)
            animeMovies = repository.getMoviesByCategory("hoat-hinh", 1)

            isLoading = false
        }
    }

    // When switching category tabs
    fun switchCategory(category: NavCategory) {
        selectedCategory = category
        if (category.id != "all") {
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
                                    text = "ĐA NGUỒN • 4K CINEMA",
                                    color = Color(0xFF00E676),
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
                                    items(watchHistory) { item ->
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