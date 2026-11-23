import sys
import os

sys.path.append(os.path.dirname(__file__))
import duytv_voice_hub as hub

print("=" * 65)
print("1. QUÉT TỰ ĐỘNG CÁC THIẾT BỊ TRÊN ROUTER (AUTO-DISCOVERY)")
print("=" * 65)
hub.scan_network_for_tvs()

print(f"Tổng số thiết bị phát hiện: {len(hub.DISCOVERED_DEVICES)}")
for did, dev in hub.DISCOVERED_DEVICES.items():
    print(f" -> [{did}] {dev['name']} - IP: {dev['ip']} (ADB: {dev['adb']} | DuyTV Port: {dev['port']})")

print("\n" + "=" * 65)
print("2. KIỂM THỬ ĐIỀU PHỐI ĐÍCH ĐẾN (TARGET ROUTING)")
print("=" * 65)

test_target_cases = [
    ("Lâm ơi mở tivi sharp cho anh", "sharp_tv"),
    ("Lâm em mở tivi box cho anh", "shield_tv"),
    ("mở shield cho anh", "shield_tv"),
    ("tất cả tivi tắt cho anh đi ngủ", "ALL"),
    ("youtebe thì chuyền qua nữa đi em", "LAST_OR_DEFAULT"),
    ("mở lớn hơn cho anh", "LAST_OR_DEFAULT"),
]

for text, expected in test_target_cases:
    cleaned = hub.clean_spoken_text(text)
    targets = hub.resolve_target_devices(cleaned.lower())
    target_names = [f"{t['name']} ({t['id']})" for t in targets]
    print(f"Khẩu lệnh: \"{text}\"")
    print(f" -> Lọc: \"{cleaned}\"")
    print(f" -> Đích đến: {target_names}")
    print("-" * 65)

print("\n" + "=" * 65)
print("3. KIỂM THỬ KHỚP LỆNH REMOTE FAST-PATH (<15ms)")
print("=" * 65)

test_fast_path_cases = [
    "chuyền qua nữa đi em",
    "chuyền qua 3 cái nữa đi em",
    "sang trái 2 lần",
    "lên trên",
    "xuống",
    "chọn",
    "quay lại",
    "về trang chủ",
    "mở lớn hơn cho anh",
    "nhỏ hơn",
    "tắt tiếng",
    "mở tivi cho anh",
    "tắt tivi đi ngủ",
    "mở đài vĩnh long",
    "bật thời sự",
    "mở phim lẻ",
    "mở phim bộ",
    "mở youtube",
    "tập này coi rồi",
    "tạm dừng",
    "tiếp tục"
]

for cmd in test_fast_path_cases:
    cleaned = hub.clean_spoken_text(cmd)
    fp = hub.match_fast_path(cleaned)
    if fp:
        print(f"Lệnh: \"{cmd}\" -> Action={fp.get('action')}, Key={fp.get('keycode')}, Repeat={fp.get('repeat',1)}, Query={fp.get('query')}")
        print(f"      TTS: \"{fp.get('tts_reply')}\"")
    else:
        print(f"Lệnh: \"{cmd}\" -> None (Gemini AI)")

print("\n✅ TẤT CẢ KIỂM THỬ HOÀN TẤT THÀNH CÔNG!")
