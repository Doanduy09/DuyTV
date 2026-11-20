package com.duytv.app.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.io.File

/**
 * Màn hình trình chiếu ảnh chụp không dây từ Điện thoại lên Tivi (Local LAN Cast).
 * - Giới hạn kích thước giải mã tối đa 1920x1080 + Bitmap.Config.RGB_565 (chống OOM trên TV 1.5GB RAM)
 * - Tắt cache với file cục bộ để mỗi lần bắn ảnh mới luôn hiển thị ảnh mới tức thì
 * - Tắt Hardware Bitmaps (allowHardware = false) để chống tràn bộ nhớ đồ họa EGL_BAD_ALLOC
 * - Nhấn Back hoặc bấm bất kỳ phím nào để đóng và quay về màn hình trước
 */
@Composable
fun PhotoViewerScreen(
    imagePathOrUrl: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "Ảnh từ điện thoại"
) {
    val context = LocalContext.current
    val isLocalFile = remember(imagePathOrUrl) {
        imagePathOrUrl.startsWith("/") || imagePathOrUrl.contains("cacheDir")
    }
    val imageModel = remember(imagePathOrUrl, isLocalFile) {
        if (isLocalFile) File(imagePathOrUrl) else imagePathOrUrl
    }

    val request = remember(imagePathOrUrl, imageModel) {
        ImageRequest.Builder(context)
            .data(imageModel)
            .size(1920, 1080)
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(false)
            .memoryCachePolicy(if (isLocalFile) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .diskCachePolicy(if (isLocalFile) CachePolicy.DISABLED else CachePolicy.ENABLED)
            .crossfade(250)
            .build()
    }

    BackHandler(onBack = onBack)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusable()
            .clickable { onBack() }
    ) {
        AsyncImage(
            model = request,
            contentDescription = title,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        // HUD Pill mờ thanh lịch ở góc dưới màn hình
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xDD0F172A))
                .border(1.dp, Color(0xFF00E676), RoundedCornerShape(24.dp))
                .padding(horizontal = 24.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "📷 $title",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Nhấn nút Quay lại (Back) để đóng",
                    color = Color(0xFFA0AAB8),
                    fontSize = 12.sp
                )
            }
        }
    }
}
