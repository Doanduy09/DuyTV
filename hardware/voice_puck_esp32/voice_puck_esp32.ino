/**
 * ============================================================================
 * DUYTV VOICE PUCK - ESP32-C3 (HW-953AB) + MICRO I2S INMP441 FIRMWARE
 * Tác giả: DOANDUY (DuyTV Smart Home Ecosystem)
 * Thiết bị: Bo mạch ESP32-C3 SuperMini (Mã HW-953AB)
 * ============================================================================
 * SƠ ĐỒ ĐẤU DÂY:
 *   Micro INMP441        -->  Bo Mạch HW-953AB (ESP32-C3 SuperMini)
 *   ---------------------------------------------------------------
 *   VDD                  -->  3.3V
 *   GND                  -->  GND
 *   L/R                  -->  GND (Kênh Trái - Mono)
 *   SCK (Bit Clock)      -->  GPIO 5
 *   WS  (Word Select)    -->  GPIO 4
 *   SD  (Serial Data)    -->  GPIO 6
 *   LED Báo Trạng Thái   -->  GPIO 8 (Tích cực LOW: LOW = Sáng, HIGH = Tắt)
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

// 2. CẤU HÌNH CHÂN HÀN (PINOUT) CHO BO MẠCH HW-953AB (ESP32-C3)
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

// 4. THÔNG SỐ VAD ĐỘ NHẠY CAO CHO PHÒNG KHÁCH (FAR-FIELD VAD)
#define MIN_VAD_THRESHOLD   260   // Ngưỡng tối thiểu nhạy bén thu âm từ xa
#define VAD_MULTIPLIER      1.35f // Chỉ cần gấp 1.35 lần tiếng ồn phòng là kích hoạt
#define SILENCE_TIMEOUT_MS  900   // Chờ 900ms khoảng lặng sau câu nói
#define MAX_SPEECH_DURATION 8500  // Tối đa 8.5s cho 1 khẩu lệnh
#define PRE_BUFFER_CHUNKS   10    // Lưu trước ~160ms chống nuốt âm đầu ("Lâm", "Mở", "Bật")

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
        set_led(false);
        Serial.printf("\n[Wi-Fi] ✅ Kết nối thành công! IP: %s (RSSI: %d dBm)\n",
                      WiFi.localIP().toString().c_str(), WiFi.RSSI());
        Serial.printf("[DuyTV Hub] Gửi âm thanh UDP tới: %s:%d\n", SERVER_IP, SERVER_PORT);
        blink_led(3, 80);
    } else {
        Serial.println("\n[Wi-Fi] ❌ Kết nối thất bại. Sẽ thử lại...");
    }
}

int32_t calculate_rms(int16_t* samples, int count) {
    int64_t sum_squares = 0;
    for (int i = 0; i < count; i++) {
        sum_squares += (int32_t)samples[i] * samples[i];
    }
    return (int32_t)sqrt(sum_squares / count);
}

void calibrate_ambient_noise() {
    Serial.print("[VAD] Đang đo độ ồn phòng (1.2s)... ");
    int64_t total_rms = 0;
    int valid_frames = 0;
    unsigned long start_cal = millis();

    while (millis() - start_cal < 1200) {
        size_t bytes_read = 0;
        i2s_read(I2S_PORT, raw_i2s_buffer, sizeof(raw_i2s_buffer), &bytes_read, 100);
        if (bytes_read > 0) {
            int samples_count = bytes_read / sizeof(int32_t);
            for (int i = 0; i < samples_count; i++) {
                int32_t val = raw_i2s_buffer[i] >> 13;
                if (val > 32767) val = 32767;
                if (val < -32768) val = -32768;
                pcm_samples[i] = (int16_t)val;
            }
            total_rms += calculate_rms(pcm_samples, samples_count);
            valid_frames++;
        }
    }

    int32_t baseline = (valid_frames > 0) ? (total_rms / valid_frames) : 180;
    // Cân chỉnh độ nhạy: Chỉ cần gấp 1.35 lần độ ồn nền, tối thiểu 260
    dynamic_vad_threshold = (int32_t)(baseline * VAD_MULTIPLIER);
    if (dynamic_vad_threshold < MIN_VAD_THRESHOLD) dynamic_vad_threshold = MIN_VAD_THRESHOLD;
    if (dynamic_vad_threshold > 550) dynamic_vad_threshold = 550;

    Serial.printf("Xong!\n[VAD] Độ ồn: %d | Ngưỡng nhận diện: %d (Rất nhạy)\n", baseline, dynamic_vad_threshold);
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

    Serial.println("\n========================================================");
    Serial.println("  DUYTV VOICE PUCK - ESP32-C3 SUPERMINI (FAR-FIELD V2)  ");
    Serial.println("  100% TIẾNG VIỆT TỰ NHIÊN - ĐIỀU KHIỂN TIVI RẢNH TAY   ");
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
    esp_err_t err = i2s_read(I2S_PORT, raw_i2s_buffer, sizeof(raw_i2s_buffer), &bytes_read, portMAX_DELAY);
    if (err != ESP_OK || bytes_read == 0) return;

    int samples_count = bytes_read / sizeof(int32_t);

    // Chuẩn hóa 24-bit INMP441 sang 16-bit PCM kèm khuếch đại tự nhiên (+6dB)
    for (int i = 0; i < samples_count; i++) {
        int32_t val = raw_i2s_buffer[i] >> 13;
        if (val > 32767) val = 32767;
        if (val < -32768) val = -32768;
        pcm_samples[i] = (int16_t)val;
    }

    int32_t energy = calculate_rms(pcm_samples, samples_count);

    if (!is_speaking) {
        // Lưu vào bộ đệm trượt 160ms chống nuốt âm đầu
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

        bool is_silence = (now - last_sound_time > SILENCE_TIMEOUT_MS);
        bool is_max_len = (now - speech_start_time > MAX_SPEECH_DURATION);

        if (is_silence || is_max_len) {
            is_speaking = false;
            set_led(false);

            Serial.printf("[Voice Puck] ⏹️ Kết thúc thu âm (%lu ms) -> Gửi máy chủ!\n", now - speech_start_time);

            send_event_marker("__END__");
            blink_led(2, 50); // Nháy 2 lần xác nhận đã gửi
        }
    }
}
