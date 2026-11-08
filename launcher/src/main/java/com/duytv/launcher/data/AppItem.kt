package com.duytv.launcher.data

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap

data class AppItem(
    val id: String,
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
    val banner: Drawable? = null,
    val iconBitmap: ImageBitmap? = null,
    val customBadge: String? = null,
    val cardColor: Long = 0xFF1E293B,
    val isCustomBranded: Boolean = false,
    val isSystemApp: Boolean = false
) {
    fun launch(context: Context) {
        try {
            val intent = context.packageManager.getLeanbackLaunchIntentForPackage(packageName)
                ?: context.packageManager.getLaunchIntentForPackage(packageName)

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (packageName == "com.duytv.app") {
                    intent.putExtra("open_home", true)
                }
                context.startActivity(intent)
            } else if (packageName == "com.android.tv.settings") {
                val settingsIntent = Intent(Settings.ACTION_SETTINGS)
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(settingsIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun uninstall(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                data = Uri.parse("package:$packageName")
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val deleteIntent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(deleteIntent)
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    fun openDetails(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
