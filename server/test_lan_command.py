import socket
import json

s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.settimeout(2.0)
s.connect(('192.168.1.172', 8990))
payload = json.dumps({"command": "bật thời sự"}) + "\n"
s.sendall(payload.encode('utf-8'))
res = s.recv(1024).decode('utf-8')
print("TV PHẢN HỒI THÀNH CÔNG:", res.strip())
s.close()
