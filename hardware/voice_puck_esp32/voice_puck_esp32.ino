/**
 * ============================================================================
 * DUYTV VOICE PUCK - ESP32-C3 (HW-953AB) + MICRO I2S INMP441 FIRMWARE
 * Tác giả: DOANDUY (DuyTV Smart Home Ecosystem)
 * Thiết bị: Bo mạch ESP32-C3 SuperMini (Mã HW-953AB) hoặc ESP32 DevKit V1
 * ============================================================================
 * 
 * 1. SƠ ĐỒ ĐẤU DÂY CHO BO MẠCH HW-953AB (ESP32-C3 SUPERMINI):
 *   Micro INMP441        -->  Bo Mạch HW-953AB (ESP32-C3)
 *   ------------------------------------------------------
 *   VDD                  -->  3.3V (hoặc 3V3)
 *   GND                  -->  GND (Tiếp địa)
 *   L/R                  -->  GND (Kênh Trái - Mono tiêu chuẩn)
 *   SCK (Bit Clock)      -->  GPIO 5 (hoặc chân anh đã hàn)
 *   WS  (Word Select)    -->  GPIO 4 (hoặc chân anh đã hàn)
 *   SD  (Serial Data)    -->  GPIO 6 (hoặc chân anh đã hàn)
 *   LED Báo Trạng Thái   -->  GPIO 8 (Tích hợp sẵn trên bo HW-953AB)
 * 
 * *LƯU Ý QUAN TRỌNG VỀ BO HW-953AB:
 * - Bo dùng chip ESP32-C3 (RISC-V 32-bit), nạp qua cổng USB Type-C.
 * - Đèn LED xanh trên bo HW-953AB nằm ở GPIO 8 và tích cực mức THẤP (LOW = Sáng, HIGH = Tắt).
 * - Nếu anh Duy hàn vào các chân khác (VD: GPIO 0, 1, 2 hoặc GPIO 1, 2, 3), 
 *   anh chỉ cần sửa lại số chân ở mục CẤU HÌNH CHÂN bên dưới là chạy ngay!
 * ============================================================================
 */

#include <WiFi.h>
#include <WiFiUdp.h>
#include <driver/i2s.h>
#include <math.h>

// ============================================================================
// 1. CẤU HÌNH MẠNG WI-FI & MÁY CHỦ DUYTV VOICE HUB
// ============================================================================
const char* WIFI_SSID       = "Duy_WiFi";          // Tên Wi-Fi nhà anh Duy
const char* WIFI_PASSWORD   = "mat_khau_wifi";     // Mật khẩu Wi-Fi
const char* SERVER_IP       = "192.168.1.189";     // IP máy tính chạy duytv_voice_hub.py
const uint16_t SERVER_PORT  = 8995;                // Cổng UDP nhận luồng âm thanh

// ============================================================================
// 2. CẤU HÌNH CHÂN HÀN (PINOUT) CHO BO MẠCH HW-953AB (ESP32-C3)
// ============================================================================
#if defined(CONFIG_IDF_TARGET_ESP32C3) || defined(ARDUINO_ESP32C3_DEV) || defined(ESP32C3)
    // Cấu hình chuẩn tối ưu cho ESP32-C3 HW-953AB SuperMini
    #define PIN_I2S_SCK         5     // Chân SCK (BCLK) của INMP441
    #define PIN_I2S_WS          4     // Chân WS (LRCLK) của INMP441
    #define PIN_I2S_SD          6     // Chân SD (DOUT) của INMP441
    #define PIN_STATUS_LED      8     // Đèn LED xanh tích hợp trên bo HW-953AB
    #define LED_ACTIVE_STATE    LOW   // HW-953AB: LOW = Sáng, HIGH = Tắt
    #define BOARD_NAME          "ESP32-C3 (HW-953AB)"
#else
    // Cấu hình dự phòng tự động nếu nạp vào ESP32 WROOM-32 truyền thống
    #define PIN_I2S_SCK         14    // BCLK
    #define PIN_I2S_WS          15    // LRCLK
    #define PIN_I2S_SD          32    // DOUT
    #define PIN_STATUS_LED      2     // LED tích hợp ESP32 thường
    #define LED_ACTIVE_STATE    HIGH  // ESP32 thường: HIGH = Sáng
    #define BOARD_NAME          "ESP32 WROOM-32"
#endif

// ============================================================================
// 3. THÔNG SỐ ÂM THANH CHUẨN PHÒNG THU (16kHz, 16-bit PCM Mono)
// ============================================================================
#define SAMPLE_RATE         16000     // 16kHz chuẩn cho Google STT & Gemini
#define DMA_BUF_COUNT       4         // Số lượng bộ đệm DMA
#define DMA_BUF_LEN         512       // Kích thước mỗi bộ đệm DMA
#define CHUNK_SAMPLES       256       // 256 mẫu = 16ms âm thanh mỗi gói UDP
#define I2S_PORT            I2S_NUM_0

// ============================================================================
// 4. THÔNG SỐ VAD (VOICE ACTIVITY DETECTION - NHẬN DIỆN GIỌNG NÓI TỰ ĐỘNG)
// ============================================================================
#define DEFAULT_VAD_THRESHOLD  850    // Ngưỡng năng lượng mặc định
#define SILENCE_TIMEOUT_MS     800    // Khoảng lặng (ms) sau khi nói xong để ngắt
#define MAX_SPEECH_DURATION   8500    // Giới hạn câu lệnh tối đa (8.5 giây)
#define PRE_BUFFER_CHUNKS        8    // Bộ đệm trước ~130ms chống nuốt âm đầu ("Duy", "Bật", "Mở")

// Khởi tạo đối tượng mạng UDP
WiFiUDP udpClient;

// Bộ đệm đọc âm thanh từ DMA
int32_t raw_i2s_buffer[CHUNK_SAMPLES];
int16_t pcm_samples[CHUNK_SAMPLES];

// Bộ đệm vòng (Ring Buffer) lưu âm thanh trước khi nhận diện tiếng nói
int16_t pre_buffer[PRE_BUFFER_CHUNKS][CHUNK_SAMPLES];
int pre_buf_index = 0;
bool pre_buf_full = false;

// Trạng thái giọng nói
bool is_speaking = false;
unsigned long speech_start_time = 0;
unsigned long last_sound_time = 0;
int32_t dynamic_vad_threshold = DEFAULT_VAD_THRESHOLD;

// Điều khiển đèn LED trạng thái
void set_led(bool turn_on) {
    if (turn_on) {
        digitalWrite(PIN_STATUS_LED, LED_ACTIVE_STATE);
    } else {
        digitalWrite(PIN_STATUS_LED, (LED_ACTIVE_STATE == LOW) ? HIGH : LOW);
    }
}

// Nháy đèn LED báo hiệu
void blink_led(int count, int delay_ms) {
    for (int i = 0; i < count; i++) {
        set_led(true);
        delay(delay_ms);
        set_led(false);
        delay(delay_ms);
    }
}

// ============================================================================
// KHỞI TẠO BUS I2S CHO MICRO INMP441
// ============================================================================
void setup_i2s() {
    i2s_config_t i2s_config = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
        .sample_rate = SAMPLE_RATE,
        .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT, // INMP441 truyền dữ liệu 24-bit trong khung 32-bit
        .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,  // Kênh trái (chân L/R nối GND)
        .communication_format = I2S_COMM_FORMAT_STAND_I2S,
        .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
        .dma_buf_count = DMA_BUF_COUNT,
        .dma_buf_len = DMA_BUF_LEN,
        .use_apll = false,
        .tx_desc_auto_clear = false,
        .fixed_mclk = 0
    };

    i2s_pin_config_t pin_config = {
        .bck_io_num = PIN_I2S_SCK,
        .ws_io_num = PIN_I2S_WS,
        .data_out_num = I2S_PIN_NO_CHANGE,
        .data_in_num = PIN_I2S_SD
    };

    esp_err_t err = i2s_driver_install(I2S_PORT, &i2s_config, 0, NULL);
    if (err != ESP_OK) {
        Serial.printf("[I2S] Lỗi cài đặt driver I2S: %d\n", err);
    }
    err = i2s_set_pin(I2S_PORT, &pin_config);
    if (err != ESP_OK) {
        Serial.printf("[I2S] Lỗi gán chân I2S: %d\n", err);
    }
    i2s_zero_dma_buffer(I2S_PORT);
    Serial.println("[I2S] Khởi tạo bus I2S micro INMP441 thành công!");
}

// ============================================================================
// KẾT NỐI WI-FI VỚI WATCHDOG TỰ ĐỘNG PHỤC HỒI
// ============================================================================
void connect_wifi() {
    Serial.printf("\n[Wi-Fi] Đang kết nối tới: %s\n", WIFI_SSID);
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED && attempts < 35) {
        delay(250);
        set_led(attempts % 2 == 0);
        Serial.print(".");
        attempts++;
    }

    if (WiFi.status() == WL_CONNECTED) {
        set_led(false);
        Serial.printf("\n[Wi-Fi] ✅ Kết nối thành công! IP: %s | RSSI: %d dBm\n",
                      WiFi.localIP().toString().c_str(), WiFi.RSSI());
        Serial.printf("[DuyTV Hub] Mục tiêu UDP gửi âm thanh: %s:%d\n", SERVER_IP, SERVER_PORT);
        blink_led(3, 80); // Nháy 3 lần báo sẵn sàng
    } else {
        Serial.println("\n[Wi-Fi] ❌ Không thể kết nối Wi-Fi. Sẽ thử lại ở vòng lặp sau...");
    }
}

// Tính năng lượng âm thanh RMS (Root Mean Square)
int32_t calculate_rms(int16_t* samples, int count) {
    int64_t sum_squares = 0;
    for (int i = 0; i < count; i++) {
        sum_squares += (int32_t)samples[i] * samples[i];
    }
    return (int32_t)sqrt(sum_squares / count);
}

// Tự động hiệu chỉnh ngưỡng tiếng ồn nền (Noise Floor Calibration)
void calibrate_ambient_noise() {
    Serial.print("[VAD] Đang đo độ ồn phòng để cân chỉnh độ nhạy micro (1.5s)... ");
    int64_t total_rms = 0;
    int valid_frames = 0;
    unsigned long start_cal = millis();

    while (millis() - start_cal < 1500) {
        size_t bytes_read = 0;
        i2s_read(I2S_PORT, raw_i2s_buffer, sizeof(raw_i2s_buffer), &bytes_read, 100);
        if (bytes_read > 0) {
            int samples_count = bytes_read / sizeof(int32_t);
            for (int i = 0; i < samples_count; i++) {
                pcm_samples[i] = (int16_t)(raw_i2s_buffer[i] >> 14);
            }
            total_rms += calculate_rms(pcm_samples, samples_count);
            valid_frames++;
        }
    }

    int32_t baseline = (valid_frames > 0) ? (total_rms / valid_frames) : 250;
    // Ngưỡng phát hiện = 2.4 lần tiếng ồn phòng (tối thiểu 650 để chống tạp âm)
    dynamic_vad_threshold = max((int32_t)650, (int32_t)(baseline * 2.4));
    Serial.printf("Xong!\n[VAD] Độ ồn phòng: %d | Ngưỡng nhận diện giọng nói: %d\n", baseline, dynamic_vad_threshold);
}

void send_udp_chunk(uint8_t* data, size_t length) {
    udpClient.beginPacket(SERVER_IP, SERVER_PORT);
    udpClient.write(data, length);
    udpClient.endPacket();
}

void send_event_marker(const char* marker) {
    udpClient.beginPacket(SERVER_IP, SERVER_PORT);
    udpClient.print(marker);
    udpClient.endPacket();
}

// ============================================================================
// HÀM KHỞI CHẠY CHÍNH (SETUP)
// ============================================================================
void setup() {
    Serial.begin(115200);
    delay(1000);

    Serial.println("\n========================================================");
    Serial.printf("  DUYTV VOICE PUCK - %s READY\n", BOARD_NAME);
    Serial.println("  100% TIẾNG VIỆT TỰ NHIÊN - ĐIỀU KHIỂN TIVI RẢNH TAY   ");
    Serial.println("========================================================");
    Serial.printf("[Cấu hình Chân] SCK: GPIO%d | WS: GPIO%d | SD: GPIO%d | LED: GPIO%d\n",
                  PIN_I2S_SCK, PIN_I2S_WS, PIN_I2S_SD, PIN_STATUS_LED);

    pinMode(PIN_STATUS_LED, OUTPUT);
    set_led(false);

    setup_i2s();
    connect_wifi();
    calibrate_ambient_noise();

    Serial.println(">>> SẴN SÀNG! Anh Duy chỉ cần nói tự nhiên vào không gian phòng khách: <<<");
    Serial.println("    - 'Bật thời sự cho anh'");
    Serial.println("    - 'Hơi ồn, vặn nhỏ lại'");
    Serial.println("    - 'Siêu nhân Tiga tập 5'");
    Serial.println("    - 'Tắt tivi đi ngủ'\n");
}

// ============================================================================
// VÒNG LẶP CHÍNH (LOOP)
// ============================================================================
void loop() {
    // 1. Tự động kết nối lại nếu rớt Wi-Fi
    if (WiFi.status() != WL_CONNECTED) {
        connect_wifi();
        return;
    }

    // 2. Đọc 256 mẫu âm thanh từ micro INMP441 qua bus I2S
    size_t bytes_read = 0;
    esp_err_t err = i2s_read(I2S_PORT, raw_i2s_buffer, sizeof(raw_i2s_buffer), &bytes_read, portMAX_DELAY);
    if (err != ESP_OK || bytes_read == 0) return;

    int samples_count = bytes_read / sizeof(int32_t);

    // 3. Chuẩn hóa 24-bit từ INMP441 sang 16-bit PCM chuẩn
    for (int i = 0; i < samples_count; i++) {
        pcm_samples[i] = (int16_t)(raw_i2s_buffer[i] >> 14);
    }

    // 4. Đo cường độ âm thanh tức thời (RMS)
    int32_t energy = calculate_rms(pcm_samples, samples_count);
    unsigned long now = millis();

    // 5. Máy trạng thái VAD (Voice Activity Detection)
    if (!is_speaking) {
        // Lưu mẫu vào bộ đệm xoay vòng để chống mất chữ đầu ("Duy", "Bật", "Mở")
        memcpy(pre_buffer[pre_buf_index], pcm_samples, sizeof(pcm_samples));
        pre_buf_index = (pre_buf_index + 1) % PRE_BUFFER_CHUNKS;
        if (pre_buf_index == 0) pre_buf_full = true;

        // Nếu năng lượng vượt ngưỡng -> Bắt đầu nói!
        if (energy > dynamic_vad_threshold) {
            is_speaking = true;
            speech_start_time = now;
            last_sound_time = now;
            set_led(true); // BẬT ĐÈN LED BÁO HIỆU ĐANG LẮNG NGHE

            Serial.printf("[Voice Puck] 🎙️ Bắt đầu câu lệnh! (Energy: %d > Ngưỡng: %d) -> Truyền UDP...\n",
                          energy, dynamic_vad_threshold);

            // Bắn tín hiệu bắt đầu câu lệnh
            send_event_marker("__START__");

            // Bắn toàn bộ âm thanh trong bộ đệm trượt (chống nuốt âm đầu)
            int count_to_flush = pre_buf_full ? PRE_BUFFER_CHUNKS : pre_buf_index;
            int start_idx = pre_buf_full ? pre_buf_index : 0;
            for (int k = 0; k < count_to_flush; k++) {
                int idx = (start_idx + k) % PRE_BUFFER_CHUNKS;
                send_udp_chunk((uint8_t*)pre_buffer[idx], sizeof(pre_buffer[idx]));
            }
            pre_buf_index = 0;
            pre_buf_full = false;

            // Bắn tiếp khối âm thanh hiện tại
            send_udp_chunk((uint8_t*)pcm_samples, sizeof(pcm_samples));
        }
    } else {
        // Đang trong quá trình nói: Truyền liên tục các gói PCM qua Wi-Fi
        send_udp_chunk((uint8_t*)pcm_samples, sizeof(pcm_samples));

        if (energy > dynamic_vad_threshold) {
            last_sound_time = now; // Cập nhật thời điểm có tiếng nói gần nhất
        }

        // Kiểm tra kết thúc câu lệnh (khoảng lặng > 800ms hoặc nói quá 8.5 giây)
        bool is_silence_timeout = (now - last_sound_time > SILENCE_TIMEOUT_MS);
        bool is_max_duration    = (now - speech_start_time > MAX_SPEECH_DURATION);

        if (is_silence_timeout || is_max_duration) {
            is_speaking = false;
            set_led(false); // Tắt đèn lắng nghe

            Serial.printf("[Voice Puck] ⏹️ Kết thúc câu lệnh (%lu ms) -> Đang gửi AI giải mã và điều khiển TV!\n",
                          now - speech_start_time);

            // Bắn tín hiệu kết thúc câu lệnh để Server ném vào STT + Gemini AI
            send_event_marker("__END__");

            // Nháy 2 lần xác nhận đã gửi lệnh thành công
            blink_led(2, 60);
        }
    }
}
