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
#include <driver/i2s_std.h>
#include <math.h>

// 1. CẤU HÌNH MẠNG WI-FI & MÁY CHỦ DUYTV VOICE HUB
const char* WIFI_SSID_1     = "TPT Lam";
const char* WIFI_SSID_2     = "TPT Lam 5G";
const char* WIFI_PASSWORD   = "TPTlam011205@!";
const char* SERVER_IP       = "192.168.1.189";
const uint16_t SERVER_PORT  = 8995;

// 2. CẤU HÌNH CHÂN HÀN CHO BO MỚI ANH DUY (ESP32-C3 SUPERMINI)
int pin_i2s_sck = 8;  // Chân 8 nối SCK micro
int pin_i2s_ws  = 7;  // Chân 7 nối WS micro
int pin_i2s_sd  = 4;  // Chân 4 nối SD micro
#define PIN_STATUS_LED      -1 // Tắt LED vì GPIO 8 đã dùng cho xung nhịp SCK của micro!
#define LED_ACTIVE_STATE    LOW

// 3. THÔNG SỐ ÂM THANH (16kHz, 16-bit PCM Mono)
#define SAMPLE_RATE         16000
#define DMA_BUF_COUNT       4
#define DMA_BUF_LEN         512
#define CHUNK_SAMPLES       256
#define I2S_PORT            I2S_NUM_0

// 4. THÔNG SỐ VAD ĐỘ NHẠY CAO (SIÊU NHẠY THAY THẾ REMOTE MICRO - KHOẢNG CÁCH 2M-3M)
#define MIN_SPEECH_DURATION_MS  1100  // BẮT BUỘC thu tối thiểu 1.1s (chống ngắt ngang câu nói)
#define SILENCE_TIMEOUT_MS      750   // Khoảng lặng 750ms sau khi nói xong để chốt câu lệnh
#define MAX_SPEECH_DURATION_MS  7000  // Tối đa 7.0s cho 1 khẩu lệnh dài
#define PRE_BUFFER_CHUNKS       12    // 12 chunk x 16ms = ~192ms âm đệm chống nuốt từ đầu

WiFiUDP udpClient;

int32_t raw_i2s_buffer[CHUNK_SAMPLES * 2]; // Hỗ trợ Stereo (Left + Right)
int16_t pcm_samples[CHUNK_SAMPLES];
int16_t pre_buffer[PRE_BUFFER_CHUNKS][CHUNK_SAMPLES];
int pre_buf_index = 0;
bool pre_buf_full = false;

bool is_speaking = false;
unsigned long speech_start_time = 0;
unsigned long last_sound_time = 0;
unsigned long speech_cooldown_until = 0;
unsigned long last_heartbeat = 0;
unsigned long last_diag_log = 0;
int32_t baseline_rms = 100;
int32_t vad_threshold_start = 350;
int32_t vad_threshold_continue = 220;
static int active_channel = 0; // Chân L/R micro đã nối GND => Khóa cứng kênh LEFT (0)

// Bộ lọc High-Pass IIR 1st Order (~90Hz cutoff tại 16kHz) loại bỏ triệt để ù nền & DC bias
static float hp_prev_x = 0;
static float hp_prev_y = 0;
const float hp_alpha = 0.965f;

void set_led(bool turn_on) {
    if (PIN_STATUS_LED >= 0) {
        digitalWrite(PIN_STATUS_LED, turn_on ? LED_ACTIVE_STATE : !LED_ACTIVE_STATE);
    }
}

void blink_led(int count, int delay_ms) {
    if (PIN_STATUS_LED < 0) return;
    for (int i = 0; i < count; i++) {
        set_led(true);
        delay(delay_ms);
        set_led(false);
        delay(delay_ms);
    }
}

struct PinCandidate {
    int sck;
    int ws;
    int sd;
    const char* desc;
};

static const PinCandidate pin_candidates[] = {
    {8, 7, 4, "SCK=8, WS=7, SD=4 (Cấu hình anh Duy chỉ định)"},
    {7, 8, 4, "SCK=7, WS=8, SD=4 (Hoán đổi SCK/WS)"},
    {8, 4, 7, "SCK=8, WS=4, SD=7"},
    {7, 4, 8, "SCK=7, WS=4, SD=8"},
    {4, 7, 8, "SCK=4, WS=7, SD=8"},
    {4, 8, 7, "SCK=4, WS=8, SD=7"},
    {7, 6, 4, "SCK=7, WS=6, SD=4"},
    {6, 7, 4, "SCK=6, WS=7, SD=4"},
    {5, 4, 6, "SCK=5, WS=4, SD=6"}
};

static i2s_chan_handle_t rx_chan = NULL;

bool init_i2s(int sck, int ws, int sd) {
    if (rx_chan != NULL) {
        i2s_channel_disable(rx_chan);
        i2s_del_channel(rx_chan);
        rx_chan = NULL;
    }

    i2s_chan_config_t chan_cfg = I2S_CHANNEL_DEFAULT_CONFIG(I2S_NUM_0, I2S_ROLE_MASTER);
    chan_cfg.dma_desc_num = DMA_BUF_COUNT;
    chan_cfg.dma_frame_num = DMA_BUF_LEN;

    esp_err_t ret = i2s_new_channel(&chan_cfg, NULL, &rx_chan);
    if (ret != ESP_OK) {
        Serial.printf("[I2S Error] i2s_new_channel thất bại: %s\n", esp_err_to_name(ret));
        return false;
    }

    i2s_std_config_t std_cfg = {
        .clk_cfg = I2S_STD_CLK_DEFAULT_CONFIG(SAMPLE_RATE),
        .slot_cfg = I2S_STD_PHILIPS_SLOT_DEFAULT_CONFIG(I2S_DATA_BIT_WIDTH_32BIT, I2S_SLOT_MODE_STEREO),
        .gpio_cfg = {
            .mclk = I2S_GPIO_UNUSED,
            .bclk = (gpio_num_t)sck,
            .ws   = (gpio_num_t)ws,
            .dout = I2S_GPIO_UNUSED,
            .din  = (gpio_num_t)sd,
            .invert_flags = {
                .mclk_inv = false,
                .bclk_inv = false,
                .ws_inv = false,
            },
        },
    };
    std_cfg.slot_cfg.slot_bit_width = I2S_SLOT_BIT_WIDTH_32BIT;
    std_cfg.slot_cfg.slot_mask = I2S_STD_SLOT_BOTH;
    std_cfg.slot_cfg.left_align = false;
    std_cfg.slot_cfg.bit_shift = true;

    ret = i2s_channel_init_std_mode(rx_chan, &std_cfg);
    if (ret != ESP_OK) {
        Serial.printf("[I2S Error] i2s_channel_init_std_mode thất bại: %s\n", esp_err_to_name(ret));
        i2s_del_channel(rx_chan);
        rx_chan = NULL;
        return false;
    }

    ret = i2s_channel_enable(rx_chan);
    if (ret != ESP_OK) {
        Serial.printf("[I2S Error] i2s_channel_enable thất bại: %s\n", esp_err_to_name(ret));
        i2s_del_channel(rx_chan);
        rx_chan = NULL;
        return false;
    }

    // Đặt chân SD ở chế độ Floating chuẩn I2S (không kéo trở để micro lái bus tự nhiên)
    gpio_set_pull_mode((gpio_num_t)sd, GPIO_FLOATING);

    return true;
}

int32_t probe_pins(int sck, int ws, int sd) {
    if (!init_i2s(sck, ws, sd)) return 0;
    delay(50);

    int32_t buf[128];
    size_t br = 0;
    for (int t = 0; t < 3; t++) {
        i2s_channel_read(rx_chan, buf, sizeof(buf), &br, 50);
    }

    int32_t min_val = INT32_MAX;
    int32_t max_val = INT32_MIN;
    int64_t sum_jitter = 0;
    int total_samples = br / sizeof(int32_t);

    for (int i = 0; i < total_samples; i++) {
        int32_t s = buf[i];
        if (s < min_val) min_val = s;
        if (s > max_val) max_val = s;
        if (i > 0) sum_jitter += abs(buf[i] - buf[i - 1]);
    }

    int32_t peak_to_peak = (total_samples > 0 && max_val >= min_val) ? (max_val - min_val) : 0;
    int32_t avg_jitter = (total_samples > 1) ? (int32_t)(sum_jitter / (total_samples - 1)) : 0;

    i2s_channel_disable(rx_chan);
    i2s_del_channel(rx_chan);
    rx_chan = NULL;

    Serial.printf("[P-P: %ld | Jitter: %ld | V0: 0x%08lX] ",
                  (long)peak_to_peak, (long)avg_jitter, (unsigned long)buf[0]);

    // Lọc bỏ hoàn toàn nhiễu treo chân (0x00000001 / 0xFFFFFFFE chỉ có P-P <= 3)
    if (peak_to_peak > 500 && avg_jitter > 50) {
        return peak_to_peak;
    }
    return 0;
}

void auto_detect_pins() {
    Serial.println("[Auto-Pin] 🔍 Bắt đầu quét toàn bộ 18 cấu hình chân micro INMP441...");
    int total_cands = sizeof(pin_candidates) / sizeof(pin_candidates[0]);
    int best_cand = -1;
    int32_t max_pp = 0;

    for (int i = 0; i < total_cands; i++) {
        const auto& cand = pin_candidates[i];
        Serial.printf("  [%d/%d] %s => ", i + 1, total_cands, cand.desc);
        int32_t pp = probe_pins(cand.sck, cand.ws, cand.sd);
        if (pp > 0) {
            Serial.printf(">>> ✅ CÓ TÍN HIỆU ÂM HỌC! (Biên độ P-P: %ld)\n", (long)pp);
            if (pp > max_pp) {
                max_pp = pp;
                best_cand = i;
            }
        } else {
            Serial.println("Không có tín hiệu.");
        }
    }
    if (best_cand != -1) {
        pin_i2s_sck = pin_candidates[best_cand].sck;
        pin_i2s_ws  = pin_candidates[best_cand].ws;
        pin_i2s_sd  = pin_candidates[best_cand].sd;
        Serial.printf("\n[Auto-Pin] 🎯 Khóa chân có tín hiệu mạnh nhất: %s (P-P: %ld)\n\n",
                      pin_candidates[best_cand].desc, (long)max_pp);
    } else {
        Serial.println("\n[Auto-Pin] ⚠️ CẢNH BÁO: Toàn bộ các chân đều không có sóng âm!");
        Serial.println("[Auto-Pin] ⚠️ Micro chưa gửi dữ liệu âm thanh (Kiểm tra chân hàn VDD 3.3V, GND, L/R->GND, SD, WS, SCK)");
    }
}

void setup_i2s() {
    bool ok = init_i2s(pin_i2s_sck, pin_i2s_ws, pin_i2s_sd);
    delay(200);
    Serial.printf("[I2S Init] (SCK:%d, WS:%d, SD:%d) => %s\n",
                  pin_i2s_sck, pin_i2s_ws, pin_i2s_sd, ok ? "HOÀN TẤT THÀNH CÔNG" : "THẤT BẠI");
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

void process_i2s_samples(int frames_count) {
    if (active_channel == -1) {
        int64_t sum_l = 0;
        int64_t sum_r = 0;
        for (int i = 0; i < frames_count; i++) {
            sum_l += abs(raw_i2s_buffer[2 * i] >> 14);
            sum_r += abs(raw_i2s_buffer[2 * i + 1] >> 14);
        }
        if (sum_l > 40 || sum_r > 40) {
            active_channel = (sum_r > sum_l) ? 1 : 0;
            Serial.printf("[I2S Auto-Select] 🎯 Đã khóa kênh micro: %s (L:%lld, R:%lld)\n",
                          active_channel == 1 ? "PHẢI (RIGHT)" : "TRÁI (LEFT)", (long long)sum_l, (long long)sum_r);
        }
    }

    int ch_offset = (active_channel == 1) ? 1 : 0;
    for (int i = 0; i < frames_count; i++) {
        int32_t x;
        if (active_channel != -1) {
            x = raw_i2s_buffer[2 * i + ch_offset] >> 14;
        } else {
            int32_t xl = raw_i2s_buffer[2 * i] >> 14;
            int32_t xr = raw_i2s_buffer[2 * i + 1] >> 14;
            x = (abs(xr) > abs(xl)) ? xr : xl;
        }

        // Bộ lọc High-Pass IIR (~90Hz cutoff tại 16kHz) loại bỏ 100% tiếng ù và DC bias
        float y = hp_alpha * (hp_prev_y + (float)x - hp_prev_x);
        hp_prev_x = (float)x;
        hp_prev_y = y;

        // Giới hạn biên độ 16-bit chuẩn
        if (y > 32767.0f) y = 32767.0f;
        if (y < -32768.0f) y = -32768.0f;
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
    Serial.print("[VAD] Đang đo độ ồn phòng và hiệu chuẩn VAD (1.2s)... ");
    int64_t total_rms = 0;
    int valid_frames = 0;
    unsigned long start_cal = millis();

    while (millis() - start_cal < 1200) {
        size_t bytes_read = 0;
        i2s_channel_read(rx_chan, raw_i2s_buffer, sizeof(raw_i2s_buffer), &bytes_read, 50);
        if (bytes_read > 0) {
            int frames_count = bytes_read / (sizeof(int32_t) * 2);
            process_i2s_samples(frames_count);
            total_rms += calculate_rms(pcm_samples, frames_count);
            valid_frames++;
        }
    }

    baseline_rms = (valid_frames > 0) ? (int32_t)(total_rms / valid_frames) : 80;
    if (baseline_rms < 30) baseline_rms = 30;

    // Ngưỡng bắt đầu thu âm: Vượt 2.0x nền phòng + 150 (Tối thiểu 250)
    vad_threshold_start = (int32_t)(baseline_rms * 2.0f + 150);
    if (vad_threshold_start < 250) vad_threshold_start = 250;

    // Ngưỡng duy trì câu nói (chống ngắt ngang khi nói nhỏ cuối câu): 1.3x nền phòng + 60
    vad_threshold_continue = (int32_t)(baseline_rms * 1.3f + 60);
    if (vad_threshold_continue < 160) vad_threshold_continue = 160;

    Serial.printf("Xong! Nền phòng RMS: %ld | Ngưỡng Bắt đầu: %ld | Ngưỡng Duy trì: %ld (Kênh: %s)\n",
                  (long)baseline_rms, (long)vad_threshold_start, (long)vad_threshold_continue,
                  (active_channel == 1) ? "RIGHT" : (active_channel == 0 ? "LEFT" : "DÒ ĐỘNG"));
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
    Serial.println("  DUYTV VOICE PUCK - ESP32-C3 SUPERMINI (REMOTE MIC V4) ");
    Serial.println("  THAY THẾ MICRO REMOTE - ĐIỀU KHIỂN TIVI SIÊU NHẠY     ");
    Serial.println("========================================================");
    Serial.printf("[Chân Đã Khóa] SCK: GPIO%d | WS: GPIO%d | SD: GPIO%d | LED: GPIO%d\n",
                  pin_i2s_sck, pin_i2s_ws, pin_i2s_sd, PIN_STATUS_LED);

    if (PIN_STATUS_LED >= 0) {
        pinMode(PIN_STATUS_LED, OUTPUT);
        set_led(false);
    }

    auto_detect_pins();
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
    esp_err_t err = i2s_channel_read(rx_chan, raw_i2s_buffer, sizeof(raw_i2s_buffer), &bytes_read, 50);
    if (err != ESP_OK || bytes_read == 0) return;

    int frames_count = bytes_read / (sizeof(int32_t) * 2);
    process_i2s_samples(frames_count);

    int32_t energy = calculate_rms(pcm_samples, frames_count);

    // Dynamic noise floor tracking khi phòng yên tĩnh và ngoài thời gian cooldown
    if (!is_speaking && now > speech_cooldown_until && energy < vad_threshold_continue) {
        static unsigned long last_dyn_update = 0;
        if (now - last_dyn_update > 400) {
            last_dyn_update = now;
            baseline_rms = (baseline_rms * 15 + energy) / 16;
            vad_threshold_start = (int32_t)(baseline_rms * 2.0f + 150);
            if (vad_threshold_start < 250) vad_threshold_start = 250;
            vad_threshold_continue = (int32_t)(baseline_rms * 1.3f + 60);
            if (vad_threshold_continue < 160) vad_threshold_continue = 160;
        }
    }

    // Diagnostic log mỗi 1000ms để theo dõi trực tiếp trạng thái micro
    if (now - last_diag_log > 1000) {
        last_diag_log = now;
        Serial.printf("[Mic Status] RMS: %d | Thresh: %d/%d | Base: %d | Kênh: %s\n",
                      (int)energy, (int)vad_threshold_start, (int)vad_threshold_continue, (int)baseline_rms,
                      (active_channel == 1) ? "RIGHT" : (active_channel == 0 ? "LEFT" : "CHƯA KHÓA"));
    }

    if (!is_speaking) {
        // Lưu vào bộ đệm trượt 192ms chống nuốt âm đầu
        memcpy(pre_buffer[pre_buf_index], pcm_samples, sizeof(pcm_samples));
        pre_buf_index = (pre_buf_index + 1) % PRE_BUFFER_CHUNKS;
        if (pre_buf_index == 0) pre_buf_full = true;

        if (now > speech_cooldown_until && energy > vad_threshold_start) {
            is_speaking = true;
            speech_start_time = now;
            last_sound_time = now;
            set_led(true); // BẬT ĐÈN LED BÁO HIỆU ĐANG THU ÂM

            Serial.printf("[Voice Puck] 🎙️ Bắt đầu thu âm! (RMS: %d > %d)\n", (int)energy, (int)vad_threshold_start);

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

        if (energy > vad_threshold_continue) {
            last_sound_time = now;
        }

        // Kiểm tra kết thúc câu nói: đã qua thời gian tối thiểu và có khoảng lặng đủ dài
        bool has_min_duration = (now - speech_start_time >= MIN_SPEECH_DURATION_MS);
        bool is_silence = has_min_duration && (now - last_sound_time > SILENCE_TIMEOUT_MS);
        bool is_max_len = (now - speech_start_time > MAX_SPEECH_DURATION_MS);

        if (is_silence || is_max_len) {
            is_speaking = false;
            speech_cooldown_until = now + 400; // Cooldown 400ms chống dội âm và retrigger tức thời
            set_led(false);

            Serial.printf("[Voice Puck] ⏹️ Kết thúc thu âm (%lu ms) -> Gửi máy chủ!\n", now - speech_start_time);

            send_event_marker("__END__");
            blink_led(2, 40); // Nháy 2 lần xác nhận đã gửi
        }
    }
}
