package com.duytv.app

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.duytv.app.data.AppCacheManager
import com.duytv.app.data.MovieRepository

class DuyTvApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AppCacheManager.init(this, MovieRepository.getVerifiedOfficialChannels())
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15) // Giới hạn 15% heap cho TV 1.5GB RAM (chống OOM)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(50L * 1024 * 1024) // 50MB disk cache lưu poster offline
                    .build()
            }
            .bitmapConfig(Bitmap.Config.RGB_565) // Tiết kiệm 50% RAM cho ảnh (2 bytes/px thay vì 4 bytes/px)
            .crossfade(true)
            .respectCacheHeaders(false) // Cache triệt để poster phim để lướt siêu mượt 60fps
            .build()
    }
}
