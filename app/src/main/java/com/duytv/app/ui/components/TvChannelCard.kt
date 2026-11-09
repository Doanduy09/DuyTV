package com.duytv.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.duytv.app.data.TvChannel
import com.duytv.app.ui.theme.AccentRed
import com.duytv.app.ui.theme.CardBg

@Composable
fun TvChannelCard(
    channel: TvChannel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val inter = remember { MutableInteractionSource() }
    val isFoc by inter.collectIsFocusedAsState()
    val isPress by inter.collectIsPressedAsState()
    val isHighlighted = isFoc || isPress
    val totalSources = remember(channel) { channel.allStreamUrls().size }
    var logoFailed by remember(channel.logoUrl) { mutableStateOf(false) }
    val callSign = remember(channel.name) {
        channel.name.substringBefore(" - ")
            .replace(" HD", "", ignoreCase = true)
            .replace(" SD", "", ignoreCase = true)
            .trim()
            .take(12)
    }

    val bgBrush = if (isHighlighted) {
        Brush.verticalGradient(
            colors = listOf(Color(0xFF566074), Color(0xFF2B313E))
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(Color(0xFF161A24), Color(0xFF10131B))
        )
    }

    Box(
        modifier = modifier
            .width(176.dp)
            .height(106.dp)
            .graphicsLayer {
                val sc = if (isHighlighted) 1.07f else 1.0f
                scaleX = sc
                scaleY = sc
            }
            .clip(RoundedCornerShape(16.dp))
            .background(brush = bgBrush)
            .border(
                width = if (isHighlighted) 2.5.dp else 1.dp,
                color = if (isHighlighted) Color.White else Color(0x22FFFFFF),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(interactionSource = inter, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (totalSources > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "$totalSources link",
                    color = Color(0xFF00E676),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // Channel Logo centered large & crisp like TV Dock (giới hạn decode 240x160 giữ kênh Alpha PNG)
            if (!channel.logoUrl.isNullOrBlank() && !logoFailed) {
                val context = LocalContext.current
                val logoRequest = remember(channel.logoUrl) {
                    ImageRequest.Builder(context)
                        .data(channel.logoUrl)
                        .size(240, 160)
                        .allowRgb565(false)
                        .build()
                }
                AsyncImage(
                    model = logoRequest,
                    contentDescription = channel.name,
                    onError = { logoFailed = true },
                    modifier = Modifier
                        .height(54.dp)
                        .fillMaxWidth(0.88f),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .height(50.dp)
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(Color(0xFF232B3E), Color(0xFF1A202E))
                            )
                        )
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = callSign,
                        color = Color.White,
                        fontSize = if (callSign.length <= 6) 20.sp else 14.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Channel Name & Live Dot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = channel.name,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = "● LIVE",
                    color = if (isFoc) Color(0xFF00E676) else Color(0xFFFF5252),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}
