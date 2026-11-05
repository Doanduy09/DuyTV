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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
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
    val totalSources = channel.allStreamUrls().size

    val bgBrush = if (isFoc) {
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
                val sc = if (isFoc) 1.07f else 1.0f
                scaleX = sc
                scaleY = sc
            }
            .clip(RoundedCornerShape(16.dp))
            .background(brush = bgBrush)
            .border(
                width = if (isFoc) 2.5.dp else 1.dp,
                color = if (isFoc) Color.White else Color(0x22FFFFFF),
                shape = RoundedCornerShape(16.dp)
            )
            .focusable(interactionSource = inter)
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

            // Channel Logo centered large & crisp like TV Dock
            if (!channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    modifier = Modifier
                        .height(54.dp)
                        .fillMaxWidth(0.88f),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2D3748)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📺", fontSize = 22.sp)
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
