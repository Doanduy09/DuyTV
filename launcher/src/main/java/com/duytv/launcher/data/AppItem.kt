package com.duytv.launcher.data

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

data class AppItem(
    val id: String,
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
    val banner: Drawable? = null,
    val customBadge: String? = null,
    val cardColor: Long = 0xFF1E293B,
    val isCustomBranded: Boolean = false
) {
    fun launch(context: Context) {
        try {
            val intent = context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: context.packageManager.getLaunchIntentForPackage(packageName)

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else if (packageName == "com.android.tv.settings") {
                val settingsIntent = Intent(android.provider.Settings.ACTION_SETTINGS)
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
