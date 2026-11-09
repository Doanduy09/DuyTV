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
        AppCacheManager.init(this, MovieRepository.getVerifiedOfficialChannels())
    }

    override fun newImageLoader(): ImageLoader {
        val imageHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
                    .build()
                chain.proceed(req)
            }
            .build()

        val hwProfile = com.duytv.app.util.HardwareOptimizer.getProfile(this)
        return ImageLoader.Builder(this)
            .okHttpClient(imageHttpClient)
            .allowHardware(hwProfile.allowHardwareBitmaps)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(12 * 1024 * 1024) // Giới hạn 12MB RAM tránh tràn GraphicBuffer (EGL_BAD_ALLOC)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024) // 50MB disk cache lưu poster offline
                    .build()
            }
            .allowRgb565(true) // Dùng RGB_565 cho ảnh JPEG poster (tiết kiệm 50% RAM) nhưng giữ kênh trong suốt Alpha cho logo PNG truyền hình
            .crossfade(false)
            .respectCacheHeaders(false) // Cache triệt để poster phim & logo kênh để lướt siêu mượt 60fps
            .build()
    }
}
