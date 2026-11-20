package com.duytv.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.duytv.app.data.AppCacheManager
import com.duytv.app.data.MovieRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class DuyTvApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // Crash Diagnostics: Ghi log lỗi chi tiết trước khi bàn giao lại cho hệ thống (tránh tạo zombie thread)
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            android.util.Log.e("CrashShield", "Uncaught exception on thread ${thread.name}: ${throwable.message}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        AppCacheManager.init(this, MovieRepository.getVerifiedOfficialChannels())
        com.duytv.app.util.GeminiVoiceClient.init(this)

        // Lazy-start các máy chủ LAN/DLNA ở luồng nền sau khi UI sẵn sàng để không chậm Cold Start;
        // Chỉ tự động giữ máy chủ LAN 24/7 trên Android TV (tránh hao pin trên Điện thoại / Tablet).
        val appCtx = applicationContext
        if (com.duytv.app.util.HardwareOptimizer.isTvDevice(appCtx)) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                com.duytv.app.util.LanCommandReceiver.start(appCtx)
                com.duytv.app.util.LanMediaServer.start(appCtx)
                com.duytv.app.util.DlnaMediaRenderer.start(appCtx)
            }, 600L)
        }
    }

    override fun newImageLoader(): ImageLoader {
        val imageHttpClient = com.duytv.app.util.SharedHttpClients.newClientBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                    .build()
                chain.proceed(req)
            }
            .build()

        val hwProfile = com.duytv.app.util.HardwareOptimizer.getProfile(this)
        val ramCacheBytes = when {
            hwProfile.isLowRamDevice -> 12 * 1024 * 1024 // 12MB RAM cho TV RAM thấp tránh tràn GraphicBuffer (EGL_BAD_ALLOC)
            hwProfile.totalRamMb >= 5500L -> 64 * 1024 * 1024 // 64MB RAM cho Tablet 3.2K 144Hz (Xiaomi Pad 7 Pro) & Z Fold
            else -> 32 * 1024 * 1024 // 32MB RAM cho thiết bị tầm trung
        }
        val diskCacheBytes = if (hwProfile.isLowRamDevice) 50L * 1024 * 1024 else 100L * 1024 * 1024
        return ImageLoader.Builder(this)
            .okHttpClient(imageHttpClient)
            .allowHardware(hwProfile.allowHardwareBitmaps)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(ramCacheBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(diskCacheBytes)
                    .build()
            }
            .allowRgb565(true) // Dùng RGB_565 cho ảnh JPEG poster (tiết kiệm 50% RAM) nhưng giữ kênh trong suốt Alpha cho logo PNG truyền hình
            .crossfade(false)
            .respectCacheHeaders(false) // Cache triệt để poster phim & logo kênh để lướt siêu mượt 60fps
            .build()
    }
}
