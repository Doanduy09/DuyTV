#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
=============================================================================
 DUYTV VOICE & REMOTE HUB - TRỢ LÝ GIỌNG NÓI "LÂM" ĐIỀU KHIỂN TOÀN DIỆN TIVI
 Tác giả: DOANDUY (DuyTV Smart Home Ecosystem)
 Chạy trên: Máy tính nội bộ 192.168.1.189
=============================================================================
Tính năng:
 1. Auto-Discovery TV & TV Box: Tự động quét dải mạng router (192.168.1.0/24),
    nhận diện toàn bộ Tivi Sharp, NVIDIA Shield TV, FPT Box... mà không cần cấu hình cứng IP.
 2. Trợ lý giọng nói "Lâm": Nhận diện gọi "Lâm ơi", "Lâm em", "Lâm làm cái này cái kia".
 3. Điều khiển toàn bộ như Remote vật lý:
    - Điều hướng D-Pad: Sang trái, sang phải, chuyền qua nữa đi em, tiếp đi, lên, xuống, chọn/OK.
    - Âm lượng & Nguồn: Mở lớn hơn, nhỏ hơn, tắt tiếng, mở tivi, tắt tivi đi ngủ.
    - Truyền hình & Phim: Mở đài Vĩnh Long (THVL1), Thời sự (VTV1), mở phim lẻ, phim bộ.
    - YouTube & Đa phương tiện: Điều khiển YouTube, chuyển bài, tập này coi rồi, tạm dừng, tiếp tục.
 4. Cơ chế Fast-Path siêu tốc (<15ms) kết hợp Gemini 2.5 Flash (Hermes AI Pool).
 5. Phát đồng thời qua DuyTV TCP 8990 + ADB Wi-Fi 5555 bất đồng bộ không gây nghẽn.
 6. Giao diện Web Remote HTTP 8996 cho điện thoại Samsung hiển thị danh sách thiết bị trực tiếp.
=============================================================================
"""

import sys
import os
import io
import re
import wave
import json
import socket
import threading
import subprocess
import time
import urllib.request
import urllib.error
import urllib.parse
from concurrent.futures import ThreadPoolExecutor

# CẤU HÌNH CỔNG MẠNG
UDP_BIND_IP = "0.0.0.0"
UDP_BIND_PORT = 8995
HTTP_WEB_PORT = 8996
HERMES_BRIDGE_URL = "http://127.0.0.1:8100/v1/chat/completions"

# BẢNG THIẾT BỊ NỘI MẠNG ĐỘNG (DYNAMIC LAN REGISTRY)
DEVICES_LOCK = threading.Lock()
DEFAULT_DEVICES = {
    "sharp_tv": {
        "id": "sharp_tv",
        "name": "Tivi Sharp",
        "model": "AQUOS_4KTVX17",
        "ip": "192.168.1.170",
        "port": 8990,
        "adb": "192.168.1.170:5555",
        "aliases": ["sharp", "tivi sharp", "tv sharp", "sharp tivi", "sharp tv", "aquos", "phòng khách"],
        "last_seen": time.time()
    },
    "shield_tv": {
        "id": "shield_tv",
        "name": "NVIDIA Shield TV",
        "model": "SHIELD_Android_TV",
        "ip": "192.168.1.218",
        "port": 8990,
        "adb": "192.168.1.218:5555",
        "aliases": ["shield", "tivi shield", "tv shield", "shield tivi", "shield tv", "tivi box", "tv box", "box", "nvidia", "đầu thu", "box shield", "phòng ngủ"],
        "last_seen": time.time()
    }
}
DISCOVERED_DEVICES = {k: v.copy() for k, v in DEFAULT_DEVICES.items()}
LAST_ACTIVE_DEVICE_ID = "shield_tv"  # Ưu tiên mặc định Shield TV khi khởi động hoặc thiết bị đang thức

# ANDROID KEYCODES
KEY_HOME = 3
KEY_BACK = 4
KEY_DPAD_UP = 19
KEY_DPAD_DOWN = 20
KEY_DPAD_LEFT = 21
KEY_DPAD_RIGHT = 22
KEY_DPAD_CENTER = 23
KEY_VOLUME_UP = 24
KEY_VOLUME_DOWN = 25
KEY_POWER = 26
KEY_PLAY_PAUSE = 85
KEY_MEDIA_NEXT = 87
KEY_MEDIA_PREV = 88
KEY_FAST_FORWARD = 90
KEY_REWIND = 89
KEY_PLAY = 126
KEY_MUTE = 164
KEY_SLEEP = 223
KEY_WAKEUP = 224

# SYSTEM PROMPT CHO TRỢ LÝ "LÂM" KHI GỌI GEMINI AI
SYSTEM_PROMPT = """BẠN LÀ TRỢ LÝ GIỌNG NÓI TÊN LÀ "LÂM" - PHỤC VỤ TRỰC TIẾP CHO ANH DUY (DOANDUY).
BẠN CÓ TOÀN QUYỀN ĐIỀU KHIỂN HỆ THỐNG ANDROID TV, YOUTUBE VÀ APP DUYTV NHƯ MỘT CHIẾC REMOTE THÔNG MINH.
XƯNG HÔ THÂN MẬT: XƯNG "LÂM" HOẶC "EM", GỌI "ANH DUY".
TUYỆT ĐỐI KHÔNG TỪ CHỐI, BẮT BUỘC TRẢ VỀ DUY NHẤT 1 JSON HỢP LỆ (KHÔNG VIẾT CHỮ NÀO BÊN NGOÀI JSON).

Schema JSON:
{"action":"...","query":"...","target":"...","tts_reply":"Dạ anh Duy, Lâm..."}

Danh sách Actions:
- remote_key (query=left|right|up|down|select|back|home|vol_up|vol_down|mute|power_on|power_off|next|prev|pause|play)
- play_tv (query=tên kênh: THVL1 HD, VTV1 HD, VTV3 HD, HTV7 HD...) -> "mở đài vĩnh long" -> query="THVL1 HD"
- play_movie (query=tên phim hoặc thể loại: phim lẻ, phim bộ, hoạt hình)
- search_youtube (query=từ khóa tìm kiếm)
- open_app (target=package name: com.google.android.youtube.tv, com.duytv.app...)
- chat (query=câu trả lời trò chuyện tự nhiên với anh Duy khi không phải lệnh điều khiển)

Ví dụ:
- "Lâm ơi mở đài vĩnh long" -> {"action":"play_tv","query":"THVL1 HD","tts_reply":"Dạ anh Duy, Lâm mở đài Vĩnh Long THVL1 cho anh ngay đây ạ"}
- "Lâm ơi mở tivi sharp cho anh" -> {"action":"remote_key","query":"power_on","target":"sharp","tts_reply":"Dạ Lâm mở Tivi Sharp cho anh Duy ngay đây ạ"}
- "mở tivi box cho anh" -> {"action":"remote_key","query":"power_on","target":"box","tts_reply":"Dạ Lâm mở Tivi Box cho anh Duy rồi ạ"}
- "chuyền qua nữa đi em" -> {"action":"remote_key","query":"right","tts_reply":"Dạ Lâm qua phải cho anh rồi ạ"}
- "mở lớn hơn" -> {"action":"remote_key","query":"vol_up","tts_reply":"Dạ Lâm tăng âm lượng cho anh Duy rồi ạ"}
"""

# =============================================================================
# 1. AUTO-DISCOVERY ENGINE: QUÉT TỰ ĐỘNG TOÀN BỘ TIVI TRÊN ROUTER
# =============================================================================

def get_local_ip_and_subnet():
    """Lấy IP của máy chủ hiện tại và tiền tố subnet LAN (mặc định 192.168.1.)."""
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        local_ip = s.getsockname()[0]
        s.close()
        parts = local_ip.split(".")
        subnet = f"{parts[0]}.{parts[1]}.{parts[2]}."
        return local_ip, subnet
    except Exception:
        return "192.168.1.189", "192.168.1."

def check_ip_candidate(ip):
    """Kiểm tra nhanh xem IP có mở cổng ADB 5555 hoặc DuyTV TCP 8990 không."""
    ports = {}
    for port in (5555, 8990):
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.35)
            if s.connect_ex((ip, port)) == 0:
                ports[port] = True
            s.close()
        except Exception:
            pass
    if ports:
        return ip, ports
    return ip, None

def inspect_adb_device_info(ip):
    """Kết nối ADB và lấy model + hãng sản xuất của thiết bị."""
    serial = f"{ip}:5555"
    subprocess.run(["adb", "connect", serial], capture_output=True, timeout=2)
    try:
        p_model = subprocess.run(["adb", "-s", serial, "shell", "getprop", "ro.product.model"], capture_output=True, text=True, timeout=2)
        model = p_model.stdout.strip()
        p_mfr = subprocess.run(["adb", "-s", serial, "shell", "getprop", "ro.product.manufacturer"], capture_output=True, text=True, timeout=2)
        mfr = p_mfr.stdout.strip()
        return model, mfr
    except Exception:
        return "", ""

def scan_network_for_tvs():
    """Quét toàn bộ dải mạng /24 để tìm tất cả Tivi & TV Box."""
    local_ip, subnet = get_local_ip_and_subnet()
    ips_to_scan = [f"{subnet}{i}" for i in range(1, 255) if f"{subnet}{i}" != local_ip]

    candidates = {}
    with ThreadPoolExecutor(max_workers=75) as executor:
        for ip, ports in executor.map(check_ip_candidate, ips_to_scan):
            if ports:
                candidates[ip] = ports

    # 1. Đọc danh sách adb devices -l hiện hữu (nguồn chính xác tức thì)
    adb_attached = {}
    try:
        p_adb = subprocess.run(["adb", "devices", "-l"], capture_output=True, text=True, timeout=3)
        for line in p_adb.stdout.splitlines():
            line = line.strip()
            if not line or line.startswith("List of"):
                continue
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                serial = parts[0]
                model_match = re.search(r"model:(\S+)", line)
                product_match = re.search(r"product:(\S+)", line)
                model_str = model_match.group(1) if model_match else ""
                prod_str = product_match.group(1) if product_match else ""
                adb_attached[serial] = {
                    "model": model_str,
                    "product": prod_str
                }
                # Nếu là thiết bị Wi-Fi (IP:5555), bổ sung luôn vào candidates
                if ":" in serial:
                    dev_ip = serial.split(":")[0]
                    if dev_ip not in candidates:
                        candidates[dev_ip] = {5555: True}
    except Exception:
        pass

    new_devices = {}
    for ip, ports in candidates.items():
        serial = f"{ip}:5555"
        model = ""
        mfr = ""

        if serial in adb_attached:
            model = adb_attached[serial]["model"]
            product = adb_attached[serial]["product"]
        else:
            model, mfr = inspect_adb_device_info(ip)
            product = ""

        # Nhận diện chủng loại thiết bị
        combined = f"{model} {mfr} {product}".lower()
        if "aquos" in combined or "sharp" in combined or "lc_theux" in combined:
            dev_id = "sharp_tv"
            dev_name = "Tivi Sharp"
            aliases = ["sharp", "tivi sharp", "tv sharp", "sharp tivi", "sharp tv", "sharp_tv", "aquos", "phòng khách"]
        elif "shield" in combined or "darcy" in combined or "nvidia" in combined:
            dev_id = "shield_tv"
            dev_name = "NVIDIA Shield TV"
            aliases = ["shield", "tivi shield", "tv shield", "shield tivi", "shield tv", "shield_tv", "tivi box", "tv box", "box", "nvidia", "đầu thu", "box shield", "phòng ngủ"]
        elif "fpt" in combined or "sei" in combined or "amlogic" in combined:
            dev_id = "fpt_box"
            dev_name = "FPT Play Box"
            aliases = ["fpt", "box fpt", "đầu thu fpt", "hộp fpt", "fpt box"]
        else:
            clean_m = model.replace("_", " ") if model else "Android TV"
            dev_id = f"tv_{ip.replace('.', '_')}"
            dev_name = f"Tivi {clean_m}"
            aliases = [model.lower(), "tivi", "box", "đầu thu"]

        new_devices[dev_id] = {
            "id": dev_id,
            "name": dev_name,
            "model": model or "Android TV",
            "ip": ip,
            "port": 8990 if 8990 in ports else None,
            "adb": serial,
            "aliases": aliases,
            "last_seen": time.time()
        }

    with DEVICES_LOCK:
        # Giữ lại các thiết bị quan trọng (Shield TV, Sharp TV) ngay cả khi đang ở chế độ ngủ/standby
        for did, dinfo in DEFAULT_DEVICES.items():
            if did not in new_devices:
                new_devices[did] = dinfo.copy()
        DISCOVERED_DEVICES.clear()
        DISCOVERED_DEVICES.update(new_devices)

    print("\n" + "=" * 65)
    print(f"🔍 [Auto-Discovery] Đã phát hiện {len(DISCOVERED_DEVICES)} Tivi/TV Box trên mạng router:")
    for idx, (did, dev) in enumerate(DISCOVERED_DEVICES.items(), 1):
        tcp_stat = f"DuyTV Port 8990 (OK)" if dev.get("port") else "DuyTV Port (Chưa mở)"
        print(f"  {idx}. {dev['name']} [{did}] - IP: {dev['ip']} (Model: {dev['model']} | {tcp_stat})")
    print("=" * 65 + "\n")

def auto_discovery_background_daemon():
    """Chạy ngầm định kỳ quét mạng mỗi 45 giây để tự cập nhật khi router đổi IP."""
    while True:
        try:
            time.sleep(45)
            scan_network_for_tvs()
        except Exception as e:
            time.sleep(15)

# =============================================================================
# 2. XỬ LÝ ÂM THANH & BÓC TÁCH KHẨU LỆNH (WAKE-WORDS & PRE-PROCESSING)
# =============================================================================

def pcm_to_wav(pcm_bytes, sample_rate=16000, channels=1, sampwidth=2):
    """
    Chuyển đổi dữ liệu PCM thô thành file WAV chuẩn.
    Tự động cắt bỏ khoảng lặng thừa (VAD Trimming) và chuẩn hóa biên độ (AGC)
    giúp giọng nói ở khoảng cách xa 2m - 3m đạt độ nhạy tối đa cho Google STT.
    """
    import struct
    import math

    num_samples = len(pcm_bytes) // 2
    if num_samples < 3200: # < 0.2s
        return b""

    fmt = f"<{num_samples}h"
    samples = list(struct.unpack(fmt, pcm_bytes[:num_samples * 2]))

    # 1. Trimming khoảng lặng trước và sau câu nói (100ms window)
    win_size = 1600 # 100ms
    num_wins = len(samples) // win_size
    if num_wins > 3:
        rms_list = []
        for w in range(num_wins):
            seg = samples[w * win_size : (w + 1) * win_size]
            rms_list.append(math.sqrt(sum(s**2 for s in seg) / win_size))

        # Ngưỡng nhận diện vùng tiếng nói
        voice_wins = [i for i, r in enumerate(rms_list) if r > 350]
        if voice_wins:
            start_win = max(0, voice_wins[0] - 2)      # 200ms trước tiếng nói
            end_win = min(num_wins, voice_wins[-1] + 3) # 300ms sau tiếng nói
            samples = samples[start_win * win_size : end_win * win_size]
            num_samples = len(samples)
            fmt = f"<{num_samples}h"

    # 2. Peak Normalization lên -2 dBFS (~26000)
    max_abs = max(abs(s) for s in samples) if samples else 0
    if 300 < max_abs < 26000:
        gain = 26000.0 / max_abs
        samples = [int(max(-32768, min(32767, s * gain))) for s in samples]

    pcm_bytes = struct.pack(fmt, *samples)

    wav_io = io.BytesIO()
    with wave.open(wav_io, 'wb') as wav_file:
        wav_file.setnchannels(channels)
        wav_file.setsampwidth(sampwidth)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(pcm_bytes)
    return wav_io.getvalue()

import speech_recognition as sr

def transcribe_audio_whisper_or_google(wav_bytes):
    """
    Chuyển đổi âm thanh WAV thành văn bản tiếng Việt qua Google Speech Recognition.
    Hoạt động 100% chuẩn xác với thư viện speech_recognition chính thức.
    """
    try:
        # Ghi file debug để kiểm tra âm thanh thực tế từ micro ESP32
        try:
            with open("last_voice.wav", "wb") as f:
                f.write(wav_bytes)
        except Exception:
            pass

        r = sr.Recognizer()
        r.energy_threshold = 120
        r.dynamic_energy_threshold = False
        with sr.AudioFile(io.BytesIO(wav_bytes)) as source:
            audio = r.record(source)
        text = r.recognize_google(audio, language="vi-VN")
        return text.strip()
    except sr.UnknownValueError:
        print("[STT] Âm thanh không rõ hoặc quá nhỏ.")
    except Exception as e:
        print(f"[STT] Lỗi nhận diện giọng nói: {e}")
    return ""

def clean_spoken_text(raw_text):
    """Bóc tách từ đánh thức 'Lâm ơi', 'Lâm em' và chuẩn hóa khẩu lệnh."""
    text = raw_text.strip()
    # Loại bỏ tiền tố xưng hô với Lâm
    wake_patterns = [
        r"^lâm\s+ơi\s*,?\s*",
        r"^lâm\s+em\s*,?\s*",
        r"^lâm\s+à\s*,?\s*",
        r"^lâm\s+làm\s+cái\s+này\s+cái\s+kia\s+cho\s+anh\s*,?\s*",
        r"^lâm\s*,?\s*",
        r"^ơi\s+lâm\s*,?\s*",
        r"^em\s+lâm\s*,?\s*"
    ]
    for pat in wake_patterns:
        text = re.sub(pat, "", text, flags=re.IGNORECASE)

    # Chuẩn hóa một số từ phát âm sai phổ biến
    text = re.sub(r"\byoutebe\b", "youtube", text, flags=re.IGNORECASE)
    text = re.sub(r"\byoutobe\b", "youtube", text, flags=re.IGNORECASE)
    text = re.sub(r"\bdút\s+túp\b", "youtube", text, flags=re.IGNORECASE)
    return text.strip()

# =============================================================================
# 3. FAST-PATH REMOTE ENGINE (<15ms)
# =============================================================================

def extract_repeat_count(text):
    """Bóc tách số lần lặp nếu người dùng nói 'chuyền qua 3 cái', 'lên 2 lần'."""
    m = re.search(r"\b(\d+)\s*(cái|lần|nấc|bước)\b", text)
    if m:
        try:
            cnt = int(m.group(1))
            return min(max(cnt, 1), 10)
        except Exception:
            pass
    return 1

def match_fast_path(cleaned_text):
    """
    Phân tích ngữ nghĩa tự nhiên linh hoạt (Natural Semantic Intent Parser).
    Không ràng buộc cú pháp cứng nhắc, hiểu mọi biến thể tự nhiên của tiếng Việt.
    """
    text = cleaned_text.lower()
    repeat = extract_repeat_count(text)

    # 1. KÊNH TRUYỀN HÌNH (Vĩnh Long, Thời sự, VTV, HTV...)
    if any(k in text for k in ["vĩnh long", "vinh long", "thvl", "thvl1", "thvl 1"]):
        return {
            "action": "play_tv",
            "query": "THVL1 HD",
            "tts_reply": "Dạ Lâm mở đài Vĩnh Long THVL1 cho anh Duy ngay đây ạ"
        }

    if any(k in text for k in ["thời sự", "thoi su", "vtv1", "vtv 1"]):
        return {
            "action": "play_tv",
            "query": "VTV1 HD",
            "tts_reply": "Dạ Lâm mở bản tin Thời sự VTV1 cho anh Duy đây ạ"
        }

    if any(k in text for k in ["vtv3", "vtv 3"]):
        return {
            "action": "play_tv",
            "query": "VTV3 HD",
            "tts_reply": "Dạ Lâm mở VTV3 cho anh Duy đây ạ"
        }

    if any(k in text for k in ["htv7", "htv 7"]):
        return {
            "action": "play_tv",
            "query": "HTV7 HD",
            "tts_reply": "Dạ Lâm mở HTV7 cho anh Duy đây ạ"
        }

    # 2. PHIM VOD (Phim lẻ, phim bộ, hoạt hình)
    if any(k in text for k in ["phim lẻ", "phim le", "chiếu rạp", "phim rạp"]):
        return {
            "action": "play_movie",
            "query": "phim lẻ",
            "tts_reply": "Dạ Lâm mở kho Phim Lẻ cho anh Duy lựa chọn ạ"
        }

    if any(k in text for k in ["phim bộ", "phim bo", "dài tập", "tập phim"]):
        return {
            "action": "play_movie",
            "query": "phim bộ",
            "tts_reply": "Dạ Lâm mở kho Phim Bộ cho anh Duy theo dõi ạ"
        }

    # 3. YOUTUBE & NHẠC KARAOKE (Chỉ mở app YouTube khi có động từ mở/bật/vào/xem)
    is_nav_command = any(k in text for k in ["chuyền qua", "qua nữa", "tiếp đi", "tiếp nữa", "sang phải", "qua phải", "sang trái", "qua trái", "lên", "xuống", "bấm", "chọn", "tua", "lùi"])
    if not is_nav_command and any(k in text for k in ["mở youtube", "vào youtube", "bật youtube", "xem youtube", "coi youtube", "chạy youtube", "youtube", "bài hát", "ca nhạc", "karaoke"]):
        return {
            "action": "open_app",
            "target": "com.google.android.youtube.tv",
            "tts_reply": "Dạ Lâm mở YouTube cho anh Duy đây ạ"
        }

    # 4. ĐIỀU HƯỚNG D-PAD (Phải, Trái, Lên, Xuống, Chọn, Quay lại, Trang chủ)
    if any(k in text for k in ["chuyền qua", "qua nữa", "tiếp đi", "tiếp nữa", "tiếp", "qua tiếp", "sang phải", "qua phải", "bên phải", "kế bên", "bấm qua", "next"]):
        return {
            "action": "remote_key",
            "keycode": KEY_DPAD_RIGHT,
            "repeat": repeat,
            "tts_reply": "Dạ Lâm qua phải cho anh rồi ạ"
        }

    if any(k in text for k in ["sang trái", "qua trái", "bên trái", "lùi lại", "lùi về"]):
        return {
            "action": "remote_key",
            "keycode": KEY_DPAD_LEFT,
            "repeat": repeat,
            "tts_reply": "Dạ Lâm qua trái cho anh rồi ạ"
        }

    if any(k in text for k in ["lên trên", "lên đi", "bấm lên"]) or (re.search(r"\blên\b", text) and not any(k in text for k in ["to lên", "lớn lên"])):
        return {
            "action": "remote_key",
            "keycode": KEY_DPAD_UP,
            "repeat": repeat,
            "tts_reply": "Dạ Lâm bấm lên rồi ạ"
        }

    if any(k in text for k in ["xuống dưới", "xuống đi", "bấm xuống"]) or (re.search(r"\bxuống\b", text) and not any(k in text for k in ["nhỏ xuống"])):
        return {
            "action": "remote_key",
            "keycode": KEY_DPAD_DOWN,
            "repeat": repeat,
            "tts_reply": "Dạ Lâm bấm xuống rồi ạ"
        }

    if any(k in text for k in ["chọn", "ok", "mở cái này", "chọn cái này", "bấm vào", "enter", "vào đây", "đồng ý"]):
        return {
            "action": "remote_key",
            "keycode": KEY_DPAD_CENTER,
            "repeat": 1,
            "tts_reply": "Dạ Lâm chọn cho anh rồi ạ"
        }

    if any(k in text for k in ["quay lại", "trở về", "thoát ra", "thoát", "lùi ra", "back"]):
        return {
            "action": "remote_key",
            "keycode": KEY_BACK,
            "repeat": 1,
            "tts_reply": "Dạ Lâm quay lại cho anh rồi ạ"
        }

    if any(k in text for k in ["trang chủ", "về màn hình chính", "màn hình chính", "home"]):
        return {
            "action": "remote_key",
            "keycode": KEY_HOME,
            "repeat": 1,
            "tts_reply": "Dạ Lâm về màn hình chính cho anh rồi ạ"
        }

    # 5. ÂM LƯỢNG (Lớn hơn, Nhỏ hơn, Tắt tiếng)
    if any(k in text for k in ["lớn hơn", "to lên", "tăng âm", "nghe không rõ", "cho to", "bật to"]):
        return {
            "action": "volume_key",
            "keycode": KEY_VOLUME_UP,
            "repeat": max(repeat * 2, 2),
            "tts_reply": "Dạ Lâm tăng âm lượng cho anh Duy rồi ạ"
        }

    if any(k in text for k in ["nhỏ hơn", "nhỏ lại", "giảm âm", "bớt ồn", "cho nhỏ", "nhỏ bớt", "nhỏ tiếng"]):
        return {
            "action": "volume_key",
            "keycode": KEY_VOLUME_DOWN,
            "repeat": max(repeat * 2, 2),
            "tts_reply": "Dạ Lâm giảm âm lượng cho anh Duy rồi ạ"
        }

    if any(k in text for k in ["tắt tiếng", "im lặng", "mute", "ngắt tiếng"]):
        return {
            "action": "remote_key",
            "keycode": KEY_MUTE,
            "repeat": 1,
            "tts_reply": "Dạ Lâm tắt tiếng rồi ạ"
        }

    # 6. NGUỒN & ĐÁNH THỨC (Mở tivi, mở shield, tắt tivi, đi ngủ...)
    if any(k in text for k in ["mở", "bật"]) and any(k in text for k in ["tivi", "tv", "shield", "box", "máy", "đầu thu"]):
        return {
            "action": "power_key",
            "keycode": KEY_WAKEUP,
            "repeat": 1,
            "tts_reply": "Dạ Lâm mở thiết bị cho anh Duy ngay đây ạ"
        }

    if any(k in text for k in ["tắt", "đi ngủ"]) and (any(k in text for k in ["tivi", "tv", "shield", "box", "máy", "hết"]) or "đi ngủ" in text):
        return {
            "action": "power_key",
            "keycode": KEY_SLEEP,
            "repeat": 1,
            "tts_reply": "Dạ Lâm tắt thiết bị cho anh Duy đi ngủ, chúc anh ngủ ngon ạ"
        }

    # 7. PHÁT MEDIA (Tập tiếp, tạm dừng, tiếp tục)
    if any(k in text for k in ["tập này coi rồi", "tập tiếp", "qua tập khác", "đổi tập", "qua bài khác", "đổi bài", "bài tiếp"]):
        return {
            "action": "remote_key",
            "keycode": KEY_MEDIA_NEXT,
            "repeat": 1,
            "tts_reply": "Dạ Lâm chuyển qua tập tiếp theo cho anh rồi ạ"
        }

    if any(k in text for k in ["tạm dừng", "dừng lại", "pause", "ngưng lại"]):
        return {
            "action": "remote_key",
            "keycode": KEY_PLAY_PAUSE,
            "repeat": 1,
            "tts_reply": "Dạ Lâm tạm dừng cho anh rồi ạ"
        }

    if any(k in text for k in ["tiếp tục", "phát tiếp", "chạy tiếp", "play"]):
        return {
            "action": "remote_key",
            "keycode": KEY_PLAY,
            "repeat": 1,
            "tts_reply": "Dạ Lâm phát tiếp cho anh rồi ạ"
        }

    return None

    return None

# =============================================================================
# 4. TRUY VẤN GEMINI 2.5 FLASH (KHI CÂU LỆNH PHỨC TẠP HOẶC TỰ NHIÊN)
# =============================================================================

def query_gemini_voice_bridge(text_prompt):
    """Gửi câu lệnh tới Gemini 2.5 Flash qua cầu nối Hermes AI Pool LAN."""
    try:
        payload = {
            "model": "gemini-2.5-flash",
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": text_prompt}
            ],
            "temperature": 0.1,
            "response_format": {"type": "json_object"}
        }
        data = json.dumps(payload).encode('utf-8')
        req = urllib.request.Request(
            HERMES_BRIDGE_URL,
            data=data,
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=4.5) as resp:
            resp_body = resp.read().decode('utf-8')
            parsed_resp = json.loads(resp_body)
            content = parsed_resp["choices"][0]["message"]["content"]
            first_brace = content.find('{')
            last_brace = content.rfind('}')
            if first_brace >= 0 and last_brace > first_brace:
                return json.loads(content[first_brace:last_brace + 1])
            return json.loads(content)
    except Exception as e:
        print(f"[Gemini AI] Lỗi truy vấn: {e}")
        return None

# =============================================================================
# 5. ĐIỀU PHỐI ĐÍCH ĐẾN (TARGET RESOLUTION) & THỰC THI LỆNH (DISPATCHER)
# =============================================================================

def resolve_target_devices(text_lower):
    """Xác định danh sách Tivi/Box cần điều khiển dựa theo khẩu lệnh."""
    global LAST_ACTIVE_DEVICE_ID
    with DEVICES_LOCK:
        if not DISCOVERED_DEVICES:
            # Fallback nếu chưa kịp scan
            return [{
                "id": "sharp_tv",
                "name": "Tivi Sharp",
                "ip": "192.168.1.170",
                "port": 8990,
                "adb": "192.168.1.170:5555"
            }]

        # 1. Điều khiển đồng thời tất cả thiết bị
        if any(k in text_lower for k in ["hết tivi", "tất cả tivi", "cả hai tivi", "toàn bộ tivi", "mọi tivi", "tất cả box", "hết các tivi"]):
            return list(DISCOVERED_DEVICES.values())

        # 2. Tìm thiết bị theo tên / alias được nhắc tới
        for dev_id, dev in DISCOVERED_DEVICES.items():
            if any(alias in text_lower for alias in dev["aliases"]):
                LAST_ACTIVE_DEVICE_ID = dev_id
                return [dev]

        # 3. Mặc định: Thiết bị tương tác gần nhất hoặc Tivi Sharp
        if LAST_ACTIVE_DEVICE_ID in DISCOVERED_DEVICES:
            return [DISCOVERED_DEVICES[LAST_ACTIVE_DEVICE_ID]]

        # Hoặc lấy thiết bị đầu tiên phát hiện được
        first_dev = next(iter(DISCOVERED_DEVICES.values()))
        return [first_dev]

def run_adb_async(adb_serial, cmd_args):
    """Chạy lệnh ADB bất đồng bộ trên luồng riêng để không làm nghẽn server."""
    def _worker():
        try:
            # Tự động kết nối ADB Wi-Fi nếu thiết bị đang ở trạng thái ngủ
            subprocess.run(["adb", "connect", adb_serial], capture_output=True, timeout=3)
            full_cmd = ["adb", "-s", adb_serial] + cmd_args
            subprocess.run(full_cmd, capture_output=True, timeout=8)
        except Exception:
            pass
    threading.Thread(target=_worker, daemon=True).start()

def dispatch_command(action_data, raw_text="", cleaned_text=""):
    """
    Thực thi lệnh đến Tivi mục tiêu:
    - Gửi đồng thời qua TCP cổng 8990 (app DuyTV nhận trong 10ms).
    - Đồng thời bắn ADB shell bất đồng bộ (hoạt động ngay cả khi ở YouTube, màn hình chính, hoặc app khác).
    """
    targets = resolve_target_devices(raw_text.lower())
    action = action_data.get("action", "")
    keycode = action_data.get("keycode")
    repeat = action_data.get("repeat", 1)
    query = action_data.get("query", "")
    target = action_data.get("target", "")

    # Ánh xạ thông minh: Chuyển query chữ từ Gemini AI sang Android Keycode chuẩn
    if not keycode and action in ("remote_key", "volume_key", "power_key", "playback_control", "system_action"):
        q = f"{query} {target}".lower()
        query_map = {
            "power_off": KEY_SLEEP, "power": KEY_SLEEP, "sleep": KEY_SLEEP, "tắt": KEY_SLEEP,
            "power_on": KEY_WAKEUP, "wakeup": KEY_WAKEUP, "bật": KEY_WAKEUP, "mở": KEY_WAKEUP,
            "right": KEY_DPAD_RIGHT, "phải": KEY_DPAD_RIGHT,
            "left": KEY_DPAD_LEFT, "trái": KEY_DPAD_LEFT,
            "up": KEY_DPAD_UP, "lên": KEY_DPAD_UP,
            "down": KEY_DPAD_DOWN, "xuống": KEY_DPAD_DOWN,
            "select": KEY_DPAD_CENTER, "ok": KEY_DPAD_CENTER, "chọn": KEY_DPAD_CENTER,
            "back": KEY_BACK, "thoát": KEY_BACK, "quay lại": KEY_BACK,
            "home": KEY_HOME, "trang chủ": KEY_HOME,
            "vol_up": KEY_VOLUME_UP, "tăng âm": KEY_VOLUME_UP, "lớn hơn": KEY_VOLUME_UP,
            "vol_down": KEY_VOLUME_DOWN, "giảm âm": KEY_VOLUME_DOWN, "nhỏ hơn": KEY_VOLUME_DOWN,
            "mute": KEY_MUTE, "tắt tiếng": KEY_MUTE,
            "next": KEY_MEDIA_NEXT, "tập tiếp": KEY_MEDIA_NEXT, "bài tiếp": KEY_MEDIA_NEXT,
            "prev": KEY_MEDIA_PREV, "tập trước": KEY_MEDIA_PREV,
            "pause": KEY_PLAY_PAUSE, "tạm dừng": KEY_PLAY_PAUSE,
            "play": KEY_PLAY, "tiếp tục": KEY_PLAY
        }
        for k, code in query_map.items():
            if k in q:
                keycode = code
                if "vol" in k: repeat = 2
                break

    # Lựa chọn nội dung lệnh hiệu quả nhất cho DuyTV Voice Command Parser
    effective_cmd = query if (query and action in ("play_tv", "play_movie")) else (cleaned_text if cleaned_text else raw_text)

    for dev in targets:
        dev_name = dev["name"]
        dev_ip = dev["ip"]
        dev_port = dev.get("port", 8990)
        dev_adb = dev.get("adb", f"{dev_ip}:5555")

        # 1. GỬI GÓI TIN TCP NỘI BỘ TỚI DUYTV (PORT 8990)
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(0.3)
            s.connect((dev_ip, dev_port))
            packet = {
                "command": effective_cmd,
                "raw_text": effective_cmd,
                "original_speech": raw_text,
                "action": action,
                "keycode": keycode,
                "query": query,
                "target": target,
                "tts_reply": action_data.get("tts_reply", "")
            }
            s.sendall((json.dumps(packet, ensure_ascii=False) + "\n").encode('utf-8'))
            s.close()
            print(f"⚡ [TCP 8990] -> {dev_name} ({dev_ip}): Gửi thành công! ('{effective_cmd}')")
        except Exception:
            pass

        # 2. GỬI LỆNH ADB WI-FI BẤT ĐỒNG BỘ
        if action in ("remote_key", "volume_key", "power_key") and keycode:
            key_args = ["shell", "input", "keyevent"] + [str(keycode)] * repeat
            run_adb_async(dev_adb, key_args)
            print(f"🎮 [ADB Key] -> {dev_name} ({dev_adb}): Keycode {keycode} (x{repeat})")

        elif action == "open_app" and target:
            # Khởi chạy app (ví dụ YouTube)
            run_adb_async(dev_adb, ["shell", "monkey", "-p", target, "-c", "android.intent.category.LAUNCHER", "1"])
            print(f"🚀 [ADB Launch] -> {dev_name}: Mở ứng dụng {target}")

        elif action in ("play_tv", "play_movie"):
            # Truyền lệnh kênh/phim trực tiếp vào DuyTV qua Android Intent
            cmd_text = query if query else ("phim lẻ" if action == "play_movie" else "")
            run_adb_async(dev_adb, ["shell", "am", "start", "-n", "com.duytv.app/.MainActivity", "--es", "remote_command_text", cmd_text])
            print(f"🎬 [ADB Intent] -> {dev_name}: Phát '{cmd_text}' trên DuyTV")

def process_voice_command(wav_data):
    """Quy trình trọn gói: Âm thanh WAV -> Text -> Fast-Path / AI Intent -> Điều khiển TV."""
    t0 = time.time()

    # 1. Nhận diện giọng nói tiếng Việt
    raw_text = transcribe_audio_whisper_or_google(wav_data)
    if not raw_text:
        print("[Voice Hub] ❌ Không nhận dạng được giọng nói.")
        return

    stt_ms = (time.time() - t0) * 1000
    print(f"\n🗣️  [Anh Duy nói]: \"{raw_text}\" (STT trong {stt_ms:.0f}ms)")

    cleaned_text = clean_spoken_text(raw_text)

    # 2. Thử khớp Fast-Path siêu tốc (<15ms)
    t1 = time.time()
    action_data = match_fast_path(cleaned_text)

    if action_data:
        fast_ms = (time.time() - t1) * 1000
        print(f"⚡ [Fast-Path]: Action={action_data.get('action')}, Keycode={action_data.get('keycode')}, Repeat={action_data.get('repeat',1)} ({fast_ms:.1f}ms)")
    else:
        # Nếu câu lệnh phức tạp, chuyển sang Gemini 2.5 Flash
        print(f"🧠 [Gemini AI]: Đang phân tích ngữ nghĩa câu lệnh: \"{cleaned_text}\"...")
        action_data = query_gemini_voice_bridge(cleaned_text)
        ai_ms = (time.time() - t1) * 1000
        if action_data:
            print(f"🤖 [Gemini AI]: Action={action_data.get('action')}, Query={action_data.get('query')} ({ai_ms:.0f}ms)")

    if action_data:
        tts = action_data.get("tts_reply")
        if tts:
            print(f"💬 [Lâm]: \"{tts}\"")

        # 3. Điều khiển Tivi / TV Box đích
        dispatch_command(action_data, raw_text=raw_text, cleaned_text=cleaned_text)
        total_ms = (time.time() - t0) * 1000
        print(f"✨ [Hoàn thành]: Tổng thời gian: {total_ms:.0f}ms\n")
    else:
        print(f"[Voice Hub] ⚠️ Không nhận diện được hành động phù hợp cho: \"{raw_text}\"\n")

# =============================================================================
# 6. UDP VOICE STREAM LISTENER (CHO ESP32-C3 HW-953AB)
# =============================================================================

def udp_voice_listener():
    """
    Lắng nghe luồng âm thanh PCM từ ESP32 qua UDP 8995.
    Tích hợp Watchdog chống rớt gói tin và tự động giải mã ngay khi hết câu nói.
    """
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((UDP_BIND_IP, UDP_BIND_PORT))
    sock.settimeout(0.3)
    print(f"🎙️  [Voice Hub] Đang lắng nghe micro ESP32 tại UDP {UDP_BIND_PORT}...")

    audio_buffer = bytearray()
    is_recording = False
    last_chunk_time = 0

    while True:
        try:
            data, addr = sock.recvfrom(2048)
            if not data:
                continue

            now = time.time()

            if data == b"__PING__":
                continue

            if data == b"__START__":
                is_recording = True
                audio_buffer = bytearray()
                last_chunk_time = now
                print(f"\n[ESP32 -> {addr[0]}] 🔴 Bắt đầu thu âm câu lệnh...")
                continue

            if data == b"__END__":
                if len(audio_buffer) >= 16000:
                    duration_s = len(audio_buffer) / 32000.0
                    print(f"[ESP32] ⏹️ Kết thúc thu âm ({duration_s:.2f}s). Đang giải mã...")
                    wav_data = pcm_to_wav(bytes(audio_buffer))
                    threading.Thread(target=process_voice_command, args=(wav_data,), daemon=True).start()
                is_recording = False
                audio_buffer = bytearray()
                continue

            if len(data) >= 256:
                if not is_recording:
                    is_recording = True
                    audio_buffer = bytearray()
                    print(f"\n[ESP32 -> {addr[0]}] 🔴 Bắt đầu thu âm (nhận luồng PCM)...")

                audio_buffer.extend(data)
                last_chunk_time = now

        except socket.timeout:
            # Watchdog timeout: Nếu đang thu âm mà quá 1.0s không có thêm gói tin -> Tự động giải mã
            now = time.time()
            if is_recording and len(audio_buffer) >= 16000 and (now - last_chunk_time > 1.0):
                duration_s = len(audio_buffer) / 32000.0
                print(f"[ESP32] ⏱️ Timeout khoảng lặng ({duration_s:.2f}s). Tự động giải mã...")
                wav_data = pcm_to_wav(bytes(audio_buffer))
                threading.Thread(target=process_voice_command, args=(wav_data,), daemon=True).start()
                is_recording = False
                audio_buffer = bytearray()
        except Exception as e:
            time.sleep(0.02)

# =============================================================================
# 7. WEB REMOTE SERVER (HTTP 8996) CHO SAMSUNG PHONE & BROWSER
# =============================================================================

def start_http_remote_server():
    """Máy chủ HTTP phục vụ Web Remote & điều khiển trực quan trên điện thoại."""
    from http.server import HTTPServer, BaseHTTPRequestHandler

    class WebRemoteHandler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)

            # API: Danh sách thiết bị tự động quét được
            if parsed.path == "/devices":
                with DEVICES_LOCK:
                    dev_list = list(DISCOVERED_DEVICES.values())
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(json.dumps(dev_list, ensure_ascii=False).encode('utf-8'))
                return

            # API: Chọn thiết bị điều khiển mặc định
            if parsed.path == "/set_target":
                params = urllib.parse.parse_qs(parsed.query)
                dev = params.get("device", [""])[0].strip()
                global LAST_ACTIVE_DEVICE_ID
                if dev and dev in DISCOVERED_DEVICES:
                    LAST_ACTIVE_DEVICE_ID = dev
                    print(f"🎯 [Target Switch]: Đã chuyển thiết bị mặc định -> {dev}")
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Access-Control-Allow-Origin", "*")
                self.end_headers()
                self.wfile.write(b'{"status":"ok"}')
                return

            # API: Nhận lệnh giọng nói hoặc văn bản từ Web Remote
            if parsed.path == "/command":
                params = urllib.parse.parse_qs(parsed.query)
                text = params.get("text", [""])[0].strip()
                dev_target = params.get("device", [""])[0].strip()
                if dev_target and dev_target in DISCOVERED_DEVICES:
                    LAST_ACTIVE_DEVICE_ID = dev_target
                if text:
                    print(f"\n📱 [Samsung Web Remote]: \"{text}\"")
                    cleaned = clean_spoken_text(text)
                    action_data = match_fast_path(cleaned)
                    if not action_data:
                        action_data = query_gemini_voice_bridge(cleaned)

                    if action_data:
                        dispatch_command(action_data, raw_text=text, cleaned_text=cleaned)
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json; charset=utf-8")
                        self.send_header("Access-Control-Allow-Origin", "*")
                        self.end_headers()
                        self.wfile.write(json.dumps({"status": "ok", "action": action_data}, ensure_ascii=False).encode('utf-8'))
                        return

                self.send_response(400)
                self.end_headers()
                return

            # API: Bấm nút Remote vật lý trực tiếp
            if parsed.path == "/remote_key":
                params = urllib.parse.parse_qs(parsed.query)
                key = params.get("key", [""])[0].lower()
                dev_target = params.get("device", [""])[0]

                key_map = {
                    "up": KEY_DPAD_UP, "down": KEY_DPAD_DOWN, "left": KEY_DPAD_LEFT, "right": KEY_DPAD_RIGHT,
                    "ok": KEY_DPAD_CENTER, "back": KEY_BACK, "home": KEY_HOME,
                    "vol_up": KEY_VOLUME_UP, "vol_down": KEY_VOLUME_DOWN, "mute": KEY_MUTE,
                    "power": KEY_WAKEUP, "sleep": KEY_SLEEP
                }

                if key in key_map:
                    action_data = {
                        "action": "remote_key",
                        "keycode": key_map[key],
                        "repeat": 1,
                        "tts_reply": f"Lâm bấm nút {key}"
                    }
                    dispatch_command(action_data, raw_text=f"{dev_target} {key}")
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json; charset=utf-8")
                    self.send_header("Access-Control-Allow-Origin", "*")
                    self.end_headers()
                    self.wfile.write(json.dumps({"status": "ok", "key": key}).encode('utf-8'))
                    return

            # Trang giao diện Web Remote hiện đại
            html = """<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>DuyTV Voice & Remote Hub - Trợ lý Lâm</title>
<style>
:root { --bg: #0b0f19; --card: #151d2f; --accent: #00d2ff; --accent2: #0072ff; --text: #f0f6fc; --gray: #8b949e; --red: #ff3366; }
body { margin:0; background:var(--bg); color:var(--text); font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif; text-align:center; padding:16px; user-select:none; }
h1 { color:var(--accent); font-size:22px; margin:4px 0; font-weight:700; letter-spacing:0.5px; }
.sub { color:var(--gray); font-size:13px; margin-bottom:12px; }
.devices-bar { display:flex; justify-content:center; gap:8px; margin-bottom:16px; flex-wrap:wrap; }
.dev-pill { background:#1e293b; border:1px solid #334155; padding:6px 14px; border-radius:20px; font-size:12px; color:#cbd5e1; cursor:pointer; transition:all 0.2s; }
.dev-pill.active { background:linear-gradient(135deg,var(--accent),var(--accent2)); color:#000; font-weight:bold; border-color:transparent; }
.mic-btn { width:100px; height:100px; border-radius:50%; background:linear-gradient(135deg,var(--accent),var(--accent2)); border:none; box-shadow:0 0 25px rgba(0,210,255,0.4); display:inline-flex; align-items:center; justify-content:center; cursor:pointer; margin:10px 0 16px; outline:none; -webkit-tap-highlight-color:transparent; }
.mic-btn:active { transform:scale(0.94); box-shadow:0 0 40px rgba(0,210,255,0.8); }
.mic-btn svg { width:46px; height:46px; fill:#fff; }
#status { font-size:14px; min-height:40px; color:#38bdf8; font-weight:500; margin:4px 10px 14px; }
.dpad-container { width:220px; height:220px; margin:0 auto 16px; position:relative; background:#162032; border-radius:50%; border:2px solid #22324d; box-shadow:inset 0 0 15px rgba(0,0,0,0.5); }
.dpad-btn { position:absolute; background:transparent; border:none; color:#cbd5e1; font-size:22px; cursor:pointer; width:65px; height:65px; display:flex; align-items:center; justify-content:center; -webkit-tap-highlight-color:transparent; }
.dpad-btn:active { color:var(--accent); transform:scale(0.92); }
.dp-up { top:0; left:77px; }
.dp-down { bottom:0; left:77px; }
.dp-left { top:77px; left:0; }
.dp-right { top:77px; right:0; }
.dp-ok { top:72px; left:72px; width:76px; height:76px; border-radius:50%; background:linear-gradient(135deg, #1e2d44, #121b29); border:2px solid var(--accent); color:var(--accent); font-weight:bold; font-size:17px; }
.dp-ok:active { background:var(--accent); color:#000; }
.row-controls { display:flex; justify-content:center; gap:12px; max-width:320px; margin:0 auto 14px; }
.btn-ctrl { flex:1; background:#1b2438; border:1px solid #2b3954; color:#f1f5f9; padding:12px 0; border-radius:12px; font-size:14px; font-weight:600; cursor:pointer; }
.btn-ctrl:active { background:#283856; border-color:var(--accent); color:var(--accent); }
.btn-power { background:#3b141e; border-color:#631e2d; color:#ff6b8b; }
.btn-power:active { background:var(--red); color:#fff; }
.shortcuts { display:grid; grid-template-columns:1fr 1fr; gap:8px; max-width:340px; margin:10px auto 30px; }
.sc-btn { background:#141d2d; border:1px solid #23314a; color:#94a3b8; padding:10px; border-radius:10px; font-size:12px; font-weight:bold; cursor:pointer; text-align:left; }
.sc-btn:active { background:#1e2e4a; border-color:var(--accent); color:var(--accent); }
</style>
</head>
<body>
<h1>DuyTV Voice & Remote Hub</h1>
<div class="sub">Trợ lý Lâm: "Lâm ơi mở Vĩnh Long, chuyền qua nữa đi em..."</div>

<div class="devices-bar" id="devBar">
  <div class="dev-pill active" onclick="setTarget('')">Tất cả Tivi</div>
</div>

<button class="mic-btn" id="micBtn" onclick="toggleSpeech()">
  <svg viewBox="0 0 24 24"><path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z"/><path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/></svg>
</button>

<div id="status">Chạm vào Micro để gọi Lâm...</div>

<div class="dpad-container">
  <button class="dpad-btn dp-up" onclick="sendKey('up')">▲</button>
  <button class="dpad-btn dp-down" onclick="sendKey('down')">▼</button>
  <button class="dpad-btn dp-left" onclick="sendKey('left')">◀</button>
  <button class="dpad-btn dp-right" onclick="sendKey('right')">▶</button>
  <button class="dpad-btn dp-ok" onclick="sendKey('ok')">OK</button>
</div>

<div class="row-controls">
  <button class="btn-ctrl" onclick="sendKey('back')">↩ Quay Lại</button>
  <button class="btn-ctrl" onclick="sendKey('home')">⌂ Trang Chủ</button>
  <button class="btn-ctrl btn-power" onclick="sendKey('sleep')">⏻ Tắt Tivi</button>
</div>

<div class="row-controls">
  <button class="btn-ctrl" onclick="sendKey('vol_down')">🔉 Giảm Âm</button>
  <button class="btn-ctrl" onclick="sendKey('vol_up')">🔊 Tăng Âm</button>
  <button class="btn-ctrl" onclick="sendKey('mute')">🔇 Tắt Tiếng</button>
</div>

<div class="shortcuts">
  <button class="sc-btn" onclick="sendCmd('mở đài vĩnh long')">📺 Vĩnh Long THVL1</button>
  <button class="sc-btn" onclick="sendCmd('bật thời sự')">📺 Thời Sự VTV1</button>
  <button class="sc-btn" onclick="sendCmd('mở phim lẻ')">🎬 Kho Phim Lẻ</button>
  <button class="sc-btn" onclick="sendCmd('mở phim bộ')">🎬 Kho Phim Bộ</button>
  <button class="sc-btn" onclick="sendCmd('tập này coi rồi')">⏭️ Qua Tập Tiếp</button>
  <button class="sc-btn" onclick="sendCmd('mở youtube')">🔴 Vào YouTube</button>
</div>

<script>
let currentTarget = '';
function setTarget(t) {
  currentTarget = t;
  document.querySelectorAll('.dev-pill').forEach(el => el.classList.remove('active'));
  event.target.classList.add('active');
  if (t) fetch('/set_target?device=' + encodeURIComponent(t)).catch(()=>{});
}

function loadDevices() {
  fetch('/devices')
    .then(r => r.json())
    .then(devs => {
      let bar = document.getElementById('devBar');
      let html = '<div class="dev-pill active" onclick="setTarget(\'\')">Tất cả Tivi</div>';
      devs.forEach(d => {
        html += `<div class="dev-pill" onclick="setTarget('${d.id}')">${d.name} (${d.ip})</div>`;
      });
      bar.innerHTML = html;
    }).catch(()=>{});
}
loadDevices();
setInterval(loadDevices, 10000);

let recognition = null;
let isListening = false;
if ('webkitSpeechRecognition' in window || 'SpeechRecognition' in window) {
  const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
  recognition = new SpeechRec();
  recognition.lang = 'vi-VN';
  recognition.onstart = () => {
    isListening = true;
    document.getElementById('status').innerText = '🎙️ Lâm đang lắng nghe anh Duy nói...';
    document.getElementById('micBtn').style.boxShadow = '0 0 45px #ff0055';
  };
  recognition.onresult = (e) => {
    const text = e.results[0][0].transcript;
    document.getElementById('status').innerText = '🗣️ "' + text + '"';
    sendCmd(text);
  };
  recognition.onend = () => {
    isListening = false;
    document.getElementById('micBtn').style.boxShadow = '0 0 25px rgba(0,210,255,0.4)';
  };
}

function toggleSpeech() {
  if (!recognition) { alert('Trình duyệt chưa hỗ trợ ghi âm trực tiếp!'); return; }
  if (!isListening) recognition.start();
  else recognition.stop();
}

function sendCmd(text) {
  let fullText = currentTarget ? (currentTarget + ' ' + text) : text;
  document.getElementById('status').innerText = '⚡ Đang gửi: "' + text + '"...';
  fetch('/command?text=' + encodeURIComponent(fullText) + '&device=' + encodeURIComponent(currentTarget))
    .then(r => r.json())
    .then(d => {
      document.getElementById('status').innerText = '💬 ' + (d.action.tts_reply || 'Đã thực hiện!');
    })
    .catch(() => {
      document.getElementById('status').innerText = '❌ Lỗi kết nối Tivi!';
    });
}

function sendKey(key) {
  fetch(`/remote_key?key=${key}&device=${currentTarget}`)
    .catch(()=>{});
}
</script>
</body>
</html>"""
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(html.encode('utf-8'))

    server = HTTPServer(('0.0.0.0', HTTP_WEB_PORT), WebRemoteHandler)
    local_ip, _ = get_local_ip_and_subnet()
    print(f"📱 [Web Remote] Khởi chạy giao diện tại: http://{local_ip}:{HTTP_WEB_PORT}")
    server.serve_forever()

# =============================================================================
# MAIN ENTRY POINT
# =============================================================================

if __name__ == "__main__":
    local_ip, subnet = get_local_ip_and_subnet()
    print("=" * 65)
    print("    DUYTV VOICE & REMOTE HUB - TRỢ LÝ GIỌNG NÓI 'LÂM' (AI LAN)    ")
    print("=" * 65)
    print(f"🖥️  Máy chủ nội bộ : {local_ip}")
    print(f"📡 Dải quét Router : {subnet}0/24")
    print(f"🧠 Cầu nối Gemini  : {HERMES_BRIDGE_URL}")
    print(f"🎙️  Cổng UDP Micro  : {UDP_BIND_PORT} (ESP32-C3 HW-953AB)")
    print(f"📱 Web Remote HTTP : {HTTP_WEB_PORT}")
    print("=" * 65)

    # 1. Quét tìm kiếm Tivi & TV Box ngay khi khởi động
    print("🔍 [Khởi động] Đang quét tự động các Tivi & TV Box trên router...")
    scan_network_for_tvs()

    # 2. Bật luồng ngầm định kỳ quét mạng (mỗi 45s)
    scanner_thread = threading.Thread(target=auto_discovery_background_daemon, daemon=True)
    scanner_thread.start()

    # 3. Chạy Web Server cho điện thoại Samsung trên luồng phụ
    web_thread = threading.Thread(target=start_http_remote_server, daemon=True)
    web_thread.start()

    # 4. Chạy UDP Voice Listener cho ESP32 trên luồng chính
    udp_voice_listener()
