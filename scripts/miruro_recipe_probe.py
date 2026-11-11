"""Replicate walterwhite-69/Miruro-API's exact pipe recipe, no cookie.

Their claim: impersonate=chrome110 + this exact header set (incl. Origin + sec-ch-ua)
opens /api/secure/pipe with no clearance, from residential IPs, no browser.
Their target host: www.miruro.tv. If this works, the same recipe in OkHttp removes
Gecko entirely. Falsifies or confirms in one run.
"""

import base64
import gzip
import json
import sys
import time

from curl_cffi import requests

HEADERS = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Safari/537.36",
    "Referer": "https://www.miruro.tv/",
    "Origin": "https://www.miruro.tv",
    "Accept": "*/*",
    "Accept-Language": "en-US,en;q=0.9",
    "Accept-Encoding": "gzip, deflate, br",
    "sec-fetch-site": "same-origin",
    "sec-fetch-mode": "cors",
    "sec-fetch-dest": "empty",
    "sec-ch-ua": '"Chromium";v="110", "Not A(Brand";v="24", "Google Chrome";v="110"',
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"Windows"',
}

E = base64.urlsafe_b64encode(
    json.dumps(
        {"path": "episodes", "method": "GET", "query": {"anilistId": 199111}, "body": None, "version": "0.1.0"},
        separators=(",", ":"),
    ).encode()
).decode().rstrip("=")

OBF_KEY = bytes.fromhex("71951034f8fbcf53d89db52ceb3dc22c")


def decode_pipe(body: str, obf: str):
    if not obf:
        return body
    raw = base64.urlsafe_b64decode(body.strip() + "=" * (-len(body.strip()) % 4))
    if obf == "2":
        raw = bytes(b ^ OBF_KEY[i % len(OBF_KEY)] for i, b in enumerate(raw))
    return gzip.decompress(raw).decode("utf-8", "replace")


def main():
    arms = []
    for imp in ("chrome110", "chrome"):
        for host in ("www.miruro.tv", "www.miruro.to", "www.miruro.bz"):
            arms.append((imp, host))
    for imp, host in arms:
        h = dict(HEADERS)
        h["Referer"] = f"https://{host}/"
        h["Origin"] = f"https://{host}"
        s = requests.Session(impersonate=imp)
        try:
            r = s.get(f"https://{host}/api/secure/pipe?e={E}", headers=h, timeout=20)
        except Exception as e:  # noqa: BLE001
            print(f"{imp:<10} {host:<16} ERR {type(e).__name__}: {str(e)[:60]}", flush=True)
            continue
        line = f"{imp:<10} {host:<16} {r.status_code} cf-mitigated={r.headers.get('cf-mitigated', '-')} len={len(r.content)}"
        if r.status_code == 200:
            try:
                obj = json.loads(decode_pipe(r.text, r.headers.get("x-obfuscated", "")))
                line += f" DECODED providers={sorted((obj.get('providers') or {}).keys())}"
            except Exception as e:  # noqa: BLE001
                line += f" decode-failed {type(e).__name__}"
        print(line, flush=True)
        time.sleep(0.8)
    return 0


if __name__ == "__main__":
    sys.exit(main())
