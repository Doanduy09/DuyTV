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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.duytv.app.data.*
import com.duytv.app.ui.theme.*
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun MovieDetailScreen(
    slug: String,
    repository: MovieRepository,
    onBack: () -> Unit,
    onPlayEpisode: (
        slug: String,
        movieTitle: String,
        episodeName: String,
        streamUrl: String,
        posterUrl: String?,
        subtitleUrl: String?,
        initialPositionMs: Long,
        episodes: List<EpisodeItemDto>
    ) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var detailResponse by remember { mutableStateOf<MovieDetailResponse?>(null) }
    var selectedServerIndex by remember { mutableStateOf(0) }
    var vietnameseSubtitleUrl by remember { mutableStateOf<String?>(null) }
    val savedHistory = remember(slug) { WatchHistoryManager.getHistoryForMovie(context, slug) }

    LaunchedEffect(slug) {
        scope.launch {
            val res = repository.getMovieDetail(slug)
            detailResponse = res

            // Check if there is an IMDb code to prefetch Vietnamese subtitles
            val imdb = if (slug.startsWith("torrent_")) {
                slug.split("_").getOrNull(2)
            } else null

            if (!imdb.isNullOrBlank()) {
                val subUrl = repository.getVietnameseSubtitle(imdb)
                vietnameseSubtitleUrl = subUrl
            }

            isLoading = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentRed)
            }
        } else {
            val movie = detailResponse?.movie
            val serverGroups = detailResponse?.episodes ?: emptyList()
            val currentServer = serverGroups.getOrNull(selectedServerIndex)

            if (movie == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Không tìm thấy thông tin phim", color = TextWhite)
                }
                return
            }

            val backdropUrl = remember(movie.thumbUrl, movie.posterUrl) {
                val p = if (!movie.thumbUrl.isNullOrBlank()) movie.thumbUrl else movie.posterUrl
                RemoteConfigManager.resolveImageUrl(p)
            }

            // Background backdrop with gradients
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(Color(0xF00A0E17), Color(0xDE0A0E17), Color(0x800A0E17))
                        )
                    )
            )

            val screenWidthDp = LocalConfiguration.current.screenWidthDp
            val horizontalPad = if (screenWidthDp < 600) 16.dp else if (screenWidthDp < 900) 24.dp else 48.dp

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Android TV Standard 5% Safe Area Margin & Responsive Mobile/Tablet/PC Padding
                contentPadding = PaddingValues(horizontal = horizontalPad, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Back Button
                item {
                    val backInteraction = remember { MutableInteractionSource() }
                    val backFocused by backInteraction.collectIsFocusedAsState()

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (backFocused) AccentRed else CardBg)
                            .focusable(interactionSource = backInteraction)
                            .clickable(interactionSource = backInteraction, indication = null) {
                                onBack()
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Quay lại",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("QUAY LẠI", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Title & Metadata
                item {
                    Column(modifier = Modifier.widthIn(max = 700.dp).fillMaxWidth()) {
                        Text(
                            text = movie.name,
                            color = TextWhite,
                            fontSize = 32.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        if (!movie.originName.isNullOrBlank()) {
                            Text(
                                text = movie.originName,
                                color = TextDim,
                                fontSize = 16.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Badges: Quality, Year, Episode, Lang, Subtitle
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Badge(text = movie.quality ?: "FHD", isHighlight = movie.quality?.contains("4K") == true)
                            Badge(text = "${movie.year ?: 2026}")
                            Badge(text = movie.episodeCurrent ?: "Trọn bộ")
                            Badge(text = movie.lang ?: "Vietsub")

                            if (vietnameseSubtitleUrl != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFF00E676))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "💬 VIETSUB TỰ ĐỘNG: SẴN SÀNG",
                                        color = Color.Black,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        val cleanContent = movie.content
                            ?.replace(Regex("<[^>]*>"), "")
                            ?.replace("&nbsp;", " ")
                            ?.trim() ?: "Nội dung đang cập nhật..."

                        Text(
                            text = cleanContent,
                            color = Color(0xFFD0D7DE),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Server Selector (if more than 1 server)
                if (serverGroups.size > 1) {
                    item {
                        Column {
                            Text(
                                text = "CHỌN NGUỒN PHÁT (${serverGroups.size} nguồn):",
                                color = TextDim,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(serverGroups.indices.toList()) { idx ->
                                    val s = serverGroups[idx]
                                    val isSelected = (idx == selectedServerIndex)
                                    val sInter = remember { MutableInteractionSource() }
                                    val isFoc by sInter.collectIsFocusedAsState()

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(
                                                if (isFoc) AccentRed
                                                else if (isSelected) Color(0xFF252B3B)
                                                else CardBg
                                            )
                                            .border(
                                                width = if (isFoc) 2.dp else if (isSelected) 1.5.dp else 0.dp,
                                                color = if (isFoc) Color.White else if (isSelected) AccentRed else Color.Transparent,
                                                shape = RoundedCornerShape(6.dp)
                                            )
                                            .focusable(interactionSource = sInter)
                                            .clickable(interactionSource = sInter, indication = null) {
                                                selectedServerIndex = idx
                                            }
                                            .padding(horizontal = 16.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = s.serverName,
                                            color = Color.White,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected || isFoc) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Episode List or Torrent Stream Qualities
                item {
                    val episodes = currentServer?.serverData ?: emptyList()
                    val isTorrentServer = currentServer?.serverName?.contains("Torrent") == true
                    val episodeRowState = rememberLazyListState()

                    LaunchedEffect(episodes, savedHistory?.episodeName) {
                        val watchedName = savedHistory?.episodeName
                        if (!watchedName.isNullOrBlank() && episodes.isNotEmpty()) {
                            val idx = episodes.indexOfFirst { it.name.equals(watchedName, ignoreCase = true) }
                            if (idx > 0) {
                                episodeRowState.scrollToItem(maxOf(0, idx - 1))
                            }
                        }
                    }

                    Column {
                        if (savedHistory != null && !isTorrentServer) {
                            val resumeInter = remember { MutableInteractionSource() }
                            val isResumeFoc by resumeInter.collectIsFocusedAsState()
                            val resumePosLabel = formatDetailTime(savedHistory.positionMs)

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .padding(bottom = 16.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isResumeFoc) AccentRed else Color(0xFF00C853))
                                    .border(
                                        width = if (isResumeFoc) 2.5.dp else 1.5.dp,
                                        color = Color.White,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .focusable(interactionSource = resumeInter)
                                    .clickable(interactionSource = resumeInter, indication = null) {
                                        val matchedEp = episodes.firstOrNull {
                                            it.name.equals(savedHistory.episodeName, ignoreCase = true)
                                        }
                                        scope.launch {
                                            var streamUrl = matchedEp?.linkM3u8 ?: savedHistory.streamUrl
                                            if (streamUrl.isBlank() && matchedEp != null && matchedEp.slug.startsWith("cobe_ep_")) {
                                                val epId = matchedEp.slug.split("_").getOrNull(2)?.toLongOrNull()
                                                if (epId != null) {
                                                    streamUrl = repository.resolveCoBeEpisode(epId) ?: ""
                                                }
                                            }
                                            if (streamUrl.isBlank()) {
                                                streamUrl = matchedEp?.linkEmbed ?: savedHistory.streamUrl
                                            }
                                            if (streamUrl.isNotBlank()) {
                                                onPlayEpisode(
                                                    slug,
                                                    movie.name,
                                                    savedHistory.episodeName,
                                                    streamUrl,
                                                    backdropUrl,
                                                    vietnameseSubtitleUrl,
                                                    savedHistory.positionMs,
                                                    episodes
                                                )
                                            }
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = if (isResumeFoc) Color.White else Color.Black,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "TIẾP TỤC XEM: ${savedHistory.episodeName.uppercase()} (TỪ $resumePosLabel)",
                                    color = if (isResumeFoc) Color.White else Color.Black,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }

                        Text(
                            text = if (isTorrentServer) "CHỌN CHẤT LƯỢNG STREAM 4K / BLURAY:" else "DANH SÁCH TẬP (${episodes.size} tập):",
                            color = TextWhite,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        LazyRow(
                            state = episodeRowState,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(end = 40.dp)
                        ) {
                            items(episodes) { ep ->
                                val isCurrentWatched = savedHistory?.episodeName?.equals(ep.name, ignoreCase = true) == true
                                EpisodeButton(
                                    name = ep.name,
                                    isQualityBadge = isTorrentServer,
                                    isCurrentlyWatched = isCurrentWatched,
                                    onClick = {
                                        scope.launch {
                                            var streamUrl = ep.linkM3u8 ?: ""
                                            if (streamUrl.isBlank() && ep.slug.startsWith("cobe_ep_")) {
                                                val epId = ep.slug.split("_").getOrNull(2)?.toLongOrNull()
                                                if (epId != null) {
                                                    streamUrl = repository.resolveCoBeEpisode(epId) ?: ""
                                                }
                                            }
                                            if (streamUrl.isBlank()) {
                                                streamUrl = ep.linkEmbed ?: ""
                                            }
                                            if (streamUrl.isNotBlank()) {
                                                val resumePos = if (isCurrentWatched && savedHistory != null &&
                                                    (savedHistory.durationMs <= 0L || savedHistory.positionMs < savedHistory.durationMs - 15_000L)
                                                ) {
                                                    savedHistory.positionMs
                                                } else {
                                                    0L
                                                }
                                                onPlayEpisode(
                                                    slug,
                                                    movie.name,
                                                    ep.name,
                                                    streamUrl,
                                                    backdropUrl,
                                                    vietnameseSubtitleUrl,
                                                    resumePos,
                                                    episodes
                                                )
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(30.dp))
                }
            }
        }
    }
}

private fun formatDetailTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val s = totalSec % 60
    val m = (totalSec / 60) % 60
    val h = totalSec / 3600
    return if (h > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}

@Composable
fun Badge(text: String, isHighlight: Boolean = false) {
    Box(
        modifier = Modifier
            .background(
                if (isHighlight) AccentRed else CardBg,
                RoundedCornerShape(4.dp)
            )
            .border(0.5.dp, if (isHighlight) Color.White else Color(0xFF30363D), RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EpisodeButton(
    name: String,
    isQualityBadge: Boolean = false,
    isCurrentlyWatched: Boolean = false,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .defaultMinSize(minWidth = if (isQualityBadge) 160.dp else 80.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    isFocused -> AccentGlow
                    isCurrentlyWatched -> Color(0xFF1A3828)
                    else -> CardBg
                }
            )
            .border(
                width = if (isFocused) 2.dp else if (isCurrentlyWatched) 1.5.dp else 1.dp,
                color = when {
                    isFocused -> Color.White
                    isCurrentlyWatched -> Color(0xFF00E676)
                    else -> Color(0xFF30363D)
                },
                shape = RoundedCornerShape(6.dp)
            )
            .focusable(interactionSource = interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = when {
                    isFocused -> Color.White
                    isCurrentlyWatched -> Color(0xFF00E676)
                    else -> AccentRed
                },
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (isCurrentlyWatched) "$name • ĐANG COI" else name,
                color = if (isCurrentlyWatched && !isFocused) Color(0xFF00E676) else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}