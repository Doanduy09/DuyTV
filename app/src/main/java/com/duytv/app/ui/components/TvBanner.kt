package com.duytv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.duytv.app.data.MovieItemDto
import com.duytv.app.data.RemoteConfigManager
import com.duytv.app.ui.theme.*

@Composable
fun TvBanner(
    featuredMovie: MovieItemDto?,
    onPlayClick: (MovieItemDto) -> Unit,
    modifier: Modifier = Modifier
) {
    if (featuredMovie == null) return

    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val backdropUrl = remember(featuredMovie.thumbUrl, featuredMovie.posterUrl) {
        val path = if (!featuredMovie.thumbUrl.isNullOrBlank()) featuredMovie.thumbUrl else featuredMovie.posterUrl
        RemoteConfigManager.resolveImageUrl(path)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val bannerRequest = remember(backdropUrl, context) {
        coil.request.ImageRequest.Builder(context)
            .data(backdropUrl)
            .size(960, 540)
            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
            .allowHardware(false)
            .crossfade(false)
            .build()
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(CardBg)
    ) {
        // Backdrop Image (ép chuẩn kích thước phần cứng MediaTek mt5891)
        AsyncImage(
            model = bannerRequest,
            contentDescription = featuredMovie.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient Left-to-Right & Bottom-to-Top
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFA0A0E17),
                            Color(0xC80A0E17),
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xDC0A0E17))
                    )
                )
        )

        // Content
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
                .width(420.dp)
        ) {
            // Tag
            Box(
                modifier = Modifier
                    .background(AccentRed, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = "NỔI BẬT HÔM NAY",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = featuredMovie.name,
                color = TextWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (!featuredMovie.originName.isNullOrBlank()) {
                Text(
                    text = featuredMovie.originName,
                    color = TextDim,
                    fontSize = 14.sp,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Play Button with TV D-pad focus
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isFocused) AccentGlow else AccentRed)
                    .border(
                        width = if (isFocused) 2.dp else 0.dp,
                        color = Color.White,
                        shape = RoundedCornerShape(6.dp)
                    )
                    .focusable(interactionSource = interactionSource)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        onPlayClick(featuredMovie)
                    }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "XEM PHIM NGAY",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
