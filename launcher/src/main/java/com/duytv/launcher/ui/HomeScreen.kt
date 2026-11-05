package com.duytv.launcher.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.duytv.launcher.R
import com.duytv.launcher.data.AppItem
import com.duytv.launcher.data.AppRepository
import com.duytv.launcher.utils.LunarCalendar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 1. Live Time State
    var currentTimeString by remember { mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())) }
    var solarDateString by remember { mutableStateOf(LunarCalendar.getTodaySolarString()) }
    var lunarDateString by remember { mutableStateOf(LunarCalendar.getTodayLunarString()) }
    var greetingString by remember { mutableStateOf(LunarCalendar.getGreetingString()) }

    LaunchedEffect(Unit) {
        while (true) {
            val now = Date()
            currentTimeString = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
            solarDateString = LunarCalendar.getTodaySolarString()
            lunarDateString = LunarCalendar.getTodayLunarString()
            greetingString = LunarCalendar.getGreetingString()
            delay(1000)
        }
    }

    // 2. Wallpaper state
    val wallpapers = listOf(R.drawable.wallpaper_default, R.drawable.wallpaper_alt)
    var currentWallpaperIndex by remember { mutableIntStateOf(0) }

    // 3. Apps state
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var focusedApp by remember { mutableStateOf<AppItem?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) {
        val loaded = AppRepository.getInstalledApps(context)
        apps = loaded
        if (loaded.isNotEmpty()) {
            focusedApp = loaded.first()
        }
    }

    // Focus Requester for initial app card focus
    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(apps) {
        if (apps.isNotEmpty()) {
            delay(300)
            try {
                initialFocusRequester.requestFocus()
            } catch (e: Exception) {}
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // === FULLSCREEN WALLPAPER ===
        Image(
            painter = painterResource(id = wallpapers[currentWallpaperIndex]),
            contentDescription = "Wallpaper",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Subtle gradient overlay for contrast & glassmorphism pop
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x33000000),
                            Color(0x11000000),
                            Color(0x55000000),
                            Color(0x88000000)
                        )
                    )
                )
        )

        // === TOP-LEFT: GREETING, CLOCK, SOLAR DATE, LUNAR DATE, FOCUSED APP TITLE ===
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 56.dp, top = 40.dp)
        ) {
            Text(
                text = greetingString,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Normal
            )

            Text(
                text = currentTimeString,
                color = Color.White,
                fontSize = 78.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-1).sp
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = solarDateString,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = lunarDateString,
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Large White App Title (Dynamically updates when user browses dock)
            Text(
                text = focusedApp?.label ?: "DuyTV",
                color = Color.White,
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
        }

        // === TOP-RIGHT: TRANSLUCENT STATUS CAPSULE (SETTINGS & WALLPAPER) ===
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 56.dp, top = 44.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0x2EFFFFFF))
                .border(1.dp, Color(0x3AFFFFFF), RoundedCornerShape(24.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Wallpaper switcher button
            TopBarIconButton(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Đổi hình nền",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = {
                    currentWallpaperIndex = (currentWallpaperIndex + 1) % wallpapers.size
                }
            )

            // Settings button
            TopBarIconButton(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Cài đặt TV",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            )
        }

        // === BOTTOM: TRANSLUCENT FROSTED GLASS CAPSULE DOCK ===
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp, start = 48.dp, end = 48.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(Color(0x22FFFFFF))
                .border(1.2.dp, Color(0x38FFFFFF), RoundedCornerShape(32.dp))
                .padding(vertical = 14.dp, horizontal = 18.dp)
        ) {
            LazyRow(
                state = listState,
                contentPadding = PaddingValues(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(apps, key = { _, item -> item.id }) { index, app ->
                    val isFirst = index == 0
                    AppCardItem(
                        app = app,
                        focusRequester = if (isFirst) initialFocusRequester else null,
                        onFocused = {
                            focusedApp = app
                            scope.launch {
                                listState.animateScrollToItem(index)
                            }
                        },
                        onClick = {
                            app.launch(context)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TopBarIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.2f else 1.0f, label = "iconScale")

    Box(
        modifier = Modifier
            .scale(scale)
            .clip(CircleShape)
            .background(if (isFocused) Color.White.copy(alpha = 0.35f) else Color.Transparent)
            .focusable()
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

@Composable
fun AppCardItem(
    app: AppItem,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.12f else 1.0f,
        animationSpec = tween(durationMillis = 180),
        label = "cardScale"
    )

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocused()
        }
    }

    val cardShape = RoundedCornerShape(18.dp)
    var modifier = Modifier
        .scale(scale)
        .width(160.dp)
        .height(96.dp)

    if (focusRequester != null) {
        modifier = modifier.focusRequester(focusRequester)
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = if (isFocused) 16.dp else 2.dp,
                shape = cardShape,
                ambientColor = if (isFocused) Color.White else Color.Black,
                spotColor = if (isFocused) Color.White else Color.Black
            )
            .clip(cardShape)
            .background(
                if (isFocused && app.packageName == "com.google.android.youtube.tv") Color.White
                else if (isFocused) Color(app.cardColor).copy(alpha = 0.95f)
                else Color(app.cardColor).copy(alpha = 0.75f)
            )
            .border(
                width = if (isFocused) 3.dp else 1.dp,
                color = if (isFocused) Color.White else Color(0x33FFFFFF),
                shape = cardShape
            )
            .focusable(interactionSource = interactionSource)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // App Custom Branded Rendering
        when (app.packageName) {
            "com.duytv.app" -> {
                // DuyTV Custom Cinema Neon Card
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "DUY",
                            color = Color(0xFFE50914),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "TV",
                            color = Color(0xFF38BDF8),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF38BDF8).copy(alpha = 0.2f))
                            .border(0.8.dp, Color(0xFF38BDF8), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "4K CINEMA",
                            color = Color(0xFF38BDF8),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            "com.google.android.youtube.tv" -> {
                // YouTube Official White Card
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 36.dp, height = 26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFFF0000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "YouTube",
                        color = Color(0xFF1E293B),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            "com.quang.monstertv" -> {
                // VietMiTV / Monster TV Brand Card
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Viet",
                            color = Color.White,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Mi",
                            color = Color(0xFF10B981),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "TV",
                            color = Color(0xFFEF4444),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "TRUYỀN HÌNH",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            "com.sports.tv" -> {
                // Sport Stream LIVE Card
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFEF4444))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "LIVE SPORT",
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "STREAM",
                        color = Color(0xFFFBBF24),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            else -> {
                // Generic App Card with drawable icon
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(8.dp)
                ) {
                    val bitmap = remember(app.icon) {
                        drawableToBitmap(app.icon)
                    }

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = app.label,
                            modifier = Modifier.size(38.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = app.label,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun drawableToBitmap(drawable: Drawable?): Bitmap? {
    if (drawable == null) return null
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    return try {
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 64
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 64
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        bitmap
    } catch (e: Exception) {
        null
    }
}
