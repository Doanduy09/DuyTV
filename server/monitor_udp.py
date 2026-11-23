import socket
import time

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
sock.bind(("0.0.0.0", 8995))
sock.settimeout(1.0)
print("=" * 60)
print("DANH SÁCH GÓI TIN UDP TỪ MICRO ESP32 (CỔNG 8995)")
print("Sẵn sàng! Đang chờ gói tin từ 192.168.1.185...")
print("=" * 60)

start = time.time()
packet_count = 0
try:
    while time.time() - start < 15:
        try:
            data, addr = sock.recvfrom(2048)
            packet_count += 1
            if data in (b"__START__", b"__END__", b"__PING__"):
                print(f"[{time.strftime('%H:%M:%S')}] Tín hiệu từ {addr[0]}: {data.decode()}")
            else:
                print(f"[{time.strftime('%H:%M:%S')}] Nhận {len(data)} bytes âm thanh từ {addr[0]}")
        except socket.timeout:
            pass
except KeyboardInterrupt:
    pass
finally:
    sock.close()

print(f"Tổng cộng nhận: {packet_count} gói tin.")
