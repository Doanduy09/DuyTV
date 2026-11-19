/**
 * ============================================================================
 * DUYTV VOICE PUCK - ESP32 + INMP441 I2S FIRMWARE (C++)
 * Tác giả: DOANDUY (DuyTV Smart Home Ecosystem)
 * Thiết bị: ESP32 DevKit V1 (ESP-WROOM-32) hoặc ESP32-S3 + Micro I2S INMP441
 * ============================================================================
 * 
 * SƠ ĐỒ ĐẤU DÂY (WIRING PINOUT):
 *   INMP441  -->  ESP32 (GPIO)
 *   --------------------------
 *   VDD      -->  3V3 (Nguồn 3.3V)
 *   GND      -->  GND (Tiếp địa)
 *   L/R      -->  GND (Kênh Trái - Mono)
 *   WS       -->  GPIO 15 (Word Select / LRCLK)
 *   SCK      -->  GPIO 14 (Bit Clock / BCLK)
 *   SD       -->  GPIO 32 (Serial Data Output từ Mic)
 * ============================================================================
 */

#include <WiFi.h>
#include <WiFiUdp.h>
#include <driver/i2s.h>
#include <math.h>

// 1. CẤU HÌNH MẠNG WI-FI & MÁY CHỦ DUYTV AI
const char* WIFI_SSID     = "Duy_WiFi";          // Thay bằng tên Wi-Fi nhà anh Duy
const char* WIFI_PASSWORD = "mat_khau_wifi";     // Thay bằng mật khẩu Wi-Fi
const char* SERVER_IP     = "192.168.1.189";     // Địa chỉ IP máy tính AI Pool của anh Duy
const uint16_t SERVER_PORT= 8995;                // Cổng UDP nhận luồng âm thanh

// 2. CẤU HÌNH CHÂN I2S MICRO INMP441 & ĐÈN BÁO
#define PIN_I2S_BCLK      14
#define PIN_I2S_WS        15
#define PIN_I2S_DATA_IN   32
#define PIN_STATUS_LED    2    // Đèn LED tích hợp trên ESP32 (Sáng khi đang thu giọng nói)

// 3. THÔNG SỐ ÂM THANH CHUẨN PHÒNG THU (16kHz, 16-bit PCM Mono)
#define SAMPLE_RATE       16000
#define DMA_BUF_COUNT     8
#define DMA_BUF_LEN       512
#define CHUNK_SAMPLES     256
#define I2S_PORT          I2S_NUM_0

// 4. THÔNG SỐ VAD (VOICE ACTIVITY DETECTION - NHẬN DIỆN TIẾNG NÓI)
#define VAD_ENERGY_THRESHOLD 850   // Ngưỡng năng lượng âm thanh bắt đầu nói (800 - 1500)
#define SILENCE_TIMEOUT_MS   800   // Khoảng lặng (ms) sau khi nói xong để ngắt và gửi lệnh
#define MAX_SPEECH_DURATION  9000  // Thời lượng tối đa 1 câu lệnh (9 giây)
#define PRE_BUFFER_CHUNKS    6     // Bộ đệm trước 300ms chống mất chữ đầu ("Duy ơi", "Bật")

// Khởi tạo đối tượng UDP
WiFiUDP udpClient;

// Bộ đệm đọc âm thanh từ DMA
int32_t raw_i2s_buffer[CHUNK_SAMPLES];
int16_t pcm_samples[CHUNK_SAMPLES];

// Bộ đệm trượt lưu âm thanh trước khi nói (Pre-speech buffer)
int16_t pre_buffer[PRE_BUFFER_CHUNKS][CHUNK_SAMPLES];
int pre_buf_index = 0;
bool pre_buf_full = false;

// Trạng thái thu âm
bool is_speaking = false;
unsigned long speech_start_time = 0;
unsigned long last_sound_time = 0;

void setup_i2s() {
    i2s_config_t i2s_config = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
        .sample_rate = SAMPLE_RATE,
        .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT, // INMP441 xuất dữ liệu trong khung 32-bit
        .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
        .communication_format = I2S_COMM_FORMAT_STAND_I2S,
        .intr_alloc_flags = ESP_INTR_FLAG_LEVEL1,
        .dma_buf_count = DMA_BUF_COUNT,
        .dma_buf_len = DMA_BUF_LEN,
        .use_apll = false
    };

    i2s_pin_config_t pin_config = {
        .bck_io_num = PIN_I2S_BCLK,
        .ws_io_num = PIN_I2S_WS,
        .data_out_num = I2S_PIN_NO_CHANGE,
        .data_in_num = PIN_I2S_DATA_IN
    };

    i2s_driver_install(I2S_PORT, &i2s_config, 0, NULL);
    i2s_set_pin(I2S_PORT, &pin_config);
    i2s_zero_dma_buffer(I2S_PORT);
}

void connect_wifi() {
    Serial.printf("[Wi-Fi] Đang kết nối tới: %s\n", WIFI_SSID);
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED && attempts < 40) {
        delay(300);
        digitalWrite(PIN_STATUS_LED, !digitalRead(PIN_STATUS_LED));
        Serial.print(".");
        attempts++;
    }

    if (WiFi.status() == WL_CONNECTED) {
        digitalWrite(PIN_STATUS_LED, LOW);
        Serial.printf("\n[Wi-Fi] Đã kết nối thành công! IP ESP32: %s\n", WiFi.localIP().toString().c_str());
        Serial.printf("[DuyTV Hub] Mục tiêu gửi âm thanh: %s:%d\n", SERVER_IP, SERVER_PORT);
    } else {
        Serial.println("\n[Wi-Fi] Lỗi: Không thể kết nối Wi-Fi, kiểm tra SSID/Pass!");
    }
}

// Tính toán năng lượng âm thanh RMS (Root Mean Square) để phát hiện tiếng người nói
int32_t calculate_rms(int16_t* samples, int count) {
    int64_t sum_squares = 0;
    for (int i = 0; i < count; i++) {
        sum_squares += (int32_t)samples[i] * samples[i];
    }
    return (int32_t)sqrt(sum_squares / count);
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

void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println("\n=============================================");
    Serial.println("  DUYTV VOICE PUCK (ESP32 + INMP441) READY   ");
    Serial.println("  100% TIẾNG VIỆT TỰ NHIÊN - ĐIỀU KHIỂN TV   ");
    Serial.println("=============================================");

    pinMode(PIN_STATUS_LED, OUTPUT);
    digitalWrite(PIN_STATUS_LED, LOW);

    setup_i2s();
    connect_wifi();
}

void loop() {
    // 1. Nếu rớt mạng Wi-Fi, tự động kết nối lại
    if (WiFi.status() != WL_CONNECTED) {
        connect_wifi();
        return;
    }

    // 2. Đọc 256 mẫu âm thanh thô từ micro INMP441 qua bus I2S
    size_t bytes_read = 0;
    esp_err_t err = i2s_read(I2S_PORT, raw_i2s_buffer, sizeof(raw_i2s_buffer), &bytes_read, portMAX_DELAY);
    if (err != ESP_OK || bytes_read == 0) return;

    int samples_count = bytes_read / sizeof(int32_t);

    // 3. Dịch 24-bit từ INMP441 sang 16-bit PCM chuẩn
    for (int i = 0; i < samples_count; i++) {
        // INMP441 đặt bit dữ liệu cao nhất ở bit 31, dịch sang phải 14 bit để được độ nhạy tốt nhất
        pcm_samples[i] = (int16_t)(raw_i2s_buffer[i] >> 14);
    }

    // 4. Đo cường độ âm thanh
    int32_t energy = calculate_rms(pcm_samples, samples_count);
    unsigned long now = millis();

    // 5. Máy trạng thái VAD nhận diện giọng nói
    if (!is_speaking) {
        // Lưu mẫu vào bộ đệm xoay vòng để không mất âm đầu
        memcpy(pre_buffer[pre_buf_index], pcm_samples, sizeof(pcm_samples));
        pre_buf_index = (pre_buf_index + 1) % PRE_BUFFER_CHUNKS;
        if (pre_buf_index == 0) pre_buf_full = true;

        // Nếu năng lượng vượt ngưỡng -> Bắt đầu nói!
        if (energy > VAD_ENERGY_THRESHOLD) {
            is_speaking = true;
            speech_start_time = now;
            last_sound_time = now;
            digitalWrite(PIN_STATUS_LED, HIGH); // Bật đèn LED báo hiệu tivi đang lắng nghe
            Serial.printf("[Voice Puck] Phát hiện giọng nói! Energy: %d -> Bắt đầu truyền...\n", energy);

            // Bắn tín hiệu bắt đầu câu lệnh
            send_event_marker("__START__");

            // Bắn toàn bộ 300ms âm thanh trong bộ đệm trượt để không mất chữ đầu
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
        // Đang trong quá trình nói: truyền liên tục các gói PCM qua Wi-Fi
        send_udp_chunk((uint8_t*)pcm_samples, sizeof(pcm_samples));

        if (energy > VAD_ENERGY_THRESHOLD) {
            last_sound_time = now; // Cập nhật thời điểm phát ra tiếng nói gần nhất
        }

        // Kiểm tra xem đã nói xong chưa (khoảng lặng > 800ms hoặc nói quá 9s)
        bool is_silence_timeout = (now - last_sound_time > SILENCE_TIMEOUT_MS);
        bool is_max_duration = (now - speech_start_time > MAX_SPEECH_DURATION);

        if (is_silence_timeout || is_max_duration) {
            is_speaking = false;
            digitalWrite(PIN_STATUS_LED, LOW); // Tắt đèn LED
            Serial.printf("[Voice Puck] Kết thúc câu lệnh! Thời lượng: %lu ms -> Đang gửi AI giải mã...\n", now - speech_start_time);

            // Bắn tín hiệu kết thúc câu lệnh để Server ném vào Gemini AI
            send_event_marker("__END__");
        }
    }
}
