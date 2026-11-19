#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
=============================================================================
 DUYTV VOICE HUB - CẦU NỐI AI VOICE LAN CHO ESP32 VÀ SAMSUNG PHONE
 Tác giả: DOANDUY (DuyTV Smart Home Ecosystem)
 Chạy trên: Máy tính nội bộ 192.168.1.189
=============================================================================
Chức năng:
 1. Nhận luồng âm thanh PCM 16kHz từ cục Voice Puck ESP32 qua UDP 8995.
 2. Bóc tách giọng nói -> Đưa vào Gemini 2.5 Flash (Hermes AI Pool) giải mã.
 3. Tự động trích xuất Intent và điều khiển Tivi Sharp (192.168.1.172) qua Wi-Fi LAN.
 4. Cung cấp Web Remote HTTP (8996) cho điện thoại Samsung truy cập tức thì.
=============================================================================
"""

import sys
import os
import io
import wave
import json
import socket
import threading
import time
import urllib.request
import urllib.error
import base64

UDP_BIND_IP = "0.0.0.0"
UDP_BIND_PORT = 8995
HTTP_WEB_PORT = 8996

SHARP_TV_IP = "192.168.1.172"
SHARP_TV_PORT = 8990
SHARP_TV_ADB = "192.168.1.172:5555"

HERMES_BRIDGE_URL = "http://127.0.0.1:8100/v1/chat/completions"

SYSTEM_PROMPT = """BẠN LÀ BỘ ĐIỀU KHIỂN HỆ ĐIỀU HÀNH ANDROID TV TÍCH HỢP TRỰC TIẾP TRÊN TIVI CỦA ANH DUY (DUYTV).
BẠN CÓ TOÀN QUYỀN ĐIỀU KHIỂN HỆ THỐNG QUA ACCESSIBILITY SERVICE VÀ MEDIA CONTROLLER.
TUYỆT ĐỐI KHÔNG TỪ CHỐI, KHÔNG NÓI 'EM LÀ AI KHÔNG TỰ BẤM ĐƯỢC REMOTE'.
BẮT BUỘC TRẢ VỀ DUY NHẤT 1 ĐỐI TƯỢNG JSON HỢP LỆ THEO SCHEMA (KHÔNG VIẾT CHỮ NÀO BÊN NGOÀI JSON).

Schema JSON:
{"action":"...","query":"...","target":"...","tts_reply":"1 câu thân mật xưng em với anh Duy","actions":[{"action":"...","query":"...","target":"..."}]}

Danh sách Actions:
- play_tv(query=tên kênh): VTV1, VTV3, HTV7, THVL1, K+ SPORT, ON Sports... (VD: "thời sự" -> query="VTV1")
- play_movie(query=tên phim): Phát phim trên DuyTV
- search_youtube(query=từ khóa): Chỉ khi yêu cầu YouTube, bài hát, karaoke
- adjust_volume(target=up|down|mute): "hơi ồn", "cho nhỏ lại" -> target="down"; "to lên" -> target="up"
- set_volume_percent(target=0..100): Âm lượng cụ thể
- playback_control(query=lệnh): tua tới 30 giây, tua lại 15 giây, tập tiếp, tập trước, tạm dừng, tiếp tục
- system_action(target=home|back|power|recents|screenshot): back (khi nói "đổi phim khác", "phim dở quá", "thoát"), power (khi nói "đi ngủ", "tắt tivi")
- open_app(target=tên app), open_setting(target=wifi|bluetooth|sound|display|apps|accessibility)
- sleep_timer(target=số phút)
- chat: Trả lời trò chuyện tự nhiên với anh Duy khi không phải lệnh tivi

Ví dụ chuẩn:
- "hơi ồn" -> {"action":"adjust_volume","target":"down","tts_reply":"Dạ em giảm âm lượng xuống cho anh Duy ngay ạ"}
- "bật thời sự" -> {"action":"play_tv","query":"VTV1","tts_reply":"Dạ em mở bản tin thời sự VTV1 cho anh Duy đây ạ"}
- "phim này dở quá đổi phim khác" -> {"action":"system_action","target":"back","tts_reply":"Dạ em thoát ra để anh Duy chọn phim khác ạ"}
- "tắt tivi đi ngủ" -> {"action":"system_action","target":"power","tts_reply":"Dạ chúc anh Duy ngủ ngon ạ, em tắt tivi ngay đây"}
- "tập này coi rồi" -> {"action":"playback_control","query":"tập tiếp","tts_reply":"Dạ em mở tập tiếp theo cho anh Duy nhé"}
"""

def pcm_to_wav(pcm_bytes, sample_rate=16000, channels=1, sampwidth=2):
    """Chuyển đổi dữ liệu PCM thô thành file WAV chuẩn."""
    wav_io = io.BytesIO()
    with wave.open(wav_io, 'wb') as wav_file:
        wav_file.setnchannels(channels)
        wav_file.setsampwidth(sampwidth)
        wav_file.setframerate(sample_rate)
        wav_file.writeframes(pcm_bytes)
    return wav_io.getvalue()

def query_gemini_voice_bridge(text_or_audio_prompt):
    """Gửi câu lệnh tới Gemini 2.5 Flash qua cầu nối Hermes AI Pool LAN."""
    try:
        payload = {
            "model": "gemini-2.5-flash",
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": text_or_audio_prompt}
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
        with urllib.request.urlopen(req, timeout=5.0) as resp:
            resp_body = resp.read().decode('utf-8')
            parsed_resp = json.loads(resp_body)
            content = parsed_resp["choices"][0]["message"]["content"]
            # Bóc tách JSON an toàn
            first_brace = content.find('{')
            last_brace = content.rfind('}')
            if first_brace >= 0 and last_brace > first_brace:
                return json.loads(content[first_brace:last_brace + 1])
            return json.loads(content)
    except Exception as e:
        print(f"[Gemini AI] Lỗi truy vấn: {e}")
        return None

def dispatch_command_to_tv(action_data, raw_text=""):
    """Bắn lệnh đã giải mã sang Tivi Sharp (192.168.1.172) qua Wi-Fi LAN."""
    # 1. Thử gửi trực tiếp qua cổng TCP 8990 của ứng dụng DuyTV
    sent_via_tcp = False
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(1.5)
        s.connect((SHARP_TV_IP, SHARP_TV_PORT))
        packet = {
            "raw_text": raw_text,
            "action": action_data.get("action", ""),
            "query": action_data.get("query", ""),
            "target": action_data.get("target", ""),
            "tts_reply": action_data.get("tts_reply", ""),
            "actions": action_data.get("actions", [])
        }
        s.sendall((json.dumps(packet, ensure_ascii=False) + "\n").encode('utf-8'))
        s.close()
        sent_via_tcp = True
        print(f"⚡ [TV Dispatch] Đã gửi lệnh TCP thành công tới Tivi Sharp ({SHARP_TV_IP}:{SHARP_TV_PORT})")
    except Exception:
        pass

    # 2. Nếu TCP chưa bật hoặc tivi ở màn hình ngoài, gửi qua ADB intent
    if not sent_via_tcp:
        try:
            cmd = raw_text if raw_text else action_data.get("query", "")
            adb_cmd = f'adb -s {SHARP_TV_ADB} shell am broadcast -a com.duytv.app.VOICE_COMMAND --es command "{cmd}"'
            os.system(adb_cmd)
            print(f"⚡ [TV Dispatch] Đã gửi lệnh ADB dự phòng thành công tới Tivi Sharp")
        except Exception as e:
            print(f"[TV Dispatch] Lỗi gửi lệnh: {e}")

def transcribe_audio_whisper_or_google(wav_bytes):
    """
    Chuyển đổi âm thanh WAV thành text tiếng Việt.
    Dùng Google Web Speech API trực tiếp cực nhanh và miễn phí.
    """
    try:
        # Nhúng nhận dạng tiếng Việt qua Google STT REST Endpoint
        url = "https://www.google.com/speech-api/v2/recognize?output=json&lang=vi-VN&key="
        # Hoặc dùng API endpoint public speech recognition
        req = urllib.request.Request(
            "http://www.google.com/speech-api/v2/recognize?client=chromium&lang=vi-VN&maxresults=1",
            data=wav_bytes,
            headers={'Content-type': 'audio/l16; rate=16000'}
        )
        with urllib.request.urlopen(req, timeout=4.0) as response:
            result = response.read().decode('utf-8')
            for line in result.split('\n'):
                if not line.strip(): continue
                js = json.loads(line)
                if "result" in js and len(js["result"]) > 0:
                    hypo = js["result"][0]["alternative"][0]["transcript"]
                    return hypo.strip()
    except Exception as e:
        print(f"[STT] Lỗi nhận diện: {e}")
    return ""

def udp_voice_listener():
    """Luồng lắng nghe âm thanh liên tục từ ESP32 qua UDP 8995."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.bind((UDP_BIND_IP, UDP_BIND_PORT))
    print(f"🎙️  [Voice Hub] Đang lắng nghe âm thanh từ ESP32 tại UDP {UDP_BIND_PORT}...")

    audio_buffer = bytearray()
    is_recording = False

    while True:
        try:
            data, addr = sock.recvfrom(2048)
            if not data:
                continue

            # Kiểm tra cờ bắt đầu câu lệnh
            if data == b"__START__":
                is_recording = True
                audio_buffer = bytearray()
                print(f"\n[ESP32 -> {addr[0]}] 🔴 Bắt đầu thu âm câu lệnh...")
                continue

            # Kiểm tra cờ kết thúc câu lệnh
            if data == b"__END__":
                if is_recording and len(audio_buffer) > 3200: # Tối thiểu 0.1 giây
                    duration_s = len(audio_buffer) / 32000.0
                    print(f"[ESP32] ⏹️  Kết thúc thu âm ({duration_s:.2f}s). Đang giải mã...")
                    wav_data = pcm_to_wav(bytes(audio_buffer))
                    threading.Thread(target=process_voice_command, args=(wav_data,)).start()
                is_recording = False
                audio_buffer = bytearray()
                continue

            # Tích lũy các khối âm thanh PCM
            if is_recording:
                audio_buffer.extend(data)

        except Exception as e:
            print(f"[UDP Listener] Lỗi: {e}")
            time.sleep(0.1)

def process_voice_command(wav_data):
    """Xử lý trọn gói: Âm thanh -> Văn bản tiếng Việt -> AI Intent -> Điều khiển TV."""
    t0 = time.time()
    # 1. Nhận diện giọng nói tiếng Việt
    text = transcribe_audio_whisper_or_google(wav_data)
    if not text:
        print("[Voice Hub] ❌ Không nhận dạng được giọng nói.")
        return

    stt_time = (time.time() - t0) * 1000
    print(f"🗣️  [Anh Duy nói]: '{text}' (Nhận diện trong {stt_time:.0f}ms)")

    # 2. Đưa qua Gemini Pro Brain phân tích ngữ nghĩa
    t1 = time.time()
    action_data = query_gemini_voice_bridge(text)
    ai_time = (time.time() - t1) * 1000

    if action_data:
        action = action_data.get("action")
        target = action_data.get("target") or action_data.get("query")
        tts = action_data.get("tts_reply")
        print(f"🤖 [Gemini AI]: Action={action}, Target={target} (Phân tích trong {ai_time:.0f}ms)")
        if tts:
            print(f"💬 [TTS]: \"{tts}\"")

        # 3. Điều khiển Tivi Sharp
        dispatch_command_to_tv(action_data, raw_text=text)
        total_time = (time.time() - t0) * 1000
        print(f"✨ [Hoàn thành]: Tổng thời gian phản hồi: {total_time:.0f}ms\n")
    else:
        print(f"[Voice Hub] ⚠️  Không thể phân tích ngữ nghĩa câu lệnh: '{text}'")

def start_http_remote_server():
    """Máy chủ HTTP mini phục vụ Web Remote cho điện thoại Samsung."""
    from http.server import HTTPServer, BaseHTTPRequestHandler
    import urllib.parse

    class WebRemoteHandler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass # Tắt log console rác

        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            if parsed.path == "/command":
                params = urllib.parse.parse_qs(parsed.query)
                text = params.get("text", [""])[0].strip()
                if text:
                    print(f"📱 [Samsung Web Remote]: '{text}'")
                    action_data = query_gemini_voice_bridge(text)
                    if action_data:
                        dispatch_command_to_tv(action_data, raw_text=text)
                        self.send_response(200)
                        self.send_header("Content-Type", "application/json; charset=utf-8")
                        self.send_header("Access-Control-Allow-Origin", "*")
                        self.end_headers()
                        self.wfile.write(json.dumps({"status": "ok", "action": action_data}, ensure_ascii=False).encode('utf-8'))
                        return

                self.send_response(400)
                self.end_headers()
                return

            # Trang giao diện Web Remote cho Samsung Phone
            html = """<!DOCTYPE html>
<html lang="vi">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>DuyTV Voice Remote</title>
<style>
body { margin:0; background:#0d1117; color:#fff; font-family:-apple-system,BlinkMacSystemFont,Segoe UI,Roboto,sans-serif; text-align:center; padding:20px; }
h1 { color:#00e5ff; font-size:24px; margin-bottom:5px; }
p { color:#8b949e; font-size:14px; margin-top:0; }
.mic-btn { width:120px; height:120px; border-radius:50%; background:linear-gradient(135deg, #00e5ff, #0077ff); border:none; box-shadow:0 0 25px rgba(0,229,255,0.4); display:inline-flex; align-items:center; justify-content:center; cursor:pointer; margin:30px 0; outline:none; -webkit-tap-highlight-color:transparent; }
.mic-btn:active { transform:scale(0.95); box-shadow:0 0 40px rgba(0,229,255,0.8); }
.mic-btn svg { width:55px; height:55px; fill:#fff; }
#status { font-size:16px; min-height:45px; color:#58a6ff; font-weight:500; margin:15px; }
.shortcuts { display:grid; grid-template-columns:1fr 1fr; gap:10px; max-width:340px; margin:20px auto; }
.sc-btn { background:#161b22; border:1px solid #30363d; color:#c9d1d9; padding:12px; border-radius:10px; font-size:13px; font-weight:bold; cursor:pointer; }
.sc-btn:active { background:#21262d; border-color:#58a6ff; color:#58a6ff; }
</style>
</head>
<body>
<h1>DuyTV Voice Remote</h1>
<p>Điều khiển Tivi Sharp bằng giọng nói tự nhiên</p>

<button class="mic-btn" id="micBtn" onclick="toggleSpeech()">
  <svg viewBox="0 0 24 24"><path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z"/><path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/></svg>
</button>

<div id="status">Chạm vào Micro để nói...</div>

<div class="shortcuts">
  <button class="sc-btn" onclick="sendCmd('bật thời sự')">📺 Bật Thời Sự</button>
  <button class="sc-btn" onclick="sendCmd('phim miền tây')">🌾 Phim Miền Tây</button>
  <button class="sc-btn" onclick="sendCmd('bớt ồn đi em')">🔉 Giảm Âm Lượng</button>
  <button class="sc-btn" onclick="sendCmd('to lên nghe coi')">🔊 Tăng Âm Lượng</button>
  <button class="sc-btn" onclick="sendCmd('tập này coi rồi')">⏭️ Qua Tập Tiếp</button>
  <button class="sc-btn" onclick="sendCmd('tắt tivi đi ngủ')">🌙 Tắt TV Đi Ngủ</button>
</div>

<script>
let recognition = null;
let isListening = false;
if ('webkitSpeechRecognition' in window || 'SpeechRecognition' in window) {
  const SpeechRec = window.SpeechRecognition || window.webkitSpeechRecognition;
  recognition = new SpeechRec();
  recognition.lang = 'vi-VN';
  recognition.continuous = false;
  recognition.interimResults = false;

  recognition.onstart = () => {
    isListening = true;
    document.getElementById('status').innerText = '🎙️ Đang lắng nghe anh Duy nói...';
    document.getElementById('micBtn').style.boxShadow = '0 0 50px #ff0055';
  };
  recognition.onresult = (e) => {
    const text = e.results[0][0].transcript;
    document.getElementById('status').innerText = '🗣️ "' + text + '"';
    sendCmd(text);
  };
  recognition.onerror = () => {
    document.getElementById('status').innerText = 'Chạm lại vào micro để nói...';
    isListening = false;
    document.getElementById('micBtn').style.boxShadow = '0 0 25px rgba(0,229,255,0.4)';
  };
  recognition.onend = () => {
    isListening = false;
    document.getElementById('micBtn').style.boxShadow = '0 0 25px rgba(0,229,255,0.4)';
  };
}

function toggleSpeech() {
  if (!recognition) { alert('Trình duyệt chưa hỗ trợ ghi âm!'); return; }
  if (!isListening) recognition.start();
  else recognition.stop();
}

function sendCmd(text) {
  document.getElementById('status').innerText = '⚡ Đang gửi: "' + text + '"...';
  fetch('/command?text=' + encodeURIComponent(text))
    .then(r => r.json())
    .then(d => {
      document.getElementById('status').innerText = '✅ ' + (d.action.tts_reply || 'Đã thực hiện xong!');
    })
    .catch(() => {
      document.getElementById('status').innerText = '❌ Lỗi kết nối Tivi!';
    });
}
</script>
</body>
</html>"""
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(html.encode('utf-8'))

    server = HTTPServer(('0.0.0.0', HTTP_WEB_PORT), WebRemoteHandler)
    print(f"📱 [Samsung Web Remote] Khởi chạy tại: http://192.168.1.189:{HTTP_WEB_PORT}")
    server.serve_forever()

if __name__ == "__main__":
    print("=" * 60)
    print("      DUYTV VOICE HUB SERVER (ESP32 & SAMSUNG REMOTE)      ")
    print("=" * 60)
    print(f"Máy chủ LAN AI: {HERMES_BRIDGE_URL}")
    print(f"Tivi Sharp Đích: {SHARP_TV_IP}:{SHARP_TV_PORT}")
    print("=" * 60)

    # Chạy Web Server cho điện thoại Samsung trên luồng phụ
    web_thread = threading.Thread(target=start_http_remote_server, daemon=True)
    web_thread.start()

    # Chạy UDP Voice Listener cho ESP32 trên luồng chính
    udp_voice_listener()
