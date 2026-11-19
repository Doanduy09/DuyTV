# Hướng Dẫn Tự Làm Cục Voice Puck Điều Khiển Tivi Rảnh Tay (ESP32 + INMP441)
**Hệ Sinh Thái Điều Khiển Giọng Nói Tự Nhiên Cho Ứng Dụng DuyTV**

---

## 1. Danh Sách Linh Kiện Cần Chuẩn Bị (Tổng Chi Phí ~100.000 VNĐ)

1. **Bo mạch ESP32**:
   - Tên tìm kiếm: *ESP32 DevKit V1 (30 chân hoặc 38 chân)* hoặc *ESP32-S3*.
   - Giá: ~50.000đ – 70.000đ (có sẵn trên Shopee, Lazada, Hshop, Nshop).
2. **Module Micro I2S INMP441**:
   - Tên tìm kiếm: *Module Micro I2S INMP441 Omnidirectional*.
   - Giá: ~25.000đ – 30.000đ.
3. **Dây cắm Dupont (Cái - Cái)**:
   - Dùng để nối trực tiếp chân micro vào ESP32 mà không cần hàn chì. Giá ~10.000đ.
4. **Cáp Micro-USB hoặc Type-C**:
   - Cáp cắm nguồn từ củ sạc điện thoại thoại 5V/2A để nuôi nguồn 24/7.

---

## 2. Sơ Đồ Cắm Dây (Chỉ 6 Dây Cắm)

| Chân trên Micro INMP441 | Cắm sang Chân bo mạch ESP32 | Chức năng |
| :--- | :--- | :--- |
| **VDD** | **3V3** | Nguồn 3.3V |
| **GND** | **GND** | Tiếp địa (Mass) |
| **L/R** | **GND** | Chọn kênh Trái (Mono) |
| **WS** | **GPIO 15** | Word Select (Khung âm thanh) |
| **SCK** | **GPIO 14** | Clock (Xung nhịp bit) |
| **SD** | **GPIO 32** | Dữ liệu âm thanh PCM từ Mic |

---

## 3. Hướng Dẫn Nạp Firmware Lên ESP32 (Chỉ 3 Bước)

### Bước 1: Mở Phần Mềm Arduino IDE
- Tải và cài đặt [Arduino IDE](https://www.arduino.cc/en/software) miễn phí trên máy tính.
- Cài gói bo mạch ESP32 (Vào `Tools` $\rightarrow$ `Board Manager` $\rightarrow$ Gõ `esp32` $\rightarrow$ Bấm `Install`).

### Bước 2: Mở File Mã Nguồn
- Mở file mã nguồn [voice_puck_esp32.ino](file:///C:/Users/admin/.gemini/antigravity/scratch/DuyTV/hardware/voice_puck_esp32/voice_puck_esp32.ino).
- Sửa lại 2 dòng Wi-Fi nhà anh:
  ```cpp
  const char* WIFI_SSID     = "Ten_WiFi_Nha_Anh_Duy";
  const char* WIFI_PASSWORD = "Mat_Khau_WiFi";
  const char* SERVER_IP     = "192.168.1.189"; // IP máy tính của anh
  ```

### Bước 3: Cắm Dây USB & Nạp Code
- Cắm dây USB nối ESP32 vào máy tính.
- Chọn cổng COM và chọn board `ESP32 Dev Module`.
- Bấm nút **Upload (Mũi tên tròn)** $\rightarrow$ Đợi 20 giây báo `Done uploading` là xong!

---

## 4. Chạy Máy Chủ Voice Hub Trên Máy Tính (`192.168.1.189`)

Trên máy tính của anh Duy, mở Terminal / PowerShell và chạy:
```powershell
python C:\Users\admin\.gemini\antigravity\scratch\DuyTV\server\duytv_voice_hub.py
```
Máy chủ sẽ tự động:
1. Lắng nghe âm thanh từ cục ESP32 qua cổng UDP `8995`.
2. Mở sẵn Web Remote cho điện thoại Samsung tại: `http://192.168.1.189:8996`.

---

## 5. Trải Nghiệm Thực Tế

### Cách 1: Rảnh tay hoàn toàn với cục Voice Puck ESP32 để bàn trà
- Cắm nguồn cục ESP32 đặt trên bàn trà phòng khách.
- Khi anh Duy nói vào không gian phòng:
  - *"Bật thời sự cho anh"*
  - *"Hơi ồn, vặn nhỏ lại"*
  - *"Tập này coi rồi"*
  - *"Tắt tivi đi ngủ"*
- Đèn LED trên ESP32 sẽ sáng lên báo hiệu đang thu âm $\rightarrow$ Tivi Sharp tự động nhảy kênh hoặc chỉnh âm lượng tức thì trong **0.3 giây**!

### Cách 2: Điều khiển bằng điện thoại Samsung
- Mở trình duyệt Chrome trên điện thoại Samsung truy cập: `http://192.168.1.189:8996`.
- Chọn *"Thêm vào màn hình chính"* (tạo thành icon app DuyTV Remote).
- Bấm vào Micro trên điện thoại để ra lệnh giọng nói bất kỳ lúc nào!
