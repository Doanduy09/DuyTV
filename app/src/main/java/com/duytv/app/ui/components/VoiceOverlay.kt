package com.duytv.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duytv.app.util.VoiceState

@Composable
fun VoiceOverlay(
    state: VoiceState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isVisible = state !is VoiceState.Idle

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(tween(200)) + slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(250)),
        exit = fadeOut(tween(250)) + slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(250)),
        modifier = modifier
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "VoicePulse")
        val idlePulse by infiniteTransition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "idlePulse"
        )

        val rms = (state as? VoiceState.Listening)?.rmsDb ?: 0f
        val animatedScale = if (state is VoiceState.Listening) {
            1.0f + (rms / 10f).coerceIn(0f, 0.45f) * idlePulse
        } else {
            1.0f
        }

        val primaryColor = when (state) {
            is VoiceState.Listening -> Color(0xFF00E5FF) // Cyan
            is VoiceState.Processing -> Color(0xFFFFD54F) // Amber Gold
            is VoiceState.Success -> Color(0xFF00E676) // Emerald Green
            is VoiceState.Error -> Color(0xFFFF5252) // Soft Red
            VoiceState.Idle -> Color.Transparent
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x40000000))
                .clickable { onDismiss() }
                .padding(bottom = 54.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(32.dp))
                    .shadow(16.dp, RoundedCornerShape(32.dp))
                    .background(Color(0xEE10141E))
                    .border(
                        width = 1.5.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(primaryColor.copy(alpha = 0.85f), primaryColor.copy(alpha = 0.25f))
                        ),
                        shape = RoundedCornerShape(32.dp)
                    )
                    .clickable { onDismiss() }
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Pulsing Icon Capsule
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .scale(animatedScale)
                        .clip(RoundedCornerShape(23.dp))
                        .background(primaryColor.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    when (state) {
                        is VoiceState.Success -> {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Success",
                                tint = primaryColor,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        is VoiceState.Error -> {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error",
                                tint = primaryColor,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Mic",
                                tint = primaryColor,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                // Text Feedback Block: Tinh gọn, ẩn nội dung thừa, chuyên nghiệp như Apple TV / Google TV
                Column(
                    verticalArrangement = Arrangement.Center
                ) {
                    val title = when (state) {
                        is VoiceState.Listening -> "ĐANG LẮNG NGHE..."
                        is VoiceState.Processing -> "ĐANG NHẬN DIỆN..."
                        is VoiceState.Success -> "ĐÃ CHUYỂN ĐÀI"
                        is VoiceState.Error -> "CHƯA RÕ KHẨU LỆNH"
                        VoiceState.Idle -> ""
                    }

                    val desc = when (state) {
                        is VoiceState.Listening -> "" // Ẩn nội dung hướng dẫn khi đang nghe để giao diện tối giản, sang trọng
                        is VoiceState.Processing -> state.partialText
                        is VoiceState.Success -> state.message
                        is VoiceState.Error -> state.message
                        VoiceState.Idle -> ""
                    }

                    Text(
                        text = title,
                        color = primaryColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                    if (desc.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = desc,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
        }
    }
}
