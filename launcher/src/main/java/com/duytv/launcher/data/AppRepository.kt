package com.duytv.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap

object AppRepository {

    private const val PREFS_NAME = "duytv_launcher_prefs"
    private const val KEY_HIDDEN_PACKAGES = "hidden_packages"
    private const val KEY_UNHIDDEN_SYSTEM_PACKAGES = "unhidden_system_packages"

    private val iconBitmapCache = ConcurrentHashMap<String, ImageBitmap>()

    // Các gói hệ thống nội bộ của Tivi Sharp / MediaTek gây rối mắt sẽ tự động ẩn mặc định
    // (Anh Duy vẫn có thể bật hiện lại bất kỳ lúc nào trong bảng Quản Lý Ẩn/Hiện App)
    private val DEFAULT_HIDDEN_SYSTEM_PACKAGES = setOf(
        "com.mediatek.ui",
        "com.mediatek.tvinput",
        "jp.co.sharp.av.android.epglinker",
        "jp.co.sharp.av.android.pouf",
        "jp.co.sharp.av.android.systemupdate",
        "jp.co.sharp.av.android.tvcore",
        "jp.co.sharp.av.android.storedemoapplication",
        "com.minhtamvn.customchannels"
    )

    data class AppConfig(
        val pkg: String,
        val label: String,
        val cardColor: Long,
        val badge: String? = null
    )

    private val PRESET_APPS = listOf(
        AppConfig("com.duytv.app", "DuyTV", 0xFF0F172A, "4K CINEMA"),
        AppConfig("com.google.android.youtube.tv", "YouTube", 0xFFFFFFFF, null),
        AppConfig("com.quang.monstertv", "VietMiTV", 0xFF090D16, "TRUYỀN HÌNH"),
        AppConfig("com.sports.tv", "Sport Stream", 0xFF0D1B2A, "LIVE"),
        AppConfig("studio.scillarium.ottnavigator", "OTT Navigator", 0xFF1E293B, null),
        AppConfig("com.stremio.one", "Stremio", 0xFF1E1035, null),
        AppConfig("com.lonelycatgames.Xplore", "X-plore", 0xFF3E2723, null),
        AppConfig("com.android.tv.settings", "Cài Đặt", 0xFF1E293B, null),
        AppConfig("com.android.vending", "Google Play", 0xFF1E293B, null)
    )

    private fun getExplicitHiddenApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_HIDDEN_PACKAGES, emptySet()) ?: emptySet()
    }

    private fun getExplicitUnhiddenSystemApps(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_UNHIDDEN_SYSTEM_PACKAGES, emptySet()) ?: emptySet()
    }

    fun getHiddenApps(context: Context): Set<String> {
        val explicitHidden = getExplicitHiddenApps(context)
        val explicitUnhidden = getExplicitUnhiddenSystemApps(context)
        return (DEFAULT_HIDDEN_SYSTEM_PACKAGES - explicitUnhidden) + explicitHidden
    }

    fun isAppHidden(context: Context, packageName: String): Boolean {
        return getHiddenApps(context).contains(packageName)
    }

    fun hideApp(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hidden = getExplicitHiddenApps(context).toMutableSet()
        val unhidden = getExplicitUnhiddenSystemApps(context).toMutableSet()
        hidden.add(packageName)
        unhidden.remove(packageName)
        prefs.edit()
            .putStringSet(KEY_HIDDEN_PACKAGES, HashSet(hidden))
            .putStringSet(KEY_UNHIDDEN_SYSTEM_PACKAGES, HashSet(unhidden))
            .apply()
    }

    fun unhideApp(context: Context, packageName: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val hidden = getExplicitHiddenApps(context).toMutableSet()
        val unhidden = getExplicitUnhiddenSystemApps(context).toMutableSet()
        hidden.remove(packageName)
        if (DEFAULT_HIDDEN_SYSTEM_PACKAGES.contains(packageName)) {
            unhidden.add(packageName)
        }
        prefs.edit()
            .putStringSet(KEY_HIDDEN_PACKAGES, HashSet(hidden))
            .putStringSet(KEY_UNHIDDEN_SYSTEM_PACKAGES, HashSet(unhidden))
            .apply()
    }

    fun toggleAppVisibility(context: Context, packageName: String): Boolean {
        return if (isAppHidden(context, packageName)) {
            unhideApp(context, packageName)
            true // Now visible
        } else {
            hideApp(context, packageName)
            false // Now hidden
        }
    }

    fun resetHiddenApps(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_HIDDEN_PACKAGES)
            .remove(KEY_UNHIDDEN_SYSTEM_PACKAGES)
            .apply()
    }

    fun showAllAppsIncludingSystem(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(KEY_HIDDEN_PACKAGES, emptySet())
            .putStringSet(KEY_UNHIDDEN_SYSTEM_PACKAGES, HashSet(DEFAULT_HIDDEN_SYSTEM_PACKAGES))
            .apply()
    }

    private fun getOrDecodeIconBitmap(pkg: String, drawable: Drawable?): ImageBitmap? {
        if (drawable == null) return null
        iconBitmapCache[pkg]?.let { return it }
        return try {
            val targetSize = 96
            val bmp = if (drawable is BitmapDrawable && drawable.bitmap != null) {
                val src = drawable.bitmap
                if (src.width <= targetSize && src.height <= targetSize) {
                    src
                } else {
                    Bitmap.createScaledBitmap(src, targetSize, targetSize, true)
                }
            } else {
                val width = drawable.intrinsicWidth.coerceIn(1, targetSize)
                val height = drawable.intrinsicHeight.coerceIn(1, targetSize)
                val created = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(created)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                created
            }
            val imgBmp = bmp.asImageBitmap()
            iconBitmapCache[pkg] = imgBmp
            imgBmp
        } catch (_: Throwable) {
            null
        }
    }

    private fun queryAllCandidateApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        val result = mutableListOf<AppItem>()
        val seen = mutableSetOf<String>()

        // 1. Priority preset apps if installed
        for (cfg in PRESET_APPS) {
            val pkg = cfg.pkg
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val icon: Drawable? = try {
                    appInfo.loadIcon(pm) ?: appInfo.loadBanner(pm)
                } catch (_: Throwable) {
                    try { pm.getApplicationIcon(pkg) } catch (_: Throwable) { null }
                }
                result.add(
                    AppItem(
                        id = pkg,
                        packageName = pkg,
                        label = cfg.label,
                        icon = icon,
                        iconBitmap = getOrDecodeIconBitmap(pkg, icon),
                        customBadge = cfg.badge,
                        cardColor = cfg.cardColor,
                        isCustomBranded = true,
                        isSystemApp = isSystem
                    )
                )
                seen.add(pkg)
            } catch (_: Throwable) {
                if (pkg == "com.android.tv.settings") {
                    result.add(
                        AppItem(
                            id = pkg,
                            packageName = pkg,
                            label = cfg.label,
                            cardColor = cfg.cardColor,
                            isCustomBranded = true,
                            isSystemApp = true
                        )
                    )
                    seen.add(pkg)
                }
            }
        }

        // 2. Query Leanback apps
        try {
            val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val leanbackResolveInfos = pm.queryIntentActivities(leanbackIntent, 0)
            for (info in leanbackResolveInfos) {
                val pkg = info.activityInfo.packageName
                if (pkg != context.packageName && !seen.contains(pkg) && !pkg.contains("launcher") && !pkg.contains("setup")) {
                    val label = info.loadLabel(pm).toString()
                    val isSystem = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val icon = try {
                        info.activityInfo.loadIcon(pm) ?: info.activityInfo.loadBanner(pm)
                    } catch (_: Throwable) {
                        null
                    }
                    result.add(
                        AppItem(
                            id = pkg,
                            packageName = pkg,
                            label = label,
                            icon = icon,
                            iconBitmap = getOrDecodeIconBitmap(pkg, icon),
                            cardColor = 0xFF1E293B,
                            isSystemApp = isSystem
                        )
                    )
                    seen.add(pkg)
                }
            }
        } catch (_: Throwable) {}

        // 3. Query regular Launcher apps
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val mainResolveInfos = pm.queryIntentActivities(mainIntent, 0)
            for (info in mainResolveInfos) {
                val pkg = info.activityInfo.packageName
                if (pkg != context.packageName && !seen.contains(pkg) && !pkg.contains("launcher") && !pkg.startsWith("com.android.cts") && !pkg.contains("setup")) {
                    val label = info.loadLabel(pm).toString()
                    val isSystem = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val icon = try {
                        info.loadIcon(pm)
                    } catch (_: Throwable) {
                        null
                    }
                    result.add(
                        AppItem(
                            id = pkg,
                            packageName = pkg,
                            label = label,
                            icon = icon,
                            iconBitmap = getOrDecodeIconBitmap(pkg, icon),
                            cardColor = 0xFF1E293B,
                            isSystemApp = isSystem
                        )
                    )
                    seen.add(pkg)
                }
            }
        } catch (_: Throwable) {}

        return result
    }

    fun getInstalledApps(context: Context): List<AppItem> {
        val hiddenApps = getHiddenApps(context)
        return queryAllCandidateApps(context).filter { !hiddenApps.contains(it.packageName) }
    }

    /**
     * Trả về toàn bộ danh sách ứng dụng trên Tivi kèm trạng thái Hiển thị (true = Đang Hiện, false = Đang Ẩn)
     * để người dùng tùy ý bật/tắt ẩn hiện từng app.
     */
    fun getAllAppsForManagement(context: Context): List<Pair<AppItem, Boolean>> {
        val hiddenApps = getHiddenApps(context)
        return queryAllCandidateApps(context).map { app ->
            app to !hiddenApps.contains(app.packageName)
        }
    }
}
