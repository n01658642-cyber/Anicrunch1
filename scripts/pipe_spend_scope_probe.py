"""Two design-input probes using a freshly earned .bz clearance:

1. cross-mirror: does a .bz clearance serve .to / .tv? (per-zone vs per-host scope)
2. UA binding: does the clearance still refuse a mismatched UA? (§0.17 #2 replication)

Re-runs the earn from pipe_clearance_replay because cf_clearance is short-lived.
"""

import sys

import pipe_clearance_replay as pcr
from curl_cffi import requests
import time

XHR = {
    "Accept": "*/*",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer": "https://www.miruro.bz/",
    "Sec-Fetch-Dest": "empty",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Site": "same-origin",
}


def hit(label, mirror, cookie, ua):
    s = requests.Session(impersonate=None)
    h = dict(XHR)
    h["Referer"] = f"{mirror}/"
    h["User-Agent"] = ua
    if cookie:
        h["Cookie"] = cookie
    r = s.get(f"{mirror}/api/secure/jwks", headers=h, timeout=20)
    body = " ".join(r.text[:60].split())
    print(f"  {label:<28} {r.status_code} cf-mitigated={r.headers.get('cf-mitigated', '-')} len={len(r.content)} {body!r}", flush=True)


def main():
    print("== earning on .bz ==", flush=True)
    ua, jar, title = pcr.earn_clearance("https://www.miruro.bz/")
    cf = jar.get("cf_clearance", "")
    print(f"  clearance={'yes' if cf else 'NO'}  ua={ua[:40]}...", flush=True)
    cookie = f"cf_clearance={cf}" if cf else ""
    time.sleep(1)

    hit("bz + matching UA (control)", "https://www.miruro.bz", cookie, ua)
    hit("to + .bz clearance", "https://www.miruro.to", cookie, ua)
    hit("tv + .bz clearance", "https://www.miruro.tv", cookie, ua)
    hit("bz + CHROME desktop UA", "https://www.miruro.bz", cookie,
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
    hit("bz + ANDROID app UA", "https://www.miruro.bz", cookie,
        "Mozilla/5.0 (Linux; Android 16; SM-S938B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36")


if __name__ == "__main__":
    sys.exit(main())
