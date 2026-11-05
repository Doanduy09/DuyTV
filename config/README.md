# 📁 HƯỚNG DẪN QUẢN LÝ CẤU HÌNH NGUỒN PHIM DUYTV

Thư mục này chứa file cấu hình duy nhất: **[`sources.json`](./sources.json)**

---

## 📺 Danh Sách Nguồn Phim Hiện Tại

| Mã Nguồn | Tên Hiển Thị | Thể Loại Phim | Domain Đang Hoạt Động |
|---|---|---|---|
| **`kkphim`** | KKPhim | Phim chiếu rạp, phim bộ mới nhất | `https://phimapi.com` |
| **`ophim`** | OPhim | Phim lẻ, phim bộ Châu Á, Anime | `https://ophim.cc` |
| **`nguonc`** | NguonC | Phim VIP tốc độ cao | `https://phim.nguonc.com/api/film` |
| **`cobephim`** | CoBePhim | Phim cổ trang xưa, kiếm hiệp TVB kinh điển | `https://cobephim.mom` |
| **`torrent4k`** | YTS 4K | Phim 4K Ultra HD bản quyền quốc tế + Phụ đề TV | `https://yts.gg` |

---

## 🛠️ Cách Thay Đổi Tên Miền Khi Nguồn Bị Sập

1. Bấm vào file **[`sources.json`](./sources.json)**.
2. Bấm vào biểu tượng **hình cây bút chì (Edit this file)** ở góc trên bên phải.
3. Tìm đến dòng `api_base` của nguồn muốn đổi và dán tên miền mới vào.
4. Bấm nút màu xanh **Commit changes...** ở góc trên.

🚀 **Ngay khi bấm Commit, GitHub sẽ tự động biên dịch bản APK mới tinh chỉ sau 2 phút!**
Anh Duy có thể tải bản APK mới nhất trong mục **[Releases](../../releases)**.
