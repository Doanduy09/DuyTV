package com.duytv.launcher.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.provider.Settings
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Size
import com.duytv.launcher.R
import com.duytv.launcher.data.AppItem
import com.duytv.launcher.data.AppRepository
import com.duytv.launcher.data.LauncherMatchItem
import com.duytv.launcher.data.MatchLiveStatus
import com.duytv.launcher.data.SportsRepository
import com.duytv.launcher.utils.LunarCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.Locale

private fun isTvOkKey(keyCode: Int): Boolean {
    return keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER ||
        keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
        keyCode == KeyEvent.KEYCODE_BUTTON_A
}

private fun isTvMenuKey(keyCode: Int): Boolean {
    return keyCode == KeyEvent.KEYCODE_MENU ||
        keyCode == KeyEvent.KEYCODE_INFO ||
        keyCode == KeyEvent.KEYCODE_BOOKMARK ||
        keyCode == KeyEvent.KEYCODE_SETTINGS
}

private fun extractDigitFromKeyCode(keyCode: Int): Int? {
    return when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }
}

private fun getAvailableRamMb(context: Context): Int {
    return try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        (memInfo.availMem / (1024L * 1024L)).toInt()
    } catch (_: Exception) {
        320
    }
}

private fun cleanBackgroundRam(context: Context): Pair<Int, Int> {
    val beforeMb = getAvailableRamMb(context)
    try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val targetPackages = mutableSetOf(
            "com.google.android.youtube.tv",
            "com.lonelycatgames.Xplore",
            "com.android.tv.settings",
            "studio.scillarium.ottnavigator",
            "com.android.vending",
            "com.google.android.katniss",
            "com.android.htmlviewer",
            "com.android.defcontainer",
            "com.google.android.packageinstaller",
            "jp.co.sharp.av.android.aquostvappasia"
        )
        AppRepository.getInstalledApps(context).forEach { appItem ->
            val pkg = appItem.packageName
            if (pkg != "com.duytv.launcher" && pkg != "com.duytv.app") {
                targetPackages.add(pkg)
            }
        }
        targetPackages.forEach { pkg ->
            try {
                am?.killBackgroundProcesses(pkg)
            } catch (_: Exception) {}
        }
        System.gc()
    } catch (_: Exception) {}
    val afterMb = getAvailableRamMb(context)
    val freed = (afterMb - beforeMb).coerceAtLeast(10)
    return Pair(freed, afterMb)
}

private const val PREFS_LAUNCHER_UTILITY = "duytv_launcher_utility_prefs"

private fun saveLastWatchedItem(
    context: Context,
    id: String,
    name: String,
    streamUrl: String,
    logoUrl: String,
    backupUrls: List<String>
) {
    try {
        context.getSharedPreferences(PREFS_LAUNCHER_UTILITY, Context.MODE_PRIVATE)
            .edit()
            .putString("last_id", id)
            .putString("last_name", name)
            .putString("last_url", streamUrl)
            .putString("last_logo", logoUrl)
            .putString("last_backups", backupUrls.joinToString("|||"))
            .apply()
    } catch (_: Exception) {}
}

private fun getLastWatchedId(context: Context): String {
    return try {
        context.getSharedPreferences(PREFS_LAUNCHER_UTILITY, Context.MODE_PRIVATE)
            .getString("last_id", "thvl1_hd") ?: "thvl1_hd"
    } catch (_: Exception) {
        "thvl1_hd"
    }
}

private fun getLastWatchedName(context: Context): String {
    return try {
        context.getSharedPreferences(PREFS_LAUNCHER_UTILITY, Context.MODE_PRIVATE)
            .getString("last_name", "THVL1 HD") ?: "THVL1 HD"
    } catch (_: Exception) {
        "THVL1 HD"
    }
}

private fun resumeLastWatched(context: Context) {
    try {
        val prefs = context.getSharedPreferences(PREFS_LAUNCHER_UTILITY, Context.MODE_PRIVATE)
        val id = prefs.getString("last_id", null)
        val name = prefs.getString("last_name", null)
        val url = prefs.getString("last_url", null)
        val logo = prefs.getString("last_logo", "") ?: ""
        val backupsRaw = prefs.getString("last_backups", "") ?: ""
        val backups = if (backupsRaw.isBlank()) emptyList() else backupsRaw.split("|||").filter { it.isNotBlank() }

        if (!id.isNullOrBlank() && !name.isNullOrBlank() && !url.isNullOrBlank()) {
            val intent = context.packageManager.getLaunchIntentForPackage("com.duytv.app")
                ?: context.packageManager.getLeanbackLaunchIntentForPackage("com.duytv.app")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                intent.putExtra("channel_id", id)
                intent.putExtra("channel_name", name)
                intent.putExtra("stream_url", url)
                intent.putExtra("logo_url", logo)
                intent.putStringArrayListExtra("backup_urls", ArrayList(backups))
                Toast.makeText(context, "▶ Mở tiếp: $name", Toast.LENGTH_SHORT).show()
                context.startActivity(intent)
                return
            }
        }
        // Fallback: mở kênh THVL1 HD hoặc kênh đầu tiên
        val fallback = DEFAULT_TV_CHANNELS.firstOrNull { it.id == "thvl1_hd" } ?: DEFAULT_TV_CHANNELS.first()
        launchTvChannel(context, fallback)
    } catch (_: Exception) {}
}

data class TvChannelItem(
    val id: String,
    val name: String,
    val logoUrl: String,
    val streamUrl: String,
    val groupTitle: String,
    val backupUrls: List<String> = emptyList()
)

private enum class FocusedRowType {
    CHANNEL,
    FOOTBALL,
    APP
}

private val DEFAULT_TV_CHANNELS = listOf(
    TvChannelItem(
        id = "vtv1_hd",
        name = "VTV1 HD",
        logoUrl = "https://i.postimg.cc/F1FvzstX/V1.png",
        streamUrl = "https://luuchieu.truyenhinhso.vn/media/live/C90B082632CD4A75F4B3EDB3/index.m3u8",
        groupTitle = "VTV Quốc Gia",
        backupUrls = listOf("https://vmttv.dpdns.org/tv360/?id=vtv1")
    ),
    TvChannelItem(
        id = "vtv3_hd",
        name = "VTV3 HD",
        logoUrl = "https://i.postimg.cc/B8cWZRM0/V3.png",
        streamUrl = "https://vmttv.dpdns.org/tv360/?id=vtv3",
        groupTitle = "VTV Quốc Gia"
    ),
    TvChannelItem(
        id = "vtv2_hd",
        name = "VTV2 HD",
        logoUrl = "https://i.postimg.cc/yDR4tDRm/V2.png",
        streamUrl = "https://vtvgolive-sctvdrm.vtvdigital.vn/manifest/vtv2/master.m3u8",
        groupTitle = "VTV Quốc Gia",
        backupUrls = listOf("https://vmttv.dpdns.org/tv360/?id=vtv2")
    ),
    TvChannelItem(
        id = "thvl1_hd",
        name = "THVL1 HD",
        logoUrl = "https://i.imgur.com/q3fjpYc.png",
        streamUrl = "https://freem3u.xyz/api/live/play.m3u8?vid=25",
        groupTitle = "THVL Vĩnh Long",
        backupUrls = listOf("https://1011154949.vnns.net/CDN-FPT02/THVL1-HD-1080p/playlist.m3u8")
    ),
    TvChannelItem(
        id = "thvl2_hd",
        name = "THVL2 HD",
        logoUrl = "https://i.imgur.com/zGv54Ed.png",
        streamUrl = "https://freem3u.xyz/api/live/play.m3u8?vid=26",
        groupTitle = "THVL Vĩnh Long",
        backupUrls = listOf("https://1011154949.vnns.net/CDN-FPT02/THVL2-HD-1080p/playlist.m3u8")
    ),
    TvChannelItem(
        id = "thvl3_hd",
        name = "THVL3 HD",
        logoUrl = "https://i.imgur.com/44c1Yoz.png",
        streamUrl = "https://freem3u.xyz/api/live/play.m3u8?vid=219",
        groupTitle = "THVL Vĩnh Long"
    ),
    TvChannelItem(
        id = "thvl4_hd",
        name = "THVL4 HD",
        logoUrl = "https://i.imgur.com/JWDsJxB.png",
        streamUrl = "https://freem3u.xyz/api/live/play.m3u8?vid=220",
        groupTitle = "THVL Vĩnh Long"
    ),
    TvChannelItem(
        id = "htv7_hd_fpt",
        name = "HTV7 HD",
        logoUrl = "https://i.imgur.com/n2PEK28.png",
        streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTV7-HD-1080p/chunks.m3u8",
        groupTitle = "HTV TP.HCM"
    ),
    TvChannelItem(
        id = "htv9_hd_fpt",
        name = "HTV9 HD",
        logoUrl = "https://i.imgur.com/6GSN524.png",
        streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTV9-HD-1080p/chunks.m3u8",
        groupTitle = "HTV TP.HCM"
    ),
    TvChannelItem(
        id = "htv2_hd_fpt",
        name = "HTV2 Vie Channel",
        logoUrl = "https://i.imgur.com/2npoF3Q.png",
        streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTV2-HD-1080p/playlist.m3u8",
        groupTitle = "HTV TP.HCM"
    ),
    TvChannelItem(
        id = "htvc_phim_fpt",
        name = "HTVC Phim HD",
        logoUrl = "https://i.imgur.com/P0wqvmu.png",
        streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTVC-PHIM-HD-1080p/chunks.m3u8",
        groupTitle = "HTV TP.HCM"
    ),
    TvChannelItem(
        id = "htvc_thuanviet_fpt",
        name = "HTVC Thuần Việt",
        logoUrl = "https://i.imgur.com/KtMeo6q.png",
        streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTVC-THUANVIET-HD-1080p/chunks.m3u8",
        groupTitle = "HTV TP.HCM"
    ),
    TvChannelItem(
        id = "htvc_thethao_fpt",
        name = "HTVC Thể Thao",
        logoUrl = "https://i.imgur.com/ikaiET3.png",
        streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTV-THETHAO-HD-1080p/chunks.m3u8",
        groupTitle = "HTV TP.HCM"
    ),
    TvChannelItem(
        id = "htvc_giadinh_fpt",
        name = "HTVC Gia Đình",
        logoUrl = "https://i.ibb.co/BVQtkHgF/IMG-2899.png",
        streamUrl = "https://1011154949.vnns.net/CDN-FPT02/HTVC-GIADINH-HD-1080p/chunks.m3u8",
        groupTitle = "HTV TP.HCM"
    ),
    TvChannelItem(
        id = "vtv4_hd",
        name = "VTV4 HD",
        logoUrl = "https://i.postimg.cc/Wqzxnzj0/V4.png",
        streamUrl = "https://vtvgolive-sctvdrm.vtvdigital.vn/manifest/vtv4/master.m3u8",
        groupTitle = "VTV Quốc Gia",
        backupUrls = listOf("https://vmttv.dpdns.org/tv360/?id=vtv4")
    ),
    TvChannelItem(
        id = "vtv5_tnb",
        name = "VTV5 Tây Nam Bộ",
        logoUrl = "https://i.postimg.cc/68ngzDsM/V5TNB.png",
        streamUrl = "https://vtvgolive-sctvdrm.vtvdigital.vn/manifest/vtv5tnb/master.m3u8",
        groupTitle = "VTV Quốc Gia"
    ),
    TvChannelItem(
        id = "vtv7_hd",
        name = "VTV7 HD",
        logoUrl = "https://i.postimg.cc/ykSbYwN8/V7.png",
        streamUrl = "https://vtvgolive-sctvdrm.vtvdigital.vn/manifest/vtv7/master.m3u8",
        groupTitle = "VTV Quốc Gia"
    ),
    TvChannelItem(
        id = "vtv8_hd",
        name = "VTV8 HD",
        logoUrl = "https://i.postimg.cc/k2QYWK8w/V8.png",
        streamUrl = "https://vtvgolive-sctvdrm.vtvdigital.vn/manifest/vtv8/master.m3u8",
        groupTitle = "VTV Quốc Gia"
    ),
    TvChannelItem(
        id = "vtv9_hd",
        name = "VTV9 HD",
        logoUrl = "https://i.postimg.cc/phT0NSS6/V9.png",
        streamUrl = "https://vtvgolive-sctvdrm.vtvdigital.vn/manifest/vtv9/master.m3u8",
        groupTitle = "VTV Quốc Gia",
        backupUrls = listOf("https://vmttv.dpdns.org/tv360/?id=vtv9")
    ),
    TvChannelItem(
        id = "antv_hd",
        name = "ANTV HD",
        logoUrl = "https://i.imgur.com/39xKjU9.png",
        streamUrl = "https://liveh12.vtvprime.vn/hls/ANNINHTV/index.m3u8",
        groupTitle = "VTV Quốc Gia"
    ),
    TvChannelItem(
        id = "qpvn_hd",
        name = "QPVN HD",
        logoUrl = "https://i.imgur.com/qWWpYRR.png",
        streamUrl = "https://liveh12.vtvprime.vn/hls/QPTV/index.m3u8",
        groupTitle = "VTV Quốc Gia"
    ),
    TvChannelItem(
        id = "hanoi1_hd_prime",
        name = "Hà Nội 1 HD",
        logoUrl = "https://i.postimg.cc/hvL15x02/Hanoi-TV1.png",
        streamUrl = "https://liveh34.vtvprime.vn/hls/HANOI1TV/index.m3u8",
        groupTitle = "Đài Tỉnh Thành"
    ),
    TvChannelItem(
        id = "hanoi2_hd_prime",
        name = "Hà Nội 2 HD",
        logoUrl = "https://i.postimg.cc/Nft4rJgS/Hanoi-TV2.png",
        streamUrl = "https://liveh34.vtvprime.vn/hls/HANOI2/index.m3u8",
        groupTitle = "Đài Tỉnh Thành"
    ),
    TvChannelItem(
        id = "dongthap_hd_prime",
        name = "Đồng Tháp TV",
        logoUrl = "https://i.imgur.com/vZYlGIW.png",
        streamUrl = "https://liveh34.vtvprime.vn/hls/DONGTHAPTV/index.m3u8",
        groupTitle = "Đài Tỉnh Thành"
    ),
    TvChannelItem(
        id = "cantho_tv_cdn",
        name = "Cần Thơ TV",
        logoUrl = "https://i.imgur.com/qWWpYRR.png",
        streamUrl = "https://live.canthotv.vn/live/tv/chunklist.m3u8",
        groupTitle = "Đài Tỉnh Thành"
    ),
    TvChannelItem(
        id = "angiang_sd_fpt",
        name = "An Giang TV",
        logoUrl = "https://i.imgur.com/vZYlGIW.png",
        streamUrl = "https://1011154949.vnns.net/CDN-FPT02/ANGIANG-SD-720p/playlist.m3u8",
        groupTitle = "Đài Tỉnh Thành"
    ),
    TvChannelItem(
        id = "dongnai1_sd_fpt",
        name = "Đồng Nai 1",
        logoUrl = "https://i.imgur.com/vZYlGIW.png",
        streamUrl = "https://1011154949.vnns.net/CDN-FPT02/DONGNAI1-SD-720p/playlist.m3u8",
        groupTitle = "Đài Tỉnh Thành"
    )
)

/**
 * Tách riêng Header Đồng hồ & Lịch Âm Dương để chỉ recompose 1 lần mỗi phút,
 * triệt tiêu hoàn toàn hiện tượng giật khung hình (Janky Frames) trên toàn bộ màn hình chính.
 */
@Composable
private fun ClockAndCalendarHeader(modifier: Modifier = Modifier) {
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
            val delayMs = (60_000L - (System.currentTimeMillis() % 60_000L)).coerceAtLeast(1_000L)
            delay(delayMs)
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "DUY",
                    color = Color(0xFFE50914),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "TV",
                    color = Color(0xFF38BDF8),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF38BDF8).copy(alpha = 0.2f))
                        .border(0.8.dp, Color(0xFF38BDF8), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "4K CINEMA",
                        color = Color(0xFF38BDF8),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = greetingString,
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(40.dp)
                .background(Color(0x33FFFFFF))
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = currentTimeString,
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = solarDateString,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = lunarDateString,
                    color = Color(0xFFFBBF24),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Wallpaper state (downsampled RGB_565 to save ~35MB GPU TextureCache on MediaTek TV)
    val wallpapers = remember { listOf(R.drawable.wallpaper_default, R.drawable.wallpaper_alt) }
    var currentWallpaperIndex by remember { mutableIntStateOf(0) }

    // Apps & Sports state
    var apps by remember { mutableStateOf<List<AppItem>>(emptyList()) }
    var matches by remember { mutableStateOf<List<LauncherMatchItem>>(emptyList()) }
    var isSyncingMatches by remember { mutableStateOf(false) }

    var focusedChannel by remember { mutableStateOf<TvChannelItem?>(DEFAULT_TV_CHANNELS.first()) }
    var focusedMatch by remember { mutableStateOf<LauncherMatchItem?>(null) }
    var focusedApp by remember { mutableStateOf<AppItem?>(null) }
    var focusedRowType by remember { mutableStateOf(FocusedRowType.CHANNEL) }

    var appToManage by remember { mutableStateOf<AppItem?>(null) }
    var showVisibilityManagerDialog by remember { mutableStateOf(false) }

    // Tiện ích nâng cao: Bộ nhớ RAM thực tế, Kênh vừa xem gần nhất, Hẹn giờ tắt TV, Bấm số chọn kênh
    var freeRamMb by remember { mutableIntStateOf(getAvailableRamMb(context)) }
    var lastWatchedId by remember { mutableStateOf(getLastWatchedId(context)) }
    var lastWatchedName by remember { mutableStateOf(getLastWatchedName(context)) }
    var sleepTimerMinutes by remember { mutableIntStateOf(0) }
    var isNightScreenOff by remember { mutableStateOf(false) }
    var numberInputBuffer by remember { mutableStateOf("") }
    var numberInputJob by remember { mutableStateOf<Job?>(null) }

    val channelsListState = rememberLazyListState()
    val matchesListState = rememberLazyListState()
    val appsListState = rememberLazyListState()

    fun triggerRamCleanup(showToast: Boolean) {
        scope.launch(Dispatchers.IO) {
            val (freed, afterMb) = cleanBackgroundRam(context)
            withContext(Dispatchers.Main) {
                freeRamMb = afterMb
                if (showToast) {
                    Toast.makeText(
                        context,
                        "🚀 Đã dọn RAM ngầm (+${freed} MB) • Trống khả dụng: $afterMb MB",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    fun cycleSleepTimer() {
        val next = when (sleepTimerMinutes) {
            0 -> 30
            30 -> 60
            60 -> 90
            90 -> 120
            else -> 0
        }
        sleepTimerMinutes = next
        val msg = if (next > 0) {
            "⏱️ Đã bật hẹn giờ nghỉ Tivi sau $next phút"
        } else {
            "⏱️ Đã tắt hẹn giờ nghỉ Tivi"
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    fun handleChannelNumberDigit(digit: Int) {
        val nextBuf = if (numberInputBuffer.length >= 2) "$digit" else "$numberInputBuffer$digit"
        numberInputBuffer = nextBuf
        numberInputJob?.cancel()
        numberInputJob = scope.launch {
            delay(1050L)
            val chNum = numberInputBuffer.toIntOrNull()
            numberInputBuffer = ""
            if (chNum != null && chNum in 1..DEFAULT_TV_CHANNELS.size) {
                val targetCh = DEFAULT_TV_CHANNELS[chNum - 1]
                lastWatchedId = targetCh.id
                lastWatchedName = targetCh.name
                launchTvChannel(context, targetCh)
            } else if (chNum != null) {
                Toast.makeText(
                    context,
                    "Chỉ hỗ trợ kênh số 1 đến ${DEFAULT_TV_CHANNELS.size}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    fun reloadAppsAsync() {
        scope.launch(Dispatchers.IO) {
            val loaded = AppRepository.getInstalledApps(context)
            withContext(Dispatchers.Main) {
                apps = loaded
                if (focusedApp == null || !loaded.any { it.packageName == focusedApp?.packageName }) {
                    focusedApp = loaded.firstOrNull()
                }
            }
        }
    }

    fun reloadSportsAsync(showToast: Boolean = false) {
        scope.launch {
            if (matches.isEmpty()) {
                val cached = withContext(Dispatchers.IO) { SportsRepository.getCachedMatches(context) }
                if (cached.isNotEmpty()) {
                    matches = cached
                    if (focusedMatch == null) focusedMatch = cached.firstOrNull()
                }
            }
            isSyncingMatches = true
            val fresh = SportsRepository.fetchLatestMatches(context)
            isSyncingMatches = false
            if (fresh.isNotEmpty()) {
                matches = fresh
                if (focusedMatch == null) focusedMatch = fresh.firstOrNull()
                if (showToast) {
                    Toast.makeText(context, "⚽ Đã cập nhật ${fresh.size} trận bóng đá mới nhất", Toast.LENGTH_SHORT).show()
                }
            } else if (showToast) {
                Toast.makeText(context, "⚽ Đang dùng lịch bóng đá đã lưu (${matches.size} trận)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Định kỳ mỗi 30 giây tự động lọc bỏ ngay các trận bóng đá đã hết giờ LIVE (> 115 phút)
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            if (matches.isNotEmpty()) {
                val filtered = SportsRepository.filterAndSortActiveMatches(matches)
                if (filtered.size != matches.size || filtered.map { it.id } != matches.map { it.id }) {
                    matches = filtered
                }
            }
        }
    }

    // Initial load & periodic auto-refresh of football schedule
    LaunchedEffect(Unit) {
        reloadAppsAsync()
        reloadSportsAsync(showToast = false)
        while (true) {
            delay(180_000L) // Tự động cập nhật lịch bóng đá mỗi 3 phút
            reloadSportsAsync(showToast = false)
        }
    }

    // Lifecycle observer: tự động dọn RAM ngầm + làm mới App & Lịch Bóng Đá ngay khi quay về Launcher (ON_RESUME)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                lastWatchedId = getLastWatchedId(context)
                lastWatchedName = getLastWatchedName(context)
                triggerRamCleanup(showToast = false)
                reloadAppsAsync()
                reloadSportsAsync(showToast = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // BroadcastReceiver cho Package thay đổi + Khi màn hình Tivi bật sáng (ACTION_SCREEN_ON) + Khi có mạng sau khi khởi động
    DisposableEffect(context) {
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED)
            addDataScheme("package")
        }
        val pkgReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                reloadAppsAsync()
            }
        }

        val powerFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val powerReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                reloadSportsAsync(showToast = false)
                reloadAppsAsync()
            }
        }

        context.registerReceiver(pkgReceiver, pkgFilter)
        context.registerReceiver(powerReceiver, powerFilter)

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val netCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reloadSportsAsync(showToast = false)
            }
        }
        try {
            cm?.registerDefaultNetworkCallback(netCallback)
        } catch (_: Exception) {}

        onDispose {
            try { context.unregisterReceiver(pkgReceiver) } catch (_: Exception) {}
            try { context.unregisterReceiver(powerReceiver) } catch (_: Exception) {}
            try { cm?.unregisterNetworkCallback(netCallback) } catch (_: Exception) {}
        }
    }

    val initialFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(180)
        try {
            initialFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    val wallpaperRequest = remember(currentWallpaperIndex, context) {
        ImageRequest.Builder(context)
            .data(wallpapers[currentWallpaperIndex])
            .size(Size(960, 540))
            .bitmapConfig(Bitmap.Config.RGB_565)
            .allowHardware(false)
            .crossfade(false)
            .build()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (isNightScreenOff) {
                    if (event.type == KeyEventType.KeyDown) {
                        isNightScreenOff = false
                    }
                    return@onPreviewKeyEvent true
                }
                if (appToManage != null || showVisibilityManagerDialog) {
                    return@onPreviewKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                    val code = event.nativeKeyEvent.keyCode
                    val digit = extractDigitFromKeyCode(code)
                    if (digit != null) {
                        handleChannelNumberDigit(digit)
                        return@onPreviewKeyEvent true
                    }
                    when (code) {
                        KeyEvent.KEYCODE_PROG_RED -> {
                            triggerRamCleanup(showToast = true)
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_PROG_GREEN -> {
                            showVisibilityManagerDialog = true
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_PROG_YELLOW -> {
                            reloadSportsAsync(showToast = true)
                            return@onPreviewKeyEvent true
                        }
                        KeyEvent.KEYCODE_PROG_BLUE -> {
                            resumeLastWatched(context)
                            return@onPreviewKeyEvent true
                        }
                    }
                }
                false
            }
    ) {
        // === FULLSCREEN WALLPAPER (RGB_565 Optimized) ===
        AsyncImage(
            model = wallpaperRequest,
            contentDescription = "Wallpaper",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Gradient overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xB3070A12),
                            Color(0x55070A12),
                            Color(0xE6070A12)
                        )
                    )
                )
        )

        // === TOP-LEFT: BRANDING + CLOCK & DUAL CALENDAR (ISOLATED RECOMPOSITION) ===
        ClockAndCalendarHeader(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 44.dp, top = 16.dp)
        )

        // === TOP-RIGHT: MINIMALIST 4-ICON CAPSULE (ZERO TEXT CLUTTER, NO OVERLAP) ===
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 44.dp, top = 22.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0x2EFFFFFF))
                .border(1.dp, Color(0x3AFFFFFF), RoundedCornerShape(22.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TopBarIconButton(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Visibility,
                        contentDescription = "Quản lý Ẩn/Hiện App",
                        tint = Color(0xFF34D399),
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    showVisibilityManagerDialog = true
                }
            )

            TopBarIconButton(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Cập nhật Lịch Bóng Đá & Dọn RAM",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    triggerRamCleanup(showToast = true)
                    reloadAppsAsync()
                    reloadSportsAsync(showToast = true)
                }
            )

            TopBarIconButton(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Đổi hình nền",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    currentWallpaperIndex = (currentWallpaperIndex + 1) % wallpapers.size
                }
            )

            TopBarIconButton(
                icon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Cài đặt TV",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
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

        // === BOTTOM: 3 DEDICATED DISTINCT BOXES (Ô 1: TRUYỀN HÌNH, Ô 2: LỊCH BÓNG ĐÁ, Ô 3: ỨNG DỤNG) ===
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp, start = 44.dp, end = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ==========================================
            // Ô 1: ĐÀI TRUYỀN HÌNH (DEDICATED TV CHANNELS BOX)
            // ==========================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x240F172A))
                    .border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(18.dp))
                    .padding(vertical = 6.dp, horizontal = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF0284C7).copy(alpha = 0.78f))
                            .border(1.dp, Color(0xFF38BDF8).copy(alpha = 0.85f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Tv,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "TRUYỀN",
                                color = Color.White,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "HÌNH",
                                color = Color(0xFFBAE6FD),
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    LazyRow(
                        state = channelsListState,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(DEFAULT_TV_CHANNELS, key = { _, item -> item.id }) { index, ch ->
                            TvChannelCard(
                                channel = ch,
                                channelNumber = index + 1,
                                isLastWatched = ch.id == lastWatchedId,
                                focusRequester = if (index == 0) initialFocusRequester else null,
                                onFocused = {
                                    focusedChannel = ch
                                    focusedRowType = FocusedRowType.CHANNEL
                                },
                                onClick = {
                                    lastWatchedId = ch.id
                                    lastWatchedName = ch.name
                                    launchTvChannel(context, ch)
                                },
                                onLongClick = {
                                    showVisibilityManagerDialog = true
                                }
                            )
                        }
                    }
                }
            }

            // ==========================================
            // Ô 2: LỊCH BÓNG ĐÁ TRỰC TIẾP (AUTO-UPDATED ON TV WAKE/BOOT)
            // ==========================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x240F172A))
                    .border(1.dp, Color(0x40F59E0B), RoundedCornerShape(18.dp))
                    .padding(vertical = 6.dp, horizontal = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(74.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFB45309).copy(alpha = 0.82f))
                            .border(1.dp, Color(0xFFFBBF24).copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                            .clickable {
                                reloadSportsAsync(showToast = true)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.SportsSoccer,
                                contentDescription = "Lịch Bóng Đá",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "LỊCH",
                                color = Color.White,
                                fontSize = 8.5.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "BÓNG ĐÁ",
                                color = Color(0xFFFDE68A),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    if (matches.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(74.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x1FFFFFFF))
                                .clickable { reloadSportsAsync(showToast = true) }
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (isSyncingMatches) {
                                    "⚽ Đang tự động cập nhật lịch thi đấu bóng đá hôm nay..."
                                } else {
                                    "⚽ Chưa có lịch bóng đá hoặc đang chờ kết nối mạng — Bấm [OK] để tải lại ngay"
                                },
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "TẢI LẠI",
                                color = Color(0xFFFBBF24),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    } else {
                        LazyRow(
                            state = matchesListState,
                            contentPadding = PaddingValues(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            itemsIndexed(matches, key = { idx, item -> "${item.id}_$idx" }) { _, match ->
                                FootballMatchCardItem(
                                    match = match,
                                    onFocused = {
                                        focusedMatch = match
                                        focusedRowType = FocusedRowType.FOOTBALL
                                    },
                                    onClick = {
                                        launchFootballMatch(context, match)
                                    },
                                    onLongClick = {
                                        showVisibilityManagerDialog = true
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // Ô 3: ỨNG DỤNG (APPS BOX + TÙY CHỌN ẨN / HIỆN APP THEO MONG MUỐN)
            // ==========================================
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0x240F172A))
                    .border(1.dp, Color(0x38FFFFFF), RoundedCornerShape(18.dp))
                    .padding(vertical = 6.dp, horizontal = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var isAppsHeaderFocused by remember { mutableStateOf(false) }
                    Box(
                        modifier = Modifier
                            .width(64.dp)
                            .height(72.dp)
                            .graphicsLayer {
                                val s = if (isAppsHeaderFocused) 1.07f else 1.0f
                                scaleX = s
                                scaleY = s
                            }
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isAppsHeaderFocused) Color(0xFF10B981) else Color(0xFF059669).copy(alpha = 0.8f))
                            .border(
                                width = if (isAppsHeaderFocused) 2.5.dp else 1.dp,
                                color = if (isAppsHeaderFocused) Color(0xFFFDE047) else Color(0xFF34D399).copy(alpha = 0.85f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .onFocusChanged { isAppsHeaderFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (isTvOkKey(event.nativeKeyEvent.keyCode)) {
                                    if (event.type == KeyEventType.KeyUp) {
                                        showVisibilityManagerDialog = true
                                    }
                                    true
                                } else false
                            }
                            .focusable(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = "Quản lý Ứng dụng",
                                tint = if (isAppsHeaderFocused) Color(0xFFFDE047) else Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "ỨNG DỤNG",
                                color = Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Black
                            )
                            Text(
                                text = "ẨN / HIỆN",
                                color = if (isAppsHeaderFocused) Color(0xFFFDE047) else Color(0xFFA7F3D0),
                                fontSize = 7.5.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    LazyRow(
                        state = appsListState,
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(apps, key = { _, item -> item.id }) { _, app ->
                            AppCardItem(
                                app = app,
                                focusRequester = null,
                                onFocused = {
                                    focusedApp = app
                                    focusedRowType = FocusedRowType.APP
                                },
                                onClick = {
                                    app.launch(context)
                                },
                                onLongClick = {
                                    appToManage = app
                                }
                            )
                        }

                        // Thẻ chuyên dụng "Ẩn / Hiện App" ngay trong Ô Ứng Dụng
                        item(key = "manage_app_visibility_card") {
                            ManageAppsVisibilityCard(
                                onFocused = {
                                    focusedRowType = FocusedRowType.APP
                                },
                                onClick = {
                                    showVisibilityManagerDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }

        // === HIỂN THỊ OSD KHI BẤM PHÍM SỐ 1-9 TRÊN REMOTE SHARP ===
        if (numberInputBuffer.isNotEmpty()) {
            val previewNum = numberInputBuffer.toIntOrNull() ?: 0
            val previewCh = DEFAULT_TV_CHANNELS.getOrNull(previewNum - 1)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 22.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xEE0F172A))
                    .border(2.dp, Color(0xFF38BDF8), RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "🔢 KÊNH SỐ: $numberInputBuffer",
                        color = Color(0xFFFDE047),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = previewCh?.let { "Đang mở: ${it.name}" }
                            ?: "Chọn từ kênh 1 đến ${DEFAULT_TV_CHANNELS.size}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // === DIALOG 1: SINGLE APP ACTION DIALOG (UNINSTALL / HIDE / MANAGE ALL / DETAILS) ===
        appToManage?.let { targetApp ->
            AppActionDialog(
                app = targetApp,
                onDismiss = { appToManage = null },
                onUninstall = {
                    appToManage = null
                    targetApp.uninstall(context)
                },
                onHide = {
                    AppRepository.hideApp(context, targetApp.packageName)
                    reloadAppsAsync()
                    Toast.makeText(context, "Đã ẩn ${targetApp.label} khỏi ô Ứng Dụng", Toast.LENGTH_SHORT).show()
                    appToManage = null
                },
                onOpenVisibilityManager = {
                    appToManage = null
                    showVisibilityManagerDialog = true
                },
                onOpenDetails = {
                    appToManage = null
                    targetApp.openDetails(context)
                }
            )
        }

        // === DIALOG 2: FULL APP VISIBILITY MANAGER (HIỆN / ẨN CÁC APP THEO MONG MUỐN) ===
        if (showVisibilityManagerDialog) {
            AppVisibilityManagerDialog(
                onDismiss = {
                    showVisibilityManagerDialog = false
                    reloadAppsAsync()
                },
                onChanged = {
                    reloadAppsAsync()
                }
            )
        }

        // === CHẾ ĐỘ NGHỈ ĐÊM TẮT MÀN HÌNH KHI HẾT HẸN GIỜ ===
        if (isNightScreenOff) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { isNightScreenOff = false },
                contentAlignment = Alignment.BottomEnd
            ) {
                Text(
                    text = "🌙 Đang ở chế độ nghỉ hẹn giờ • Bấm phím bất kỳ để bật sáng",
                    color = Color.White.copy(alpha = 0.22f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
    }
}

private fun launchTvChannel(context: Context, channel: TvChannelItem) {
    try {
        saveLastWatchedItem(
            context = context,
            id = channel.id,
            name = channel.name,
            streamUrl = channel.streamUrl,
            logoUrl = channel.logoUrl,
            backupUrls = channel.backupUrls
        )
        val intent = context.packageManager.getLaunchIntentForPackage("com.duytv.app")
            ?: context.packageManager.getLeanbackLaunchIntentForPackage("com.duytv.app")
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra("channel_id", channel.id)
            intent.putExtra("channel_name", channel.name)
            intent.putExtra("stream_url", channel.streamUrl)
            intent.putExtra("logo_url", channel.logoUrl)
            intent.putStringArrayListExtra("backup_urls", ArrayList(channel.backupUrls))
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "Chưa cài đặt DuyTV để phát ${channel.name}", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun launchFootballMatch(context: Context, match: LauncherMatchItem) {
    try {
        val displayTitle = "⚽ ${match.homeTeam} vs ${match.awayTeam}"
        saveLastWatchedItem(
            context = context,
            id = match.id,
            name = displayTitle,
            streamUrl = match.streamUrl,
            logoUrl = match.logoUrl ?: "",
            backupUrls = match.backupUrls
        )
        val intent = context.packageManager.getLaunchIntentForPackage("com.duytv.app")
            ?: context.packageManager.getLeanbackLaunchIntentForPackage("com.duytv.app")
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra("channel_id", match.id)
            intent.putExtra("channel_name", displayTitle)
            intent.putExtra("stream_url", match.streamUrl)
            intent.putExtra("logo_url", match.logoUrl ?: "")
            intent.putStringArrayListExtra("backup_urls", ArrayList(match.backupUrls))
            context.startActivity(intent)
        } else {
            Toast.makeText(context, "Chưa cài đặt DuyTV để phát trận đấu", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

@Composable
fun TvChannelCard(
    channel: TvChannelItem,
    channelNumber: Int = 1,
    isLastWatched: Boolean = false,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scope = rememberCoroutineScope()
    var okDownTime by remember { mutableStateOf(0L) }
    var longPressTriggered by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var logoFailed by remember(channel.logoUrl) { mutableStateOf(false) }
    val totalSources = remember(channel) { 1 + channel.backupUrls.size }
    val callSign = remember(channel.name) {
        channel.name.substringBefore(" - ")
            .replace(" HD", "", ignoreCase = true)
            .replace(" SD", "", ignoreCase = true)
            .trim()
            .take(10)
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1.0f,
        animationSpec = tween(durationMillis = 140),
        label = "channelScale"
    )

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocused()
        } else {
            holdJob?.cancel()
            okDownTime = 0L
            longPressTriggered = false
        }
    }

    val bgBrush = if (isFocused) {
        Brush.verticalGradient(colors = listOf(Color(0xFF475569), Color(0xFF1E293B)))
    } else {
        Brush.verticalGradient(colors = listOf(Color(0xFF161A24), Color(0xFF10131B)))
    }

    val cardShape = RoundedCornerShape(13.dp)
    var modifier = Modifier
        .width(138.dp)
        .height(72.dp)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }

    if (focusRequester != null) {
        modifier = modifier.focusRequester(focusRequester)
    }

    Box(
        modifier = modifier
            .clip(cardShape)
            .background(brush = bgBrush)
            .border(
                width = if (isFocused) 2.2.dp else if (isLastWatched) 1.5.dp else 1.dp,
                color = if (isFocused) Color.White else if (isLastWatched) Color(0xFFFBBF24) else Color(0x28FFFFFF),
                shape = cardShape
            )
            .onPreviewKeyEvent { keyEvent ->
                val code = keyEvent.nativeKeyEvent.keyCode
                when {
                    keyEvent.type == KeyEventType.KeyDown && isTvMenuKey(code) -> {
                        holdJob?.cancel()
                        longPressTriggered = true
                        onLongClick()
                        true
                    }
                    keyEvent.type == KeyEventType.KeyDown && isTvOkKey(code) -> {
                        if (keyEvent.nativeKeyEvent.repeatCount == 0 && !keyEvent.nativeKeyEvent.isLongPress) {
                            okDownTime = System.currentTimeMillis()
                            longPressTriggered = false
                            holdJob?.cancel()
                            holdJob = scope.launch {
                                delay(400L)
                                if (!longPressTriggered && okDownTime > 0L) {
                                    longPressTriggered = true
                                    onLongClick()
                                }
                            }
                        } else if (!longPressTriggered) {
                            holdJob?.cancel()
                            longPressTriggered = true
                            onLongClick()
                        }
                        true
                    }
                    keyEvent.type == KeyEventType.KeyUp && isTvOkKey(code) -> {
                        holdJob?.cancel()
                        val wasDown = okDownTime > 0L
                        val heldMs = if (wasDown) System.currentTimeMillis() - okDownTime else 9999L
                        okDownTime = 0L
                        if (wasDown && !longPressTriggered && heldMs < 400L) {
                            onClick()
                        }
                        longPressTriggered = false
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        // Nhãn số thứ tự kênh (1-9) hoặc ★ VỪA XEM ở góc trên bên trái
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(
                    if (isLastWatched) Color(0xFFD97706) else Color(0x990F172A),
                    RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 4.dp, vertical = 1.dp)
        ) {
            Text(
                text = if (isLastWatched) "★ VỪA XEM" else "#$channelNumber",
                color = if (isLastWatched) Color.White else Color(0xFF93C5FD),
                fontSize = 7.sp,
                fontWeight = FontWeight.Black
            )
        }

        if (totalSources > 1) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = "$totalSources link",
                    color = Color(0xFF00E676),
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(1.dp))

            if (channel.logoUrl.isNotBlank() && !logoFailed) {
                AsyncImage(
                    model = channel.logoUrl,
                    contentDescription = channel.name,
                    onError = { logoFailed = true },
                    modifier = Modifier
                        .height(36.dp)
                        .fillMaxWidth(0.86f),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .fillMaxWidth(0.9f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF1E293B))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = callSign,
                        color = Color.White,
                        fontSize = if (callSign.length <= 6) 14.sp else 11.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = channel.name,
                    color = Color.White,
                    fontSize = 9.5.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = "● LIVE",
                    color = if (isFocused) Color(0xFF00E676) else Color(0xFFFF5252),
                    fontSize = 7.5.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
fun FootballMatchCardItem(
    match: LauncherMatchItem,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scope = rememberCoroutineScope()
    var okDownTime by remember { mutableStateOf(0L) }
    var longPressTriggered by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }
    val status = match.getStatus()
    val totalServers = remember(match) { 1 + match.backupUrls.size }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.05f else 1.0f,
        animationSpec = tween(durationMillis = 140),
        label = "matchScale"
    )

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocused()
        } else {
            holdJob?.cancel()
            okDownTime = 0L
            longPressTriggered = false
        }
    }

    val bgBrush = when {
        isFocused -> Brush.verticalGradient(colors = listOf(Color(0xFF1E3A8A), Color(0xFF0F172A)))
        status == MatchLiveStatus.LIVE -> Brush.verticalGradient(colors = listOf(Color(0xFF2A1215), Color(0xFF141824)))
        else -> Brush.verticalGradient(colors = listOf(Color(0xFF172033), Color(0xFF0F1420)))
    }

    val borderColor = when {
        isFocused -> Color(0xFFFBBF24)
        status == MatchLiveStatus.LIVE -> Color(0x99EF4444)
        else -> Color(0x3338BDF8)
    }

    val cardShape = RoundedCornerShape(13.dp)

    Box(
        modifier = Modifier
            .width(196.dp)
            .height(74.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(cardShape)
            .background(brush = bgBrush)
            .border(
                width = if (isFocused) 2.2.dp else 1.dp,
                color = borderColor,
                shape = cardShape
            )
            .onPreviewKeyEvent { keyEvent ->
                val code = keyEvent.nativeKeyEvent.keyCode
                when {
                    keyEvent.type == KeyEventType.KeyDown && isTvMenuKey(code) -> {
                        holdJob?.cancel()
                        longPressTriggered = true
                        onLongClick()
                        true
                    }
                    keyEvent.type == KeyEventType.KeyDown && isTvOkKey(code) -> {
                        if (keyEvent.nativeKeyEvent.repeatCount == 0 && !keyEvent.nativeKeyEvent.isLongPress) {
                            okDownTime = System.currentTimeMillis()
                            longPressTriggered = false
                            holdJob?.cancel()
                            holdJob = scope.launch {
                                delay(400L)
                                if (!longPressTriggered && okDownTime > 0L) {
                                    longPressTriggered = true
                                    onLongClick()
                                }
                            }
                        } else if (!longPressTriggered) {
                            holdJob?.cancel()
                            longPressTriggered = true
                            onLongClick()
                        }
                        true
                    }
                    keyEvent.type == KeyEventType.KeyUp && isTvOkKey(code) -> {
                        holdJob?.cancel()
                        val wasDown = okDownTime > 0L
                        val heldMs = if (wasDown) System.currentTimeMillis() - okDownTime else 9999L
                        okDownTime = 0L
                        if (wasDown && !longPressTriggered && heldMs < 400L) {
                            onClick()
                        }
                        longPressTriggered = false
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interactionSource)
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Row: Live/Time Badge + Server Count / BLV
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when (status) {
                                MatchLiveStatus.LIVE -> Color(0xFFEF4444)
                                MatchLiveStatus.UPCOMING -> Color(0xFF0284C7)
                                MatchLiveStatus.FINISHED -> Color(0xFF475569)
                            }
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = when (status) {
                            MatchLiveStatus.LIVE -> "● LIVE ${match.kickoffTime}"
                            MatchLiveStatus.UPCOMING -> "🕒 ${match.kickoffTime}"
                            MatchLiveStatus.FINISHED -> "KT ${match.kickoffTime}"
                        },
                        color = Color.White,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Text(
                    text = if (totalServers > 1) "${match.commentator} • $totalServers link" else match.commentator,
                    color = Color(0xFFFBBF24),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Middle: Home Team vs Away Team
            Text(
                text = match.homeTeam,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "vs ${match.awayTeam}",
                    color = Color(0xFFBAE6FD),
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "XEM ►",
                    color = if (isFocused) Color(0xFF00E676) else Color.White.copy(alpha = 0.55f),
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
fun TopBarPillButton(
    text: String,
    textColor: Color,
    borderColor: Color,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.08f else 1.0f, label = "pillScale")

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(14.dp))
            .background(if (isFocused) Color.White.copy(alpha = 0.28f) else Color(0x330F172A))
            .border(
                width = if (isFocused) 1.8.dp else 1.dp,
                color = if (isFocused) Color(0xFFFDE047) else borderColor,
                shape = RoundedCornerShape(14.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (isTvOkKey(event.nativeKeyEvent.keyCode)) {
                    if (event.type == KeyEventType.KeyUp) {
                        onClick()
                    }
                    true
                } else false
            }
            .focusable()
            .padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (isFocused) Color.White else textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
        )
    }
}

@Composable
fun TopBarIconButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.18f else 1.0f, label = "iconScale")

    Box(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(if (isFocused) Color.White.copy(alpha = 0.35f) else Color.Transparent)
            .onFocusChanged { isFocused = it.isFocused }
            .onPreviewKeyEvent { event ->
                if (isTvOkKey(event.nativeKeyEvent.keyCode)) {
                    if (event.type == KeyEventType.KeyUp) {
                        onClick()
                    }
                    true
                } else false
            }
            .focusable()
            .padding(5.dp),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCardItem(
    app: AppItem,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scope = rememberCoroutineScope()
    var okDownTime by remember { mutableStateOf(0L) }
    var longPressTriggered by remember { mutableStateOf(false) }
    var holdJob by remember { mutableStateOf<Job?>(null) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1.0f,
        animationSpec = tween(durationMillis = 140),
        label = "cardScale"
    )

    LaunchedEffect(isFocused) {
        if (isFocused) {
            onFocused()
        } else {
            holdJob?.cancel()
            okDownTime = 0L
            longPressTriggered = false
        }
    }

    val cardShape = RoundedCornerShape(13.dp)
    var modifier = Modifier
        .width(138.dp)
        .height(72.dp)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }

    if (focusRequester != null) {
        modifier = modifier.focusRequester(focusRequester)
    }

    Box(
        modifier = modifier
            .clip(cardShape)
            .background(
                if (app.packageName == "com.google.android.youtube.tv") {
                    if (isFocused) Color.White else Color.White.copy(alpha = 0.9f)
                } else if (isFocused) {
                    Color(app.cardColor).copy(alpha = 0.95f)
                } else {
                    Color(app.cardColor).copy(alpha = 0.8f)
                }
            )
            .border(
                width = if (isFocused) 2.2.dp else 1.dp,
                color = if (isFocused) Color.White else Color(0x33FFFFFF),
                shape = cardShape
            )
            .onPreviewKeyEvent { keyEvent ->
                val code = keyEvent.nativeKeyEvent.keyCode
                when {
                    keyEvent.type == KeyEventType.KeyDown && isTvMenuKey(code) -> {
                        holdJob?.cancel()
                        longPressTriggered = true
                        onLongClick()
                        true
                    }
                    keyEvent.type == KeyEventType.KeyDown && isTvOkKey(code) -> {
                        if (keyEvent.nativeKeyEvent.repeatCount == 0 && !keyEvent.nativeKeyEvent.isLongPress) {
                            okDownTime = System.currentTimeMillis()
                            longPressTriggered = false
                            holdJob?.cancel()
                            holdJob = scope.launch {
                                delay(360L)
                                if (!longPressTriggered && okDownTime > 0L) {
                                    longPressTriggered = true
                                    onLongClick()
                                }
                            }
                        } else if (!longPressTriggered) {
                            holdJob?.cancel()
                            longPressTriggered = true
                            onLongClick()
                        }
                        true
                    }
                    keyEvent.type == KeyEventType.KeyUp && isTvOkKey(code) -> {
                        holdJob?.cancel()
                        val wasDown = okDownTime > 0L
                        val heldMs = if (wasDown) System.currentTimeMillis() - okDownTime else 9999L
                        okDownTime = 0L
                        if (wasDown && !longPressTriggered && heldMs < 360L) {
                            onClick()
                        }
                        longPressTriggered = false
                        true
                    }
                    else -> false
                }
            }
            .focusable(interactionSource = interactionSource),
        contentAlignment = Alignment.Center
    ) {
        when (app.packageName) {
            "com.duytv.app" -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "DUY",
                            color = Color(0xFFE50914),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "TV",
                            color = Color(0xFF38BDF8),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF38BDF8).copy(alpha = 0.2f))
                            .border(0.8.dp, Color(0xFF38BDF8), RoundedCornerShape(4.dp))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "4K CINEMA",
                            color = Color(0xFF38BDF8),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            "com.google.android.youtube.tv" -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(horizontal = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 30.dp, height = 22.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(Color(0xFFFF0000)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "YouTube",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "YouTube",
                        color = Color(0xFF1E293B),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            "com.quang.monstertv" -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Viet", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text(text = "Mi", color = Color(0xFF10B981), fontSize = 18.sp, fontWeight = FontWeight.Black)
                        Text(text = "TV", color = Color(0xFFEF4444), fontSize = 18.sp, fontWeight = FontWeight.Black)
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "TRUYỀN HÌNH",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            "com.sports.tv" -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFEF4444))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "LIVE SPORT",
                            color = Color.White,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "STREAM",
                        color = Color(0xFFFBBF24),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            else -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(5.dp)
                ) {
                    if (app.iconBitmap != null) {
                        Image(
                            bitmap = app.iconBitmap,
                            contentDescription = app.label,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Tv,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(3.dp))

                    Text(
                        text = app.label,
                        color = Color.White,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun ManageAppsVisibilityCard(
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1.0f,
        animationSpec = tween(durationMillis = 140),
        label = "manageScale"
    )

    LaunchedEffect(isFocused) {
        if (isFocused) onFocused()
    }

    val cardShape = RoundedCornerShape(13.dp)
    Box(
        modifier = Modifier
            .width(138.dp)
            .height(72.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(cardShape)
            .background(
                if (isFocused) Color(0xFF065F46) else Color(0xFF0F172A).copy(alpha = 0.85f)
            )
            .border(
                width = if (isFocused) 2.2.dp else 1.dp,
                color = if (isFocused) Color(0xFF34D399) else Color(0x5534D399),
                shape = cardShape
            )
            .onPreviewKeyEvent { keyEvent ->
                val code = keyEvent.nativeKeyEvent.keyCode
                if (keyEvent.type == KeyEventType.KeyDown && (isTvOkKey(code) || isTvMenuKey(code))) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .focusable(interactionSource = interactionSource)
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = "Ẩn / Hiện App",
                tint = Color(0xFF34D399),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Ẩn / Hiện App",
                color = Color.White,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
fun AppActionDialog(
    app: AppItem,
    onDismiss: () -> Unit,
    onUninstall: () -> Unit,
    onHide: () -> Unit,
    onOpenVisibilityManager: () -> Unit,
    onOpenDetails: () -> Unit
) {
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(80)
        try { firstFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF0F172A).copy(alpha = 0.97f),
            border = BorderStroke(1.5.dp, Color(0x6638BDF8)),
            shadowElevation = 20.dp,
            modifier = Modifier.width(450.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (app.iconBitmap != null) {
                    Image(
                        bitmap = app.iconBitmap,
                        contentDescription = app.label,
                        modifier = Modifier.size(48.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Tv,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = app.label,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = app.packageName,
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.5.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                DialogActionButton(
                    icon = Icons.Default.VisibilityOff,
                    text = "Ẩn Ứng Dụng Này",
                    subtext = "Ẩn ${app.label} khỏi ô Ứng Dụng (có thể bật hiện lại bất kỳ lúc nào)",
                    accentColor = Color(0xFFF59E0B),
                    focusRequester = firstFocusRequester,
                    onClick = onHide
                )

                Spacer(modifier = Modifier.height(8.dp))

                DialogActionButton(
                    icon = Icons.Default.Visibility,
                    text = "Quản Lý Ẩn / Hiện Tất Cả App",
                    subtext = "Tùy chọn bật hiện hoặc ẩn bất kỳ ứng dụng nào trên Tivi",
                    accentColor = Color(0xFF10B981),
                    onClick = onOpenVisibilityManager
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (!app.isSystemApp && app.packageName != "com.duytv.launcher") {
                    DialogActionButton(
                        icon = Icons.Default.Delete,
                        text = "Gỡ Cài Đặt Khỏi TV",
                        subtext = "Xóa hoàn toàn ứng dụng này khỏi bộ nhớ TV",
                        accentColor = Color(0xFFEF4444),
                        onClick = onUninstall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                DialogActionButton(
                    icon = Icons.Default.Info,
                    text = "Thông Tin & Bộ Nhớ",
                    subtext = "Mở cài đặt chi tiết, xóa cache hoặc buộc dừng",
                    accentColor = Color(0xFF38BDF8),
                    onClick = onOpenDetails
                )

                Spacer(modifier = Modifier.height(8.dp))

                DialogActionButton(
                    icon = Icons.Default.Close,
                    text = "Đóng (Quay Lại)",
                    subtext = null,
                    accentColor = Color.White.copy(alpha = 0.6f),
                    onClick = onDismiss
                )
            }
        }
    }
}

/**
 * Bảng Quản Lý Ẩn / Hiện Ứng Dụng Theo Mong Muốn:
 * Cho phép Anh Duy xem toàn bộ app trong Tivi và bấm [OK] để bật HIỆN hoặc ẨN từng app tùy ý.
 */
@Composable
fun AppVisibilityManagerDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var appEntries by remember { mutableStateOf<List<Pair<AppItem, Boolean>>>(emptyList()) }
    val firstRowFocusRequester = remember { FocusRequester() }
    var initialFocusDone by remember { mutableStateOf(false) }

    fun refreshList() {
        scope.launch(Dispatchers.IO) {
            val all = AppRepository.getAllAppsForManagement(context)
            withContext(Dispatchers.Main) {
                appEntries = all
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshList()
    }

    LaunchedEffect(appEntries.isNotEmpty()) {
        if (appEntries.isNotEmpty() && !initialFocusDone) {
            initialFocusDone = true
            delay(80)
            try { firstRowFocusRequester.requestFocus() } catch (_: Exception) {}
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFF0F172A).copy(alpha = 0.98f),
            border = BorderStroke(1.5.dp, Color(0xFF34D399)),
            shadowElevation = 24.dp,
            modifier = Modifier
                .width(560.dp)
                .heightIn(max = 470.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TÙY CHỌN ẨN / HIỆN ỨNG DỤNG",
                            color = Color(0xFF34D399),
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(
                            text = "Bấm [OK] vào ứng dụng bất kỳ để chuyển đổi giữa HIỆN và ẨN",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }

                    val visibleCount = appEntries.count { it.second }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF065F46))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "Đang hiện: $visibleCount/${appEntries.size}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(appEntries, key = { _, pair -> pair.first.packageName }) { index, (appItem, isVisible) ->
                        var rowFocused by remember { mutableStateOf(false) }
                        var sawFreshDown by remember { mutableStateOf(false) }
                        val rowAction = {
                            AppRepository.toggleAppVisibility(context, appItem.packageName)
                            refreshList()
                            onChanged()
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = when {
                                rowFocused -> Color(0xFF1E293B)
                                isVisible -> Color(0x14FFFFFF)
                                else -> Color(0x0AFFFFFF)
                            },
                            border = BorderStroke(
                                width = if (rowFocused) 2.dp else 1.dp,
                                color = when {
                                    rowFocused -> Color(0xFF38BDF8)
                                    isVisible -> Color(0x4434D399)
                                    else -> Color(0x22FFFFFF)
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(if (index == 0) Modifier.focusRequester(firstRowFocusRequester) else Modifier)
                                .onPreviewKeyEvent { keyEvent ->
                                    val code = keyEvent.nativeKeyEvent.keyCode
                                    when {
                                        keyEvent.type == KeyEventType.KeyDown && isTvOkKey(code) -> {
                                            if (keyEvent.nativeKeyEvent.repeatCount == 0 && !keyEvent.nativeKeyEvent.isLongPress) {
                                                sawFreshDown = true
                                            }
                                            true
                                        }
                                        keyEvent.type == KeyEventType.KeyUp && isTvOkKey(code) -> {
                                            if (sawFreshDown) {
                                                sawFreshDown = false
                                                rowAction()
                                            }
                                            true
                                        }
                                        else -> false
                                    }
                                }
                                .onFocusChanged {
                                    rowFocused = it.isFocused
                                    if (!it.isFocused) sawFreshDown = false
                                }
                                .focusable()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (appItem.iconBitmap != null) {
                                        Image(
                                            bitmap = appItem.iconBitmap,
                                            contentDescription = appItem.label,
                                            modifier = Modifier.size(30.dp)
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Tv,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(
                                            text = appItem.label,
                                            color = if (isVisible) Color.White else Color.White.copy(alpha = 0.5f),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = appItem.packageName,
                                            color = Color.White.copy(alpha = 0.45f),
                                            fontSize = 9.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isVisible) Color(0xFF059669) else Color(0xFF78350F)
                                        )
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = if (isVisible) "✓ ĐANG HIỆN" else "✕ ĐANG ẨN",
                                        color = Color.White,
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Black
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        DialogActionButton(
                            icon = Icons.Default.Refresh,
                            text = "Mặc Định Gọn Đẹp",
                            subtext = null,
                            accentColor = Color(0xFF38BDF8),
                            onClick = {
                                AppRepository.resetHiddenApps(context)
                                refreshList()
                                onChanged()
                                Toast.makeText(context, "Đã đặt lại danh sách gọn đẹp mặc định", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        DialogActionButton(
                            icon = Icons.Default.Visibility,
                            text = "Hiện Tất Cả App",
                            subtext = null,
                            accentColor = Color(0xFF34D399),
                            onClick = {
                                AppRepository.showAllAppsIncludingSystem(context)
                                refreshList()
                                onChanged()
                                Toast.makeText(context, "Đã bật hiển thị tất cả ứng dụng", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                    Box(modifier = Modifier.weight(0.8f)) {
                        DialogActionButton(
                            icon = Icons.Default.Close,
                            text = "Đóng",
                            subtext = null,
                            accentColor = Color(0xFFF59E0B),
                            onClick = onDismiss
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DialogActionButton(
    icon: ImageVector,
    text: String,
    subtext: String?,
    accentColor: Color,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var sawFreshDown by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(targetValue = if (isFocused) 1.03f else 1.0f, label = "buttonScale")

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (isFocused) accentColor.copy(alpha = 0.28f) else Color(0x1AFFFFFF),
        border = BorderStroke(
            width = if (isFocused) 2.dp else 1.dp,
            color = if (isFocused) accentColor else Color(0x22FFFFFF)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onPreviewKeyEvent { keyEvent ->
                val code = keyEvent.nativeKeyEvent.keyCode
                when {
                    keyEvent.type == KeyEventType.KeyDown && isTvOkKey(code) -> {
                        if (keyEvent.nativeKeyEvent.repeatCount == 0 && !keyEvent.nativeKeyEvent.isLongPress) {
                            sawFreshDown = true
                        }
                        true
                    }
                    keyEvent.type == KeyEventType.KeyUp && isTvOkKey(code) -> {
                        if (sawFreshDown) {
                            sawFreshDown = false
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .onFocusChanged {
                isFocused = it.isFocused
                if (!it.isFocused) sawFreshDown = false
            }
            .focusable()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isFocused) accentColor else Color.White,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    color = Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtext != null) {
                    Text(
                        text = subtext,
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
