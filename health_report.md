## ⚠️ Cảnh Báo Sập Nguồn Phim DuyTV (2026-11-16T10:08:51.904Z)

Hệ thống kiểm tra tự động phát hiện một hoặc nhiều nguồn phim không phản hồi:

| Nguồn | URL | Trạng Thái | Chi Tiết |
|---|---|---|---|
| KKPhim | https://phimapi.com | ✅ Sống | 687ms |
| OPhim | https://ophim1.com, https://ophim17.cc, https://ophim16.cc | ❌ SẬP | connect ECONNREFUSED 127.0.0.1:443 |
| NguonC | https://phim.nguonc.com/api/film | ✅ Sống | 710ms |
| CoBePhim | https://cobephim.mom | ❌ SẬP | connect ECONNREFUSED 127.0.0.1:443 |
| YTS 4K Ultra HD Cinema (https://yts.gg) | https://yts.gg | ✅ Sống | 1541ms |

### 🛠️ Hướng Dẫn Sửa Đổi:
1. Mở file `config/sources.json` trên GitHub.
2. Cập nhật `api_base` của nguồn bị lỗi sang tên miền mới.
3. Bấm Commit Changes. DuyTV trên TV và Tablet sẽ tự động cập nhật ngay!
