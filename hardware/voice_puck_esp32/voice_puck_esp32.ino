/**
 * ============================================================================
 * DUYTV VOICE PUCK - ESP32-C3 (HW-953AB) + MICRO I2S INMP441 FIRMWARE V3
 * Tác giả: DOANDUY (DuyTV Smart Home Ecosystem)
 * Thiết bị: Bo mạch ESP32-C3 SuperMini (Mã HW-953AB)
 * ============================================================================
 * CẢI TIẾN V3:
 * - Tích hợp bộ lọc DC-Blocker IIR loại bỏ 100% nhiễu lệch mức một chiều của INMP441.
 * - Khuếch đại số +12dB cực nhạy bắt giọng nói thì thầm từ khoảng cách 2 - 4 mét.
 * - Cơ chế chống ngắt sớm (Minimum Speech Window 1.4s), bắt trọn vẹn toàn bộ câu lệnh.
 * - Pre-buffer 192ms chống nuốt âm đầu ("Lâm", "Mở", "Bật").
 * ============================================================================
 */

#include <WiFi.h>
#include <WiFiUdp.h>
#include <driver/i2s.h>
#include <math.h>

// 1. CẤU HÌNH MẠNG WI-FI & MÁY CHỦ DUYTV VOICE HUB
const char* WIFI_SSID_1     = "TPT Lam";
const char* WIFI_SSID_2     = "TPT Lam 5G";
const char* WIFI_PASSWORD   = "TPTlam011205@!";
const char* SERVER_IP       = "192.168.1.189";
const uint16_t SERVER_PORT  = 8995;

// 2. CẤU HÌNH CHÂN HÀN CHO BO HW-953AB (ESP32-C3 SUPERMINI)
#define PIN_I2S_SCK         5
#define PIN_I2S_WS          4
#define PIN_I2S_SD          6
#define PIN_STATUS_LED      8
#define LED_ACTIVE_STATE    LOW

// 3. THÔNG SỐ ÂM THANH (16kHz, 16-bit PCM Mono)
#define SAMPLE_RATE         16000
#define DMA_BUF_COUNT       4
#define DMA_BUF_LEN         512
#define CHUNK_SAMPLES       256
#define I2S_PORT            I2S_NUM_0

// 4. THÔNG SỐ VAD ĐỘ NHẠY CAO (SIÊU NHẠY THAY THẾ REMOTE MICRO)
#define MIN_VAD_THRESHOLD       85    // Ngưỡng tối thiểu siêu nhạy sau khi lọc sạch DC bias
#define VAD_MULTIPLIER          1.4f  // Vượt 1.4 lần nền phòng là kích hoạt
#define MIN_SPEECH_DURATION_MS  1400  // BẮT BUỘC thu tối thiểu 1.4s (chống ngắt ngang câu nói)
#define SILENCE_TIMEOUT_MS      850   // Khoảng lặng 850ms sau khi đã nói đủ tối thiểu 1.4s
#define MAX_SPEECH_DURATION_MS  7500  // Tối đa 7.5s cho 1 khẩu lệnh dài
#define PRE_BUFFER_CHUNKS       12    // 12 chunk x 16ms = ~192ms âm đệm chống nuốt từ đầu

WiFiUDP udpClient;

int32_t raw_i2s_buffer[CHUNK_SAMPLES];
int16_t pcm_samples[CHUNK_SAMPLES];
int16_t pre_buffer[PRE_BUFFER_CHUNKS][CHUNK_SAMPLES];
int pre_buf_index = 0;
bool pre_buf_full = false;

bool is_speaking = false;
unsigned long speech_start_time = 0;
unsigned long last_sound_time = 0;
unsigned long last_heartbeat = 0;
int32_t dynamic_vad_threshold = MIN_VAD_THRESHOLD;

// Bộ lọc DC-Blocker IIR 1st Order (Loại bỏ lệch DC của micro INMP441)
static int32_t dc_prev_x = 0;
static int32_t dc_prev_y = 0;

void set_led(bool turn_on) {
    digitalWrite(PIN_STATUS_LED, turn_on ? LED_ACTIVE_STATE : !LED_ACTIVE_STATE);
}

void blink_led(int count, int delay_ms) {
    for (int i = 0; i < count; i++) {
        set_led(true);
        delay(delay_ms);
        set_led(false);
        delay(delay_ms);
    }
}

void setup_i2s() {
    i2s_config_t i2s_config = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
        .sample_rate = SAMPLE_RATE,
        .bits_per_sample = I2S_BITS_PER_SAMPLE_32BIT,
        .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
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

    i2s_driver_install(I2S_PORT, &i2s_config, 0, NULL);
    i2s_set_pin(I2S_PORT, &pin_config);
    i2s_zero_dma_buffer(I2S_PORT);
    Serial.println("[I2S] Micro INMP441 đã sẵn sàng!");
}

void connect_wifi() {
    Serial.printf("\n[Wi-Fi] Đang kết nối tới: %s\n", WIFI_SSID_1);
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID_1, WIFI_PASSWORD);

    int attempts = 0;
    while (WiFi.status() != WL_CONNECTED && attempts < 24) {
        delay(250);
        set_led(attempts % 2 == 0);
        Serial.print(".");
        attempts++;
    }

    if (WiFi.status() != WL_CONNECTED) {
        Serial.printf("\n[Wi-Fi] Thử mạng dự phòng: %s\n", WIFI_SSID_2);
        WiFi.begin(WIFI_SSID_2, WIFI_PASSWORD);
        attempts = 0;
        while (WiFi.status() != WL_CONNECTED && attempts < 20) {
            delay(250);
            set_led(attempts % 2 == 0);
            Serial.print(".");
            attempts++;
        }
    }

    if (WiFi.status() == WL_CONNECTED) {
        Serial.printf("\n[Wi-Fi] ✅ Kết nối thành công! IP: %s (RSSI: %d dBm)\n",
                      WiFi.localIP().toString().c_str(), WiFi.RSSI());
        Serial.printf("[DuyTV Hub] Gửi âm thanh UDP tới: %s:%d\n", SERVER_IP, SERVER_PORT);
        blink_led(3, 80);
    } else {
        Serial.println("\n[Wi-Fi] ❌ Kết nối thất bại. Sẽ thử lại...");
    }
}

void process_i2s_samples(int samples_count) {
    for (int i = 0; i < samples_count; i++) {
        // INMP441: 24-bit MSB-aligned in 32-bit slot
        // Dịch >> 14 cho khuếch đại sạch +12dB (bắt tiếng xa cực nhạy mà không méo tiếng)
        int32_t x = raw_i2s_buffer[i] >> 14;

        // Bộ lọc DC-Blocker IIR: y[n] = x[n] - x[n-1] + 0.975 * y[n-1]
        int32_t y = x - dc_prev_x + (int32_t)((dc_prev_y * 998) >> 10);
        dc_prev_x = x;
        dc_prev_y = y;

        // Giới hạn biên độ 16-bit chuẩn
        if (y > 32767) y = 32767;
        if (y < -32768) y = -32768;
        pcm_samples[i] = (int16_t)y;
    }
}

int32_t calculate_rms(int16_t* samples, int count) {
    if (count <= 0) return 0;
    int64_t sum_squares = 0;
    for (int i = 0; i < count; i++) {
        sum_squares += (int32_t)samples[i] * samples[i];
    }
    return (int32_t)sqrt(sum_squares / count);
}

void calibrate_ambient_noise() {
    Serial.print("[VAD] Đang đo độ ồn phòng (1.0s)... ");
    int64_t total_rms = 0;
    int valid_frames = 0;
    unsigned long start_cal = millis();

    while (millis() - start_cal < 1000) {
        size_t bytes_read = 0;
        i2s_read(I2S_PORT, raw_i2s_buffer, sizeof(raw_i2s_buffer), &bytes_read, 50 / portTICK_PERIOD_MS);
        if (bytes_read > 0) {
            int samples_count = bytes_read / sizeof(int32_t);
            process_i2s_samples(samples_count);
            total_rms += calculate_rms(pcm_samples, samples_count);
            valid_frames++;
        }
    }

    int32_t baseline = (valid_frames > 0) ? (total_rms / valid_frames) : 35;
    // Ngưỡng VAD: Nhạy bén thu âm từ xa, chỉ cần vượt 1.4x độ ồn thực tế
    dynamic_vad_threshold = (int32_t)(baseline * VAD_MULTIPLIER);
    if (dynamic_vad_threshold < MIN_VAD_THRESHOLD) dynamic_vad_threshold = MIN_VAD_THRESHOLD;
    if (dynamic_vad_threshold > 350) dynamic_vad_threshold = 350;

    Serial.printf("Xong! Độ ồn nền: %d | Ngưỡng kích hoạt VAD: %d (Siêu nhạy)\n", baseline, dynamic_vad_threshold);
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
    delay(400);

    Serial.println("\n========================================================");
    Serial.println("  DUYTV VOICE PUCK - ESP32-C3 SUPERMINI (REMOTE MIC V3) ");
    Serial.println("  THAY THẾ MICRO REMOTE - ĐIỀU KHIỂN TIVI SIÊU NHẠY     ");
    Serial.println("========================================================");
    Serial.printf("[Chân] SCK: GPIO%d | WS: GPIO%d | SD: GPIO%d | LED: GPIO%d\n",
                  PIN_I2S_SCK, PIN_I2S_WS, PIN_I2S_SD, PIN_STATUS_LED);

    pinMode(PIN_STATUS_LED, OUTPUT);
    set_led(false);

    setup_i2s();
    connect_wifi();
    calibrate_ambient_noise();

    Serial.println(">>> SẴN SÀNG! Anh Duy nói tự nhiên: 'Lâm ơi mở đài Vĩnh Long' <<<\n");
}

void loop() {
    if (WiFi.status() != WL_CONNECTED) {
        connect_wifi();
        return;
    }

    // Heartbeat định kỳ 15s để server biết ESP32 luôn online
    unsigned long now = millis();
    if (now - last_heartbeat > 15000) {
        last_heartbeat = now;
        send_event_marker("__PING__");
    }

    size_t bytes_read = 0;
    esp_err_t err = i2s_read(I2S_PORT, raw_i2s_buffer, sizeof(raw_i2s_buffer), &bytes_read, 50 / portTICK_PERIOD_MS);
    if (err != ESP_OK || bytes_read == 0) return;

    int samples_count = bytes_read / sizeof(int32_t);
    process_i2s_samples(samples_count);

    int32_t energy = calculate_rms(pcm_samples, samples_count);

    if (!is_speaking) {
        // Lưu vào bộ đệm trượt 192ms chống nuốt âm đầu
        memcpy(pre_buffer[pre_buf_index], pcm_samples, sizeof(pcm_samples));
        pre_buf_index = (pre_buf_index + 1) % PRE_BUFFER_CHUNKS;
        if (pre_buf_index == 0) pre_buf_full = true;

        if (energy > dynamic_vad_threshold) {
            is_speaking = true;
            speech_start_time = now;
            last_sound_time = now;
            set_led(true); // BẬT ĐÈN LED XANH BÁO HIỆU ĐANG THU ÂM

            Serial.printf("[Voice Puck] 🎙️ Bắt đầu thu âm! (RMS: %d > %d)\n", energy, dynamic_vad_threshold);

            send_event_marker("__START__");

            // Bắn toàn bộ âm thanh trong bộ đệm trượt 192ms
            int count_to_flush = pre_buf_full ? PRE_BUFFER_CHUNKS : pre_buf_index;
            int start_idx = pre_buf_full ? pre_buf_index : 0;
            for (int k = 0; k < count_to_flush; k++) {
                int idx = (start_idx + k) % PRE_BUFFER_CHUNKS;
                send_udp_chunk((uint8_t*)pre_buffer[idx], sizeof(pre_buffer[idx]));
            }
            pre_buf_index = 0;
            pre_buf_full = false;

            send_udp_chunk((uint8_t*)pcm_samples, sizeof(pcm_samples));
        }
    } else {
        // Truyền liên tục các gói PCM âm thanh qua UDP
        send_udp_chunk((uint8_t*)pcm_samples, sizeof(pcm_samples));

        if (energy > dynamic_vad_threshold) {
            last_sound_time = now;
        }

        // CHỈ cho phép kết thúc sau khi đã thu tối thiểu 1.4s (MIN_SPEECH_DURATION_MS)
        bool has_min_duration = (now - speech_start_time >= MIN_SPEECH_DURATION_MS);
        bool is_silence = has_min_duration && (now - last_sound_time > SILENCE_TIMEOUT_MS);
        bool is_max_len = (now - speech_start_time > MAX_SPEECH_DURATION_MS);

        if (is_silence || is_max_len) {
            is_speaking = false;
            set_led(false);

            Serial.printf("[Voice Puck] ⏹️ Kết thúc thu âm (%lu ms) -> Gửi máy chủ!\n", now - speech_start_time);

            send_event_marker("__END__");
            blink_led(2, 40); // Nháy 2 lần xác nhận đã gửi
        }
    }
}
