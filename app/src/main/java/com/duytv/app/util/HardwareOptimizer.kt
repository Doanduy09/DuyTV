package com.duytv.app.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.upstream.DefaultAllocator

enum class SocFamily(val displayName: String) {
    QUALCOMM("Qualcomm Snapdragon (Adreno GPU)"),
    AMLOGIC("Amlogic Multimedia SoC"),
    ROCKCHIP("Rockchip VPU SoC"),
    REALTEK("Realtek Media SoC"),
    ALLWINNER("Allwinner CedarX SoC"),
    HISILICON("HiSilicon Imprex SoC"),
    EXYNOS_TENSOR("Samsung Exynos / Google Tensor"),
    MEDIATEK("MediaTek / Pentonic SoC"),
    X86_PC("Intel / AMD x86_64 Architecture"),
    GENERIC_ARM("Universal ARM64 Multi-Core SoC")
}

data class HardwareProfile(
    val socFamily: SocFamily,
    val rawHardware: String,
    val totalRamMb: Long,
    val isLowRamDevice: Boolean,
    val allowHardwareBitmaps: Boolean,
    val useAsyncMediaCodec: Boolean,
    val liveTargetBufferBytes: Int,
    val vodTargetBufferBytes: Int,
    val liveMinBufferMs: Int,
    val liveMaxBufferMs: Int,
    val vodMinBufferMs: Int,
    val vodMaxBufferMs: Int
)

object HardwareOptimizer {
    private const val TAG = "HardwareOptimizer"

    @Volatile
    private var cachedProfile: HardwareProfile? = null

    fun getProfile(context: Context): HardwareProfile {
        cachedProfile?.let { return it }
        synchronized(this) {
            cachedProfile?.let { return it }

            val hw = Build.HARDWARE.lowercase()
            val board = Build.BOARD.lowercase()
            val socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MANUFACTURER.lowercase()
            } else ""
            val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL.lowercase()
            } else ""
            val abis = Build.SUPPORTED_ABIS.joinToString(",").lowercase()
            val fingerprint = "$hw $board $socManufacturer $socModel $abis"

            val socFamily = when {
                fingerprint.contains("qcom") || fingerprint.contains("qualcomm") ||
                    fingerprint.contains("sm8") || fingerprint.contains("sm7") ||
                    fingerprint.contains("sdm") || fingerprint.contains("kona") ||
                    fingerprint.contains("taro") || fingerprint.contains("kalama") ||
                    fingerprint.contains("pineapple") || fingerprint.contains("cliff") -> SocFamily.QUALCOMM

                fingerprint.contains("amlogic") || fingerprint.contains("meson") ||
                    fingerprint.contains("gxbb") || fingerprint.contains("gxl") ||
                    fingerprint.contains("g12") || fingerprint.contains("sm1") ||
                    fingerprint.contains("sc2") || fingerprint.contains("s905") ||
                    fingerprint.contains("s922") || fingerprint.contains("s928") -> SocFamily.AMLOGIC

                fingerprint.contains("rk3") || fingerprint.contains("rockchip") -> SocFamily.ROCKCHIP

                fingerprint.contains("rtd") || fingerprint.contains("realtek") -> SocFamily.REALTEK

                fingerprint.contains("sunxi") || fingerprint.contains("allwinner") ||
                    fingerprint.contains("sun50i") || fingerprint.contains("sun8i") -> SocFamily.ALLWINNER

                fingerprint.contains("hisi") || fingerprint.contains("hi37") ||
                    fingerprint.contains("kirin") -> SocFamily.HISILICON

                fingerprint.contains("exynos") || fingerprint.contains("s5e") ||
                    fingerprint.contains("tensor") || fingerprint.contains("gs101") ||
                    fingerprint.contains("gs201") || fingerprint.contains("zuma") -> SocFamily.EXYNOS_TENSOR

                fingerprint.contains("mt") || fingerprint.contains("mediatek") ||
                    fingerprint.contains("pentonic") || fingerprint.contains("dimensity") ||
                    fingerprint.contains("helio") -> SocFamily.MEDIATEK

                abis.contains("x86") -> SocFamily.X86_PC

                else -> SocFamily.GENERIC_ARM
            }

            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager?.getMemoryInfo(memInfo)
            val totalRamMb = if (memInfo.totalMem > 0) memInfo.totalMem / (1024 * 1024) else 2048L
            val isLowRam = activityManager?.isLowRamDevice == true || totalRamMb <= 2600L

            // Chỉ tắt Hardware Bitmap trên Android 7/8 đời cũ dùng chip TV (MediaTek/Rockchip/Allwinner)
            // để tránh lỗi driver libJpgHwDecoder.so. Trên các chip đời mới (Snapdragon, Pad 7 Pro, Google TV, Android 9+),
            // bật Hardware Bitmap giúp tăng tốc GPU render poster lên gấp 3 lần và giảm 50% RAM.
            val hasLegacyHwBitmapBug = Build.VERSION.SDK_INT <= Build.VERSION_CODES.O_MR1 &&
                (socFamily == SocFamily.MEDIATEK || socFamily == SocFamily.ROCKCHIP || socFamily == SocFamily.ALLWINNER)
            val allowHardwareBitmaps = !hasLegacyHwBitmapBug

            // Bật hàng đợi giải mã bất đồng bộ (Async MediaCodec Queueing) trên Android 12+ (API 31+)
            // giúp màn hình 120Hz/144Hz trên Tablet/Phone (như Xiaomi Pad 7 Pro) và TV đời mới mượt tuyệt đối.
            val useAsyncMediaCodec = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

            val profile = when {
                totalRamMb >= 5500L -> HardwareProfile(
                    socFamily = socFamily,
                    rawHardware = "${Build.HARDWARE}/${Build.BOARD}",
                    totalRamMb = totalRamMb,
                    isLowRamDevice = false,
                    allowHardwareBitmaps = allowHardwareBitmaps,
                    useAsyncMediaCodec = useAsyncMediaCodec,
                    liveTargetBufferBytes = 64 * 1024 * 1024,
                    vodTargetBufferBytes = 48 * 1024 * 1024,
                    liveMinBufferMs = 12_000,
                    liveMaxBufferMs = 45_000,
                    vodMinBufferMs = 10_000,
                    vodMaxBufferMs = 40_000
                )
                !isLowRam -> HardwareProfile(
                    socFamily = socFamily,
                    rawHardware = "${Build.HARDWARE}/${Build.BOARD}",
                    totalRamMb = totalRamMb,
                    isLowRamDevice = false,
                    allowHardwareBitmaps = allowHardwareBitmaps,
                    useAsyncMediaCodec = useAsyncMediaCodec,
                    liveTargetBufferBytes = 40 * 1024 * 1024,
                    vodTargetBufferBytes = 28 * 1024 * 1024,
                    liveMinBufferMs = 10_000,
                    liveMaxBufferMs = 35_000,
                    vodMinBufferMs = 8_000,
                    vodMaxBufferMs = 25_000
                )
                else -> HardwareProfile(
                    socFamily = socFamily,
                    rawHardware = "${Build.HARDWARE}/${Build.BOARD}",
                    totalRamMb = totalRamMb,
                    isLowRamDevice = true,
                    allowHardwareBitmaps = allowHardwareBitmaps,
                    useAsyncMediaCodec = useAsyncMediaCodec,
                    liveTargetBufferBytes = 28 * 1024 * 1024,
                    vodTargetBufferBytes = 16 * 1024 * 1024,
                    liveMinBufferMs = 10_000,
                    liveMaxBufferMs = 28_000,
                    vodMinBufferMs = 5_000,
                    vodMaxBufferMs = 15_000
                )
            }

            Log.i(
                TAG,
                "Detected SoC: ${profile.socFamily.displayName} (${profile.rawHardware}), " +
                    "RAM: ${profile.totalRamMb}MB, hwBitmaps=${profile.allowHardwareBitmaps}, " +
                    "asyncCodec=${profile.useAsyncMediaCodec}, liveBuf=${profile.liveTargetBufferBytes / (1024 * 1024)}MB"
            )
            cachedProfile = profile
            return profile
        }
    }

    @UnstableApi
    fun buildLoadControl(context: Context, isLive: Boolean): DefaultLoadControl {
        val profile = getProfile(context)
        val segmentSize = if (profile.isLowRamDevice) 32 * 1024 else 64 * 1024
        val allocator = DefaultAllocator(true, segmentSize)

        return if (isLive) {
            DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                    profile.liveMinBufferMs,
                    profile.liveMaxBufferMs,
                    1_800,
                    3_500
                )
                .setTargetBufferBytes(profile.liveTargetBufferBytes)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                    profile.vodMinBufferMs,
                    profile.vodMaxBufferMs,
                    1_500,
                    2_500
                )
                .setTargetBufferBytes(profile.vodTargetBufferBytes)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        }
    }

    @UnstableApi
    fun buildRenderersFactory(context: Context): DefaultRenderersFactory {
        val profile = getProfile(context)
        return DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
            if (profile.useAsyncMediaCodec) {
                forceEnableMediaCodecAsynchronousQueueing()
            }
        }
    }

    /**
     * Nhận diện chuẩn xác thiết bị Android TV / TV Box qua Leanback & UiModeManager.
     * Trả về true nếu là TV (không có thanh trạng thái), false nếu là Phone, Fold hoặc Tablet.
     */
    fun isTvDevice(context: Context): Boolean {
        return try {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            if (uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION) {
                true
            } else {
                context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
            }
        } catch (_: Exception) {
            false
        }
    }
}
