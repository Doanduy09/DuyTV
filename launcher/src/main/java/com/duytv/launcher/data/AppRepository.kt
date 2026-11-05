package com.duytv.launcher.data

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

object AppRepository {

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

    fun getInstalledApps(context: Context): List<AppItem> {
        val pm = context.packageManager
        val result = mutableListOf<AppItem>()
        val seen = mutableSetOf<String>()

        // 1. First add priority apps with dedicated fallback styling
        for (cfg in PRESET_APPS) {
            val pkg = cfg.pkg
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val icon: Drawable? = try {
                    appInfo.loadBanner(pm) ?: appInfo.loadIcon(pm)
                } catch (t: Throwable) {
                    try { pm.getApplicationIcon(pkg) } catch (e: Throwable) { null }
                }

                result.add(
                    AppItem(
                        id = pkg,
                        packageName = pkg,
                        label = cfg.label,
                        icon = icon,
                        customBadge = cfg.badge,
                        cardColor = cfg.cardColor,
                        isCustomBranded = true
                    )
                )
                seen.add(pkg)
            } catch (t: Throwable) {
                // If package info lookup failed, but it's settings or a known app, still add with fallback
                if (pkg == "com.android.tv.settings") {
                    result.add(
                        AppItem(
                            id = pkg,
                            packageName = pkg,
                            label = cfg.label,
                            cardColor = cfg.cardColor,
                            isCustomBranded = true
                        )
                    )
                    seen.add(pkg)
                }
            }
        }

        // 2. Query remaining leanback apps
        try {
            val leanbackIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val leanbackResolveInfos = pm.queryIntentActivities(leanbackIntent, 0)
            for (info in leanbackResolveInfos) {
                val pkg = info.activityInfo.packageName
                if (pkg != context.packageName && !seen.contains(pkg) && !pkg.contains("launcher") && !pkg.contains("setup")) {
                    val label = info.loadLabel(pm).toString()
                    val icon = try {
                        info.activityInfo.loadBanner(pm) ?: info.activityInfo.loadIcon(pm)
                    } catch (t: Throwable) {
                        null
                    }
                    result.add(
                        AppItem(
                            id = pkg,
                            packageName = pkg,
                            label = label,
                            icon = icon,
                            cardColor = 0xFF1E293B
                        )
                    )
                    seen.add(pkg)
                }
            }
        } catch (t: Throwable) {}

        // 3. Query regular launcher apps if not already in list
        try {
            val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val mainResolveInfos = pm.queryIntentActivities(mainIntent, 0)
            for (info in mainResolveInfos) {
                val pkg = info.activityInfo.packageName
                if (pkg != context.packageName && !seen.contains(pkg) && !pkg.contains("launcher") && !pkg.startsWith("com.android.cts") && !pkg.contains("setup")) {
                    val label = info.loadLabel(pm).toString()
                    val icon = try {
                        info.loadIcon(pm)
                    } catch (t: Throwable) {
                        null
                    }
                    result.add(
                        AppItem(
                            id = pkg,
                            packageName = pkg,
                            label = label,
                            icon = icon,
                            cardColor = 0xFF1E293B
                        )
                    )
                    seen.add(pkg)
                }
            }
        } catch (t: Throwable) {}

        return result
    }
}
