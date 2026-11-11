"""Probe every miruro origin the client bundles mention, with NO clearance.

If any mirror serves /health (or better, /api/secure/pipe) to a plain client, the whole
clearance apparatus is unnecessary against that origin. Arms: libcurl-default (worst case),
chrome-impersonate + XHR headers (best case), both cookie-less.
"""

import base64
import gzip
import json
import sys
import time

from curl_cffi import requests

ORIGINS = [
    "https://www.miruro.cx",
    "https://www.miruro.com",
    "https://status.miruro.com",
    "https://www.miruro.to",
]

ENVELOPE = json.dumps(
    {"path": "episodes", "method": "GET", "query": {"anilistId": 199111}, "body": None, "version": "0.1.0"},
    separators=(",", ":"),
)
E = base64.urlsafe_b64encode(ENVELOPE.encode()).decode().rstrip("=")
OBF_KEY = bytes.fromhex("71951034f8fbcf53d89db52ceb3dc22c")


def decode_pipe(body: str, obf: str):
    if not obf:
        return body
    raw = base64.urlsafe_b64decode(body.strip() + "=" * (-len(body.strip()) % 4))
    if obf == "2":
        raw = bytes(b ^ OBF_KEY[i % len(OBF_KEY)] for i, b in enumerate(raw))
    return gzip.decompress(raw).decode("utf-8", "replace")


def hit(session, url: str, headers):
    try:
        r = session.get(url, headers=headers, timeout=15)
    except Exception as e:  # noqa: BLE001
        return f"ERR {type(e).__name__}: {str(e)[:70]}"
    line = f"{r.status_code} cf-mitigated={r.headers.get('cf-mitigated', '-')} server={r.headers.get('server', '-')[:14]} len={len(r.content)}"
    if "pipe?e=" in url and r.status_code == 200:
        try:
            obj = json.loads(decode_pipe(r.text, r.headers.get("x-obfuscated", "")))
            line += f" DECODED providers={sorted((obj.get('providers') or {}).keys())}"
        except Exception as e:  # noqa: BLE001
            line += f" decode-failed {type(e).__name__}"
    elif r.status_code == 200:
        line += f" body={' '.join(r.text[:60].split())!r}"
    else:
        line += f" body={' '.join(r.text[:40].split())!r}"
    return line


def main():
    chrome = {
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "Referer": "https://www.miruro.cx/",
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-origin",
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
    }
    for origin in ORIGINS:
        for label, imp, headers in (
            ("plain", None, None),
            ("chrome", "chrome", dict(chrome, Referer=f"{origin}/")),
        ):
            s = requests.Session(impersonate=imp)
            print(f"{origin:<30} {label:<7} health  {hit(s, origin + '/health', headers)}", flush=True)
            print(f"{origin:<30} {label:<7} pipe    {hit(s, origin + f'/api/secure/pipe?e={E}', headers)}", flush=True)
            time.sleep(0.8)
    return 0


if __name__ == "__main__":
    sys.exit(main())
