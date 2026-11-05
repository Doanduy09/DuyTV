package com.duytv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.duytv.app.data.FootballMatchGroup
import com.duytv.app.data.MatchStateHelper
import com.duytv.app.data.MatchStatus
import com.duytv.app.data.MatchStreamItem
import com.duytv.app.ui.theme.AccentRed
import com.duytv.app.ui.theme.CardBg

@Composable
fun FootballMatchCard(
    match: FootballMatchGroup,
    nowMillis: Long,
    onSelectStream: (MatchStreamItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val state = remember(match.kickoffTime, nowMillis / 1000) {
        MatchStateHelper.calculateMatchState(match.kickoffTime, nowMillis)
    }

    val inter = remember { MutableInteractionSource() }
    val isFoc by inter.collectIsFocusedAsState()

    val borderColor = when {
        isFoc -> AccentRed
        state.status == MatchStatus.LIVE -> Color(0xFFFF1744)
        state.status == MatchStatus.UPCOMING -> Color(0xFFFFB300)
        else -> Color(0x22FFFFFF)
    }

    val cardBg = when (state.status) {
        MatchStatus.FINISHED -> Color(0x33121722)
        MatchStatus.LIVE -> Color(0xEE1E1528)
        else -> CardBg
    }

    Box(
        modifier = modifier
            .width(320.dp)
            .height(180.dp)
            .graphicsLayer {
                val sc = if (isFoc) 1.05f else 1.0f
                scaleX = sc
                scaleY = sc
                alpha = if (state.status == MatchStatus.FINISHED) 0.55f else 1.0f
            }
            .clip(RoundedCornerShape(12.dp))
            .background(cardBg)
            .border(if (isFoc) 2.5.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .focusable(interactionSource = inter)
            .clickable(interactionSource = inter, indication = null) {
                if (match.streams.isNotEmpty()) {
                    onSelectStream(match.streams.first())
                }
            }
            .padding(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Giải đấu & Badge Trạng thái thời gian thực
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = match.tournament.ifBlank { "TRỰC TIẾP BÓNG ĐÁ" }.uppercase(),
                    color = Color(0xFFA0AAB8),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                // Status Badge (Đang Live / Sắp đá / Kết thúc)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            when (state.status) {
                                MatchStatus.LIVE -> Color(0x33FF1744)
                                MatchStatus.UPCOMING -> Color(0x33FFB300)
                                MatchStatus.FINISHED -> Color(0x33888888)
                            }
                        )
                        .border(
                            1.dp,
                            when (state.status) {
                                MatchStatus.LIVE -> Color(0xFFFF1744)
                                MatchStatus.UPCOMING -> Color(0xFFFFB300)
                                MatchStatus.FINISHED -> Color(0xFF888888)
                            },
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = state.label,
                        color = when (state.status) {
                            MatchStatus.LIVE -> Color(0xFFFF5252)
                            MatchStatus.UPCOMING -> Color(0xFFFFC107)
                            MatchStatus.FINISHED -> Color(0xFFB0BEC5)
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            // Teams VS Center Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Đội Nhà
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    if (!match.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = match.logo,
                            contentDescription = match.homeTeam,
                            modifier = Modifier.size(36.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2D3748)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⚽", fontSize = 16.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = match.homeTeam,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // VS & Giờ đá ở giữa
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = match.kickoffTime,
                        color = if (state.status == MatchStatus.LIVE) Color(0xFFFF5252) else Color(0xFF00E676),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black
                    )
                    Text(
                        text = "VS",
                        color = Color(0xFF718096),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Đội Khách
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f)
                ) {
                    if (!match.logo.isNullOrBlank()) {
                        AsyncImage(
                            model = match.logo,
                            contentDescription = match.awayTeam,
                            modifier = Modifier.size(36.dp),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2D3748)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⚽", fontSize = 16.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = match.awayTeam,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Footer: BLV & Nút bấm hành động
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val blv = match.streams.firstOrNull()?.commentator ?: "Tiếng Việt"
                Text(
                    text = "🎙️ $blv",
                    color = Color(0xFFA0AAB8),
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Text(
                    text = when (state.status) {
                        MatchStatus.LIVE -> "▶️ XEM TRỰC TIẾP"
                        MatchStatus.UPCOMING -> "⏳ CHƯA ĐÁ"
                        MatchStatus.FINISHED -> "⏹️ HẾT TRẬN"
                    },
                    color = when (state.status) {
                        MatchStatus.LIVE -> AccentRed
                        MatchStatus.UPCOMING -> Color(0xFFFFB300)
                        MatchStatus.FINISHED -> Color(0xFF888888)
                    },
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
