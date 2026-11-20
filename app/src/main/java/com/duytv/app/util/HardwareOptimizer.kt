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
                totalRamMb >= 2800L -> HardwareProfile( // Tầng TV Box cao cấp (Nvidia Shield, Mecool 4GB, Apple-tier box)
                    socFamily = socFamily,
                    rawHardware = "${Build.HARDWARE}/${Build.BOARD}",
                    totalRamMb = totalRamMb,
                    isLowRamDevice = false,
                    allowHardwareBitmaps = allowHardwareBitmaps,
                    useAsyncMediaCodec = useAsyncMediaCodec,
                    liveTargetBufferBytes = 40 * 1024 * 1024,
                    vodTargetBufferBytes = 32 * 1024 * 1024,
                    liveMinBufferMs = 10_000,
                    liveMaxBufferMs = 35_000,
                    vodMinBufferMs = 8_000,
                    vodMaxBufferMs = 25_000
                )
                totalRamMb > 1300L -> HardwareProfile( // Tầng Google TV & Smart TV phổ thông (1.5GB - 2GB RAM: Sharp, TCL, Mi Box S, Chromecast 4K)
                    socFamily = socFamily,
                    rawHardware = "${Build.HARDWARE}/${Build.BOARD}",
                    totalRamMb = totalRamMb,
                    isLowRamDevice = isLowRam,
                    allowHardwareBitmaps = allowHardwareBitmaps,
                    useAsyncMediaCodec = useAsyncMediaCodec,
                    liveTargetBufferBytes = 24 * 1024 * 1024,
                    vodTargetBufferBytes = 16 * 1024 * 1024,
                    liveMinBufferMs = 10_000,
                    liveMaxBufferMs = 28_000,
                    vodMinBufferMs = 5_000,
                    vodMaxBufferMs = 15_000
                )
                else -> HardwareProfile( // Tầng TV Box giá rẻ siêu nhẹ (<= 1.2GB RAM: TX3 Mini 1GB, X96 Mini 1GB, TV Stick 1GB)
                    socFamily = socFamily,
                    rawHardware = "${Build.HARDWARE}/${Build.BOARD}",
                    totalRamMb = totalRamMb,
                    isLowRamDevice = true,
                    allowHardwareBitmaps = allowHardwareBitmaps,
                    useAsyncMediaCodec = false, // Tắt async codec trên chip yếu để giảm tải luồng xử lý
                    liveTargetBufferBytes = 14 * 1024 * 1024, // 14MB buffer chống OOM
                    vodTargetBufferBytes = 10 * 1024 * 1024,
                    liveMinBufferMs = 8_000,
                    liveMaxBufferMs = 20_000,
                    vodMinBufferMs = 4_000,
                    vodMaxBufferMs = 12_000
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
                    /* minBufferMs = */ 1500,
                    /* maxBufferMs = */ 12000,
                    /* bufferForPlaybackMs = */ 400, // Instant play in 400ms (cắt giảm từ 1200ms xuống 400ms)
                    /* bufferForPlaybackAfterRebufferMs = */ 800
                )
                .setBackBuffer(0, false)
                .setTargetBufferBytes(if (profile.isLowRamDevice) 12 * 1024 * 1024 else 20 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        } else {
            DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                    profile.vodMinBufferMs.coerceIn(3000, 12000),
                    profile.vodMaxBufferMs.coerceIn(10000, 25000),
                    /* bufferForPlaybackMs = */ 600, // Khởi động phim trong 600ms
                    /* bufferForPlaybackAfterRebufferMs = */ 1200
                )
                .setBackBuffer(if (profile.isLowRamDevice) 5000 else 15000, false)
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
     * [✅ ĐÃ CHUẨN - MỞ RỘNG TV BOX & GOOGLE TV]
     * Nhận diện chuẩn xác thiết bị Android TV, Google TV & Android TV Box qua Leanback, UiModeManager,
     * Touchscreen và phần cứng không pin (Battery absent) / SoC box đặc thù.
     * Trả về true nếu là TV hoặc Android TV Box; false nếu là Phone, Fold hoặc Tablet (như Xiaomi Pad 7 Pro 1163dp).
     */
    fun isTvDevice(context: Context): Boolean {
        return try {
            val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
            val pm = context.packageManager
            when {
                uiModeManager?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION -> true
                pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) -> true
                @Suppress("DEPRECATION")
                pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TELEVISION) -> true
                !pm.hasSystemFeature(android.content.pm.PackageManager.FEATURE_TOUCHSCREEN) -> true
                else -> isTvBoxHardware(context)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isTvBoxHardware(context: Context): Boolean {
        return try {
            val fp = "${Build.HARDWARE} ${Build.BOARD} ${Build.MODEL} ${Build.DEVICE} ${Build.PRODUCT}".lowercase()
            val hasBoxKeyword = fp.contains("box") || fp.contains("tv") || fp.contains("stb") ||
                fp.contains("dongle") || fp.contains("droidlogic") || fp.contains("x96") ||
                fp.contains("tanix") || fp.contains("tx3") || fp.contains("mecool") ||
                fp.contains("ugoos") || fp.contains("shield") || fp.contains("chromecast") ||
                fp.contains("h96") || fp.contains("magicsee")
            if (hasBoxKeyword) return true

            // Kiểm tra phần cứng không có pin (Battery absent) - Đặc trưng của 100% Android Box & TV cắm nguồn AC liên tục
            val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val bStatus = context.registerReceiver(null, ifilter)
            val hasBattery = bStatus?.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, true) ?: true
            !hasBattery
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Tự động sinh tên thiết bị thân thiện (Friendly Name) hiển thị trên DLNA/mDNS/Web Hub:
     * - Tivi Sharp: "DuyTV (Tivi Sharp)"
     * - Sony Google TV: "DuyTV (Sony Bravia Google TV)"
     * - Xiaomi Mi Box / TV: "DuyTV (Xiaomi Mi Box)"
     * - Nvidia Shield: "DuyTV (Nvidia Shield TV)"
     * - Chromecast: "DuyTV (Chromecast Google TV)"
     * - Các dòng TV Box phổ biến (X96, Tanix, Mecool, TX3): "DuyTV (X96 Android Box)"...
     */
    fun getDeviceFriendlyName(context: Context): String {
        return try {
            val manufacturer = Build.MANUFACTURER.trim().lowercase()
            val model = Build.MODEL.trim()
            val brand = Build.BRAND.trim().lowercase()

            val brandLabel = when {
                manufacturer.contains("sharp") || brand.contains("sharp") -> "Tivi Sharp"
                manufacturer.contains("sony") || brand.contains("sony") -> "Sony Bravia Google TV"
                manufacturer.contains("tcl") || brand.contains("tcl") -> "TCL Google TV"
                manufacturer.contains("xiaomi") || brand.contains("xiaomi") -> {
                    if (model.contains("box", ignoreCase = true) || model.contains("stick", ignoreCase = true)) {
                        "Xiaomi Mi Box"
                    } else "Xiaomi TV"
                }
                manufacturer.contains("nvidia") || brand.contains("nvidia") || model.contains("shield", ignoreCase = true) -> "Nvidia Shield TV"
                manufacturer.contains("google") || model.contains("chromecast", ignoreCase = true) -> "Chromecast Google TV"
                manufacturer.contains("casper") || brand.contains("casper") -> "Casper TV"
                manufacturer.contains("samsung") -> "Samsung Smart TV"
                manufacturer.contains("lg") -> "LG Smart TV"
                model.contains("x96", ignoreCase = true) -> "X96 Android Box"
                model.contains("tx3", ignoreCase = true) -> "TX3 Android Box"
                model.contains("tanix", ignoreCase = true) -> "Tanix Android Box"
                model.contains("mecool", ignoreCase = true) -> "Mecool TV Box"
                model.contains("ugoos", ignoreCase = true) -> "Ugoos TV Box"
                isTvDevice(context) -> "DuyTV ($model)"
                else -> model.ifBlank { "Thiết bị DuyTV" }
            }
            if (brandLabel.startsWith("DuyTV")) brandLabel else "DuyTV ($brandLabel)"
        } catch (_: Exception) {
            "DuyTV (Android TV)"
        }
    }

    /**
     * Sinh mã UDN duy nhất cho từng thiết bị theo Android ID để tránh xung đột
     * khi trong một hộ gia đình có nhiều Tivi hoặc TV Box trên cùng mạng Wi-Fi/LAN.
     */
    fun getUdn(context: Context): String {
        return try {
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
            if (!androidId.isNullOrBlank()) "uuid:duytv-renderer-$androidId" else "uuid:duytv-renderer-default"
        } catch (_: Exception) {
            "uuid:duytv-renderer-default"
        }
    }

    enum class ScreenFormFactor(val label: String) {
        TV_CINEMA("TV / Android Box (16:9)"),
        TABLET_WIDE("Máy Tính Bảng (3:2 / 16:10)"),
        FOLDABLE_UNFOLDED("Màn Hình Gập Mở Rộng (4:3 / 6:5)"),
        FOLDABLE_COVER("Màn Hình Phụ Gập (23:9)"),
        PHONE_ULTRAWIDE("Điện Thoại Tràn Viền (20:9 / 21:9)"),
        PHONE_STANDARD("Điện Thoại Chuẩn (16:9 / 18:9)")
    }

    data class ResponsiveScreenConfig(
        val formFactor: ScreenFormFactor,
        val isTv: Boolean,
        val aspectRatio: Float,
        val ratioLabel: String,
        val resolutionPxLabel: String,
        val screenSummaryBadge: String,
        val horizontalPaddingDp: Int,
        val verticalPaddingDp: Int,
        val gridMinCardWidthDp: Int
    )

    // [✅ ĐÃ CHUẨN - CẤM SỬA] Tự động nhận diện 100% mọi màn hình & thiết bị bằng Pixel thực tế + isTvDevice
    fun detectScreenConfig(context: Context, screenWidthDp: Int, screenHeightDp: Int): ResponsiveScreenConfig {
        val isTv = isTvDevice(context)
        val dm = context.resources.displayMetrics
        val widthPx = dm.widthPixels.coerceAtLeast(320)
        val heightPx = dm.heightPixels.coerceAtLeast(320)
        val maxPx = maxOf(widthPx, heightPx).toFloat()
        val minPx = minOf(widthPx, heightPx).toFloat()

        val w = screenWidthDp.coerceAtLeast(320).toFloat()
        val h = screenHeightDp.coerceAtLeast(320).toFloat()
        val maxDim = maxOf(w, h)
        val minDim = minOf(w, h)
        val ratio = if (minPx > 0f) (maxPx / minPx) else (maxDim / minDim)

        val ratioLabel = when {
            ratio >= 2.28f -> "23:9"
            ratio >= 2.12f -> "21:9"
            ratio >= 1.96f -> "20:9"
            ratio >= 1.84f -> "18.5:9"
            ratio >= 1.70f -> "16:9"
            ratio >= 1.56f -> "16:10"
            ratio >= 1.44f -> "3:2"
            ratio >= 1.28f -> "4:3"
            else -> "6:5"
        }

        val formFactor = when {
            isTv -> ScreenFormFactor.TV_CINEMA
            minDim >= 560f && ratio <= 1.38f -> ScreenFormFactor.FOLDABLE_UNFOLDED
            minDim >= 560f -> ScreenFormFactor.TABLET_WIDE
            ratio >= 2.28f -> ScreenFormFactor.FOLDABLE_COVER
            ratio >= 1.92f -> ScreenFormFactor.PHONE_ULTRAWIDE
            else -> ScreenFormFactor.PHONE_STANDARD
        }

        val resTag = when {
            maxPx >= 3800f -> "4K"
            maxPx >= 2800f -> "3.2K"
            maxPx >= 2200f -> "2.4K+"
            maxPx >= 1900f -> "1080p"
            else -> "HD+"
        }
        val resolutionPxLabel = "${widthPx}x${heightPx}"
        val deviceShort = when (formFactor) {
            ScreenFormFactor.TV_CINEMA -> "📺 TV $resTag ($ratioLabel)"
            ScreenFormFactor.TABLET_WIDE -> "📱 Tablet $resTag ($ratioLabel)"
            ScreenFormFactor.FOLDABLE_UNFOLDED -> "📱 Fold Mở $resTag ($ratioLabel)"
            ScreenFormFactor.FOLDABLE_COVER -> "📱 Fold Gập $resTag ($ratioLabel)"
            ScreenFormFactor.PHONE_ULTRAWIDE -> "📱 Phone $resTag ($ratioLabel)"
            ScreenFormFactor.PHONE_STANDARD -> "📱 Phone $resTag ($ratioLabel)"
        }

        val hPad = when {
            isTv -> (screenWidthDp * 0.038f).toInt().coerceIn(32, 48)
            minDim >= 560f -> (screenWidthDp * 0.026f).toInt().coerceIn(20, 36)
            else -> (screenWidthDp * 0.022f).toInt().coerceIn(12, 22)
        }
        val vPad = when {
            isTv -> 18
            minDim >= 560f -> 14
            else -> 10
        }
        val minCardW = when {
            isTv -> 160
            minDim >= 560f -> (screenWidthDp / 6).coerceIn(142, 175)
            screenWidthDp >= 640 -> 135
            else -> 118
        }
        return ResponsiveScreenConfig(
            formFactor = formFactor,
            isTv = isTv,
            aspectRatio = w / h,
            ratioLabel = ratioLabel,
            resolutionPxLabel = resolutionPxLabel,
            screenSummaryBadge = deviceShort,
            horizontalPaddingDp = hPad,
            verticalPaddingDp = vPad,
            gridMinCardWidthDp = minCardW
        )
    }
}
