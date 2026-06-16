#!/usr/bin/env python3
"""Quick bridge connectivity test."""
import socket, select, sys, time

for attempt in range(12):
    try:
        s = socket.socket()
        s.settimeout(1)
        s.connect(("127.0.0.1", 4444))
        print(f"[+] Connected to 127.0.0.1:4444")
        s.settimeout(2)
        data = s.recv(4096)
        print("[RCV]", data.decode("utf-8", "replace"))
        s.close()
        sys.exit(0)
    except Exception as e:
        print(f"[-] Attempt {attempt+1}: {e}")
        time.sleep(1)

print("[!] Bridge not reachable.")
print("    Did you open CH341PARDemo and tap '打开设备'?")
sys.exit(1)
