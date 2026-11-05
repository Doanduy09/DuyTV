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

    Box(
        modifier = modifier
            .width(170.dp)
            .height(110.dp)
            .graphicsLayer {
                val sc = if (isFoc) 1.08f else 1.0f
                scaleX = sc
                scaleY = sc
            }
            .clip(RoundedCornerShape(10.dp))
            .background(if (isFoc) Color(0xFF222938) else CardBg)
            .border(
                width = if (isFoc) 2.5.dp else 1.dp,
                color = if (isFoc) AccentRed else Color(0x22FFFFFF),
                shape = RoundedCornerShape(10.dp)
            )
            .focusable(interactionSource = inter)
            .clickable(interactionSource = inter, indication = null, onClick = onClick)
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Channel Logo or Placeholder Icon
            if (!channel.logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    modifier = Modifier
                        .height(46.dp)
                        .fillMaxWidth(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2D3748)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📺", fontSize = 20.sp)
                }
            }

            // Channel Name & Live Badge
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
                    text = "🔴 LIVE",
                    color = Color(0xFFFF5252),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}
