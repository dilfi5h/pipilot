import http.cookiejar
import os
import sys
import time
import urllib.parse
import urllib.request
import uuid

def upload(host, path, password="Lannister33", use_proxy=False):
    handlers = []
    if use_proxy:
        handlers.append(urllib.request.ProxyHandler({
            "http": "socks5h://localhost:1140",
            "https": "socks5h://localhost:1140",
        }))
    # socks5h needs PySocks; fall back to plain opener if unavailable
    try:
        opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()), *handlers)
    except Exception:
        opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    data = urllib.parse.urlencode({"password": password}).encode()
    r = opener.open(urllib.request.Request(f"https://{host}/login", data=data), timeout=20)
    print("login:", r.status)

    payload = open(path, "rb").read()
    b = uuid.uuid4().hex
    body = (
        f"--{b}\r\n"
        f'Content-Disposition: form-data; name="file"; filename="{os.path.basename(path)}"\r\n'
        "Content-Type: application/octet-stream\r\n\r\n"
    ).encode() + payload + f"\r\n--{b}--\r\n".encode()

    t0 = time.time()
    req = urllib.request.Request(
        f"https://{host}/upload", data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={b}"})
    r = opener.open(req, timeout=600)
    out = r.read().decode(errors="replace").strip()
    dt = time.time() - t0
    mb = len(payload) / 1e6
    print(f"upload: {r.status} {mb:.1f}MB in {dt:.1f}s = {mb/dt:.2f}MB/s")
    print("URL:", out)

if __name__ == "__main__":
    host, path = sys.argv[1], sys.argv[2]
    use_proxy = len(sys.argv) > 3 and sys.argv[3] == "proxy"
    upload(host, path, use_proxy=use_proxy)
