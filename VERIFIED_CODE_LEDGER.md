# SỔ CÁI KHÓA MÃ NGUỒN ĐÃ CHUẨN (VERIFIED CODE LEDGER - DUYTV)

> **QUY TẮC BẤT BIẾN (MANDATORY INVARIANT)**:
> Mọi khối mã và tệp tin được đánh dấu `[✅ ĐÃ CHUẨN - CẤM SỬA]` trong sổ cái này đã được kiểm chứng thực tế trên **Tivi Sharp Android TV** (Remote D-Pad) và **Máy tính bảng Xiaomi** (Cảm ứng chạm 1 lần). **TUYỆT ĐỐI KHÔNG TỰ Ý SỬA ĐỔI, REFACTOR HOẶC GHI ĐÈ** các phần đã đánh dấu chuẩn này trong mọi phiên làm việc tiếp theo.

---

## Danh sách 20/20 Tệp Mã Nguồn & Trạng Thái Khóa Chuẩn

| STT | Tệp mã nguồn | Trạng thái | Các chốt kỹ thuật bất biến `[✅ ĐÃ CHUẨN - CẤM SỬA]` |
| :--- | :--- | :---: | :--- |
| 1 | `DuyTvApplication.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Singleton `ImageLoader` duy nhất: `OkHttpClient` kèm `User-Agent`, `allowRgb565(true)`, `allowHardware(hwProfile.allowHardwareBitmaps)`, RAM Cache `12MB`, Disk Cache `50MB`, `respectCacheHeaders(false)`. |
| 2 | `MainActivity.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Khởi tạo `AppCacheManager.init()`, giữ màn hình sáng (`FLAG_KEEP_SCREEN_ON`), ẩn thanh điều hướng (`Full Immersive Mode`). **CẤM** gọi `Coil.setImageLoader()` tại đây để không ghi đè `DuyTvApplication`. |
| 3 | `data/ApiModels.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Cấu trúc dữ liệu `Movie`, `Episode`, `EpisodeServer`, `OphimResponse` chuẩn hóa đa nguồn. |
| 4 | `data/AppCacheManager.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Lưu trữ đa tầng L1 RAM (`ConcurrentHashMap`) + L2 Disk Cache (`Atomic Write` qua tệp `.tmp`) + Danh sách Yêu thích (`Favorites`). |
| 5 | `data/MovieRepository.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Sắp xếp ưu tiên luồng `FHD 1080p` lên đầu mỗi kênh (`prioritizeChannelStreams`), tích hợp 172 luồng sống `A3 IPTV FHD` (`https://a3iptv.goitoi.co/rtp/...`), hợp nhất phim từ OPhim / KKPhim / NguonC. |
| 6 | `data/RemoteConfigManager.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Đồng bộ cấu hình IPTV / Sports từ đám mây kèm fallback an toàn về cấu hình mặc định. |
| 7 | `data/TvModels.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Mô hình `TvChannel` (đa luồng `streamUrls`) & `FootballMatch` tự động tính trạng thái `LIVE` / `UPCOMING` / `FINISHED` theo múi giờ chuẩn `GMT+7`. |
| 8 | `data/WatchHistoryManager.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Lưu lịch sử xem phim, vị trí đang xem dở (`positionMs`) và danh sách kênh xem gần đây (`Recent Channels`). |
| 9 | `ui/components/FootballMatchCard.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Thẻ trận đấu dùng `.clickable(interactionSource, indication = null)` — **CẤM** thêm `.focusable()` phía trước `.clickable()` để đảm bảo chạm 1 lần ăn ngay trên màn hình cảm ứng Xiaomi và vẫn nhận D-Pad trên Tivi. |
| 10 | `ui/components/MovieCard.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Thẻ phim dùng `.clickable()` duy nhất (chạm 1 lần ăn ngay), ảnh Poster ép chuẩn `300x450` (`RGB_565`). |
| 11 | `ui/components/TvBanner.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Banner Hero dùng `.clickable()` duy nhất cho các nút hành động, ảnh Backdrop ép chuẩn `960x540` (`RGB_565`) chống tràn VRAM `EGL_BAD_ALLOC`. |
| 12 | `ui/components/TvChannelCard.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Thẻ kênh truyền hình dùng `.clickable()` duy nhất, Logo kênh dùng `ARGB_8888` (`240x160`) giữ nền trong suốt cho PNG. |
| 13 | `ui/screens/HomeScreen.kt` | ✅ **ĐÃ KHÓA CHUẨN** | • 0 `.focusable()` thừa trước `.clickable()`.<br>• `lastAutoSyncMillis = System.currentTimeMillis()` tránh gọi trùng lặp API khi khởi động.<br>• Bộ lọc con truyền hình: `Tất cả`, `VTV`, `VTVcab`, `HTV`, `Thể Thao`, `Phim & Giải Trí`, `Quốc Tế VIP`, `Địa Phương`.<br>• Hộp thoại cập nhật (`UpdateDialog`) có `verticalScroll(rememberScrollState())` và nút cập nhật luôn hiển thị. |
| 14 | `ui/screens/MovieDetailScreen.kt` | ✅ **ĐÃ KHÓA CHUẨN** | • 0 `.focusable()` thừa trước `.clickable()`.<br>• Nút `QUAY LẠI` cảm ứng cả ở trạng thái tải thành công lẫn khi `movie == null`. |
| 15 | `ui/screens/PlayerScreen.kt` | ✅ **ĐÃ KHÓA CHUẨN** | • `ON_RESUME` kiểm tra `STATE_IDLE` để `prepare()` + `play()` lại khi bật màn hình.<br>• Tách riêng `PlayerQuickActionSheet` để hàm biên dịch dưới ngưỡng `16,384` lệnh ART `dex2oat`.<br>• Nút cảm ứng `✕ ĐÓNG` và chạm nền tối để đóng bảng chọn kênh nhanh.<br>• Cờ giải mã luồng IPTV MPEG-TS (`FLAG_ALLOW_NON_IDR_KEYFRAMES \| FLAG_DETECT_ACCESS_UNITS`) + nhận diện `VIDEO_MP2T` cho `a3iptv.goitoi.co/rtp/`. |
| 16 | `ui/screens/SearchScreen.kt` | ✅ **ĐÃ KHÓA CHUẨN** | 0 `.focusable()` thừa trước `.clickable()` trên toàn bộ bàn phím ảo và nút gợi ý (chạm 1 lần ăn ngay trên Tablet Xiaomi). |
| 17 | `ui/theme/Theme.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Bảng màu `DuyTvTheme` tối ưu độ tương phản cho màn hình Tivi & Tablet. |
| 18 | `util/AppUpdateManager.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Ưu tiên kiểm tra và tải APK từ **GitHub Cloud (`raw.githubusercontent.com`) trước tiên** (0 giây chờ timeout LAN), chỉ dùng LAN làm phương án dự phòng thứ 2. |
| 19 | `util/HardwareOptimizer.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Tự động nhận diện SoC (MediaTek, Realtek, Amlogic, Qualcomm...) và cấp phát bộ đệm ExoPlayer tối ưu theo dung lượng RAM thực tế. |
| 20 | `util/HlsAdBlockEngine.kt` | ✅ **ĐÃ KHÓA CHUẨN** | Lọc bỏ quảng cáo chèn giữa luồng `.m3u8` (`#EXT-X-DISCONTINUITY`) mà không làm giật luồng phát chính. |
