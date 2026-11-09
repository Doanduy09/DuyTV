package com.duytv.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duytv.app.data.MovieItemDto
import com.duytv.app.data.MovieRepository
import com.duytv.app.ui.components.MovieCard
import com.duytv.app.ui.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    repository: MovieRepository,
    onMovieSelected: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var allResults by remember { mutableStateOf<List<MovieItemDto>>(emptyList()) }
    var selectedSourceFilter by remember { mutableStateOf("ALL") }
    var isSearching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    val popularKeywords = listOf(
        "Đắc Kỷ Trụ Vương",
        "Bao Thanh Thiên",
        "Thiên Long Bát Bộ",
        "Tây Du Ký",
        "Thần Điêu Đại Hiệp",
        "Phim Chiếu Rạp",
        "Avengers",
        "Dune",
        "Avatar",
        "Lật Mặt",
        "Deadpool",
        "Hành động",
        "Kinh dị",
        "Hoạt hình"
    )

    fun performSearch(text: String) {
        if (text.isBlank()) {
            allResults = emptyList()
            isSearching = false
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            isSearching = true
            delay(350)
            val res = repository.searchAllSources(text)
            allResults = res
            isSearching = false
        }
    }

    val filteredResults = remember(allResults, selectedSourceFilter) {
        when (selectedSourceFilter) {
            "COBEPHIM" -> allResults.filter { it.source == "CoBePhim" }
            "TORRENT" -> allResults.filter { it.source == "Torrent4K" }
            "KKPHIM" -> allResults.filter { it.source == "KKPhim" }
            "NGUONC" -> allResults.filter { it.source == "NguonC" }
            else -> allResults
        }
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val horizontalPad = if (screenWidthDp < 600) 16.dp else if (screenWidthDp < 900) 24.dp else 48.dp

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = horizontalPad, vertical = 20.dp)
    ) {
        // Top Bar: Back & Search Field
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            val backInter = remember { MutableInteractionSource() }
            val backFoc by backInter.collectIsFocusedAsState()

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (backFoc) AccentRed else CardBg)
                    // 1-tap clickable
                    .clickable(interactionSource = backInter, indication = null, onClick = onBack)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("QUAY LẠI", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.width(20.dp))

            // Search input field
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(CardBg)
                    .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = AccentRed,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        performSearch(it)
                    },
                    textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(AccentRed),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { performSearch(query) }),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { innerTextField ->
                        if (query.isEmpty()) {
                            Text("Nhập tên bộ phim bạn muốn tìm kiếm (tìm đồng thời mọi nguồn: Phim Rạp, 4K Cinema, KKPhim, Nguồn C)...", color = TextDim, fontSize = 13.sp)
                        }
                        innerTextField()
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Quick Suggestion Chips
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(popularKeywords) { tag ->
                val tagInter = remember { MutableInteractionSource() }
                val tagFoc by tagInter.collectIsFocusedAsState()

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (tagFoc) AccentRed else Color(0xFF1E2638))
                        .border(
                            width = if (tagFoc) 1.5.dp else 0.dp,
                            color = Color.White,
                            shape = RoundedCornerShape(6.dp)
                        )
                        // 1-tap clickable
                        .clickable(interactionSource = tagInter, indication = null) {
                            query = tag
                            performSearch(tag)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = tag, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        // Source Filters if results are found
        if (allResults.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val filters = listOf(
                    Triple("ALL", "TẤT CẢ NGUỒN (${allResults.size})", Color(0xFF252B3B)),
                    Triple("COBEPHIM", "🌸 COBEPHIM / CỔ TRANG (${allResults.count { it.source == "CoBePhim" }})", Color(0xFFD81B60)),
                    Triple("TORRENT", "💎 4K TORRENT (${allResults.count { it.source == "Torrent4K" }})", Color(0xFFE50914)),
                    Triple("KKPHIM", "⚡ KKPHIM VIP (${allResults.count { it.source == "KKPhim" }})", Color(0xFF00897B)),
                    Triple("NGUONC", "🌐 NGUỒN C (${allResults.count { it.source == "NguonC" }})", Color(0xFF5E35B1))
                )

                for ((id, title, color) in filters) {
                    val isSelected = (selectedSourceFilter == id)
                    val fInter = remember { MutableInteractionSource() }
                    val isFFoc by fInter.collectIsFocusedAsState()

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isFFoc) AccentRed else if (isSelected) color else CardBg)
                            .border(
                                width = if (isFFoc) 1.5.dp else if (isSelected) 1.dp else 0.dp,
                                color = Color.White,
                                shape = RoundedCornerShape(4.dp)
                            )
                            // 1-tap clickable
                            .clickable(interactionSource = fInter, indication = null) {
                                selectedSourceFilter = id
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = title,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected || isFFoc) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Results Grid
        if (isSearching) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentRed)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Đang tìm kiếm đồng thời trên tất cả nguồn phim...", color = TextDim, fontSize = 13.sp)
                }
            }
        } else if (allResults.isEmpty() && query.isNotBlank()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Không tìm thấy kết quả cho \"$query\" trên bất kỳ nguồn phim nào.", color = TextDim, fontSize = 14.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 145.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filteredResults, key = { it.source + "_" + it.slug + "_" + it.name }) { movie ->
                    MovieCard(
                        movie = movie,
                        onClick = { onMovieSelected(movie.slug) }
                    )
                }
            }
        }
    }
}