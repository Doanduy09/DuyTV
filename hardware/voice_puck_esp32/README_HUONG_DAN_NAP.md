# Hướng Dẫn Nạp Firmware Cho Cục Voice Puck ESP32-C3 (HW-953AB) + Micro I2S INMP441
**Hệ Sinh Thái Điều Khiển Giọng Nói Tiếng Việt Tự Nhiên Rảnh Tay Cho DuyTV**

---

## 1. Thông Tin Bo Mạch & Micro

- **Bo mạch điều khiển**: **ESP32-C3 SuperMini** (Mã bo: `HW-953AB` / RISC-V 32-bit 160MHz / Cổng USB Type-C).
- **Module Micro**: **INMP441** (Micro kỹ thuật số I2S MEMS đa hướng, độ nhạy cao, chống ồn phòng).
- **Cơ chế hoạt động**:
  1. Micro INMP441 thu âm thanh 16kHz 16-bit PCM chất lượng cao.
  2. ESP32-C3 chạy thuật toán VAD tự động cân chỉnh độ ồn phòng (Auto Noise Calibration) và bộ đệm trượt 8 khung chống nuốt âm đầu ("Duy", "Bật", "Mở").
  3. Khi phát hiện giọng nói, đèn LED xanh (GPIO 8) trên bo HW-953AB sáng lên và truyền luồng âm thanh qua Wi-Fi (UDP 8995) về máy chủ DuyTV Voice Hub.
  4. Máy chủ chuyển hóa thành khẩu lệnh tiếng Việt $\rightarrow$ Bắn lệnh trực tiếp qua mạng LAN TCP (8990) để điều khiển **Tivi Sharp** (`192.168.1.172`) và **NVIDIA Shield TV** (`192.168.1.218`) chuyển kênh, mở phim hoặc chỉnh âm lượng tức thì trong **0.3 giây**!

---

## 2. Sơ Đồ Chân Hàn (Pinout HW-953AB ESP32-C3)

| Chân trên Micro INMP441 | Chân hàn trên Bo ESP32-C3 HW-953AB | Ghi chú kỹ thuật |
| :--- | :--- | :--- |
| **VDD** | **3.3V** (hoặc **3V3**) | Cấp nguồn 3.3V *(Tuyệt đối không nối vào 5V)* |
| **GND** | **GND** | Nối Mass chung |
| **L/R** | **GND** | Kênh Trái (Mono tiêu chuẩn) |
| **SCK** (hoặc BCLK) | **GPIO 5** *(hoặc chân anh đã hàn)* | Xung nhịp bit Clock I2S |
| **WS** (hoặc LRCLK) | **GPIO 4** *(hoặc chân anh đã hàn)* | Khung Word Select I2S |
| **SD** (hoặc DOUT) | **GPIO 6** *(hoặc chân anh đã hàn)* | Dữ liệu âm thanh số PCM |
| **Đèn LED Xanh** | **GPIO 8** *(Có sẵn trên bo)* | Tích cực mức LOW (Sáng khi đang thu âm) |

> 💡 **Mẹo**: Nếu anh Duy đã hàn các chân `SCK`, `WS`, `SD` vào các chân GPIO khác trên bo (ví dụ GPIO 0, 1, 2 hoặc GPIO 1, 2, 3), anh chỉ cần mở file [voice_puck_esp32.ino](file:///C:/Users/admin/.gemini/antigravity/scratch/DuyTV/hardware/voice_puck_esp32/voice_puck_esp32.ino) và sửa lại 3 số chân ở dòng 44–46 là xong!

---

## 3. Hướng Dẫn Cài Đặt Arduino IDE & Nạp Code (Chỉ 3 Bước)

### Bước 1: Chuẩn bị Arduino IDE
1. Tải và mở phần mềm [Arduino IDE](https://www.arduino.cc/en/software).
2. Vào `File` $\rightarrow$ `Preferences`, ở ô **Additional boards manager URLs**, dán đường link:
   ```text
   https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
   ```
3. Vào `Tools` $\rightarrow$ `Board` $\rightarrow$ `Boards Manager...`, gõ tìm `esp32` của Espressif Systems và bấm **Install**.

### Bước 2: Cài Đặt Thông Số Bo Mạch Trong Arduino IDE
Cắm cáp USB Type-C từ máy tính vào bo mạch **HW-953AB**, sau đó vào menu `Tools` chỉnh đúng các thông số:
- **Board**: Chọn `ESP32C3 Dev Module` (hoặc `ESP32-C3 SuperMini` nếu có).
- **USB CDC On Boot**: Chọn **`Enabled`** *(RẤT QUAN TRỌNG: để hiện thông báo trên Serial Monitor qua cổng Type-C)*.
- **CPU Frequency**: `160MHz (WiFi)`.
- **Flash Size**: `4MB (32Mb)`.
- **Flash Mode**: `QIO` hoặc `DIO`.
- **Upload Speed**: `921600` hoặc `115200`.
- **Port**: Chọn đúng cổng `COM...` của bo ESP32-C3.

> ⚠️ *Nếu máy tính không nhận cổng COM hoặc báo lỗi nạp:* Giữ nút **BOOT (IO9)** trên bo mạch $\rightarrow$ Cắm dây USB vào máy tính $\rightarrow$ Thả nút BOOT ra $\rightarrow$ Bấm Upload lại.

### Bước 3: Điền Thông Tin Wi-Fi & Nạp Firmware
Mở file [voice_puck_esp32.ino](file:///C:/Users/admin/.gemini/antigravity/scratch/DuyTV/hardware/voice_puck_esp32/voice_puck_esp32.ino):
1. Sửa lại tên & mật khẩu Wi-Fi nhà anh:
   ```cpp
   const char* WIFI_SSID       = "Ten_WiFi_Nha_Anh_Duy";
   const char* WIFI_PASSWORD   = "Mat_Khau_WiFi";
   const char* SERVER_IP       = "192.168.1.189";     // IP máy tính chạy server DuyTV
   ```
2. Bấm nút **Upload (Mũi tên tròn ➔)** ở góc trên bên trái Arduino IDE.
3. Chờ 20 giây báo `Done uploading` là hoàn tất!

---

## 4. Chạy Máy Chủ DuyTV Voice Hub

Trên máy tính (`192.168.1.189`), anh chỉ cần:
- Nhấp đúp chuột vào file: [run_voice_hub.bat](file:///C:/Users/admin/.gemini/antigravity/scratch/DuyTV/server/run_voice_hub.bat).
- Hoặc mở PowerShell gõ:
  ```powershell
  cd C:\Users\admin\.gemini\antigravity\scratch\DuyTV\server
  uv run python duytv_voice_hub.py
  ```

---

## 5. Danh Sách Câu Lệnh Giọng Nói Mẫu Để Thử Nghiệm

Đặt cục ESP32 trên bàn phòng khách, nói tự nhiên:
- 📺 **Truyền hình**:
  - *"Bật thời sự cho anh"* $\rightarrow$ Nhảy ngay sang VTV1 HD.
  - *"Bật THVL1"* $\rightarrow$ Mở đài Vĩnh Long 1.
  - *"Chuyển qua VTV3"* $\rightarrow$ Mở VTV3 HD.
- 🎬 **Phim ảnh**:
  - *"Bật Siêu nhân Tiga tập 5"* $\rightarrow$ Mở đúng tập 5 KKPhim.
  - *"Tập này coi rồi"* $\rightarrow$ Tự nhảy sang tập kế tiếp.
  - *"Phim dở quá đổi phim khác"* $\rightarrow$ Tự lùi về danh sách chọn phim.
- 🔊 **Âm lượng & Hệ thống**:
  - *"Hơi ồn, cho nhỏ lại"* $\rightarrow$ Giảm âm lượng.
  - *"Vặn to lên"* $\rightarrow$ Tăng âm lượng.
  - *"Tắt tivi đi ngủ"* $\rightarrow$ Tắt màn hình TV đưa vào chế độ nghỉ.
- 🎯 **Điều khiển chỉ định TV**:
  - *"Bật thời sự trên tivi Shield"* $\rightarrow$ Điều khiển riêng NVIDIA Shield TV.
  - *"Tắt tất cả tivi"* $\rightarrow$ Điều khiển đồng thời cả Tivi Sharp và Shield TV.
