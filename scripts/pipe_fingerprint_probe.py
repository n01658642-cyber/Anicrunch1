"""
Does a Chrome-coherent TLS/HTTP2 fingerprint get served by miruro's /api/secure/*?

The one arm never tried in docs/PIPE_CLOUDFLARE_BLOCK.md: §0.18/§0.19 tested Node's OpenSSL
fingerprint and the in-page page principal, nothing between. ja3proxy and tls-forge both
claim a real-browser ClientHello + matching UA is the gate for Cloudflare's TLS-fingerprint
rules; this probe measures that claim against the pipe itself before any app code changes.

Arms (each a fresh session, controls interleaved per §0.4 of the block doc):
  control    curl_cffi with no impersonation (libcurl TLS)   -> expected WALL, proves IP/wall state
  chrome     impersonate="chrome"                            -> the thesis
  chrome-xhr impersonation + same-origin XHR header set      -> thesis + §0.18's header question

Endpoints per arm per mirror: /health, /api/secure/jwks, /api/secure/pipe?e=<episodes envelope>.

Usage: scripts/.venv/Scripts/python.exe scripts/pipe_fingerprint_probe.py [--mirror .bz] [--once]
"""

import argparse
import base64
import gzip
import json
import sys
import time

from curl_cffi import requests

MIRRORS = ["https://www.miruro.bz", "https://www.miruro.to"]

# The known-good envelope from PIPE_CLOUDFLARE_BLOCK.md §0.20 — episodes for anilistId 199111.
ENVELOPE = json.dumps(
    {
        "path": "episodes",
        "method": "GET",
        "query": {"anilistId": 199111},
        "body": None,
        "version": "0.1.0",
    },
    separators=(",", ":"),
)
E = base64.urlsafe_b64encode(ENVELOPE.encode()).decode().rstrip("=")

# VITE_PIPE_OBF_KEY from the site's env2.js (public; mirrors PipeClient.PIPE_OBF_KEY).
OBF_KEY = bytes.fromhex("71951034f8fbcf53d89db52ceb3dc22c")

CHROME_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

XHR_HEADERS = {
    "Accept": "*/*",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer": "https://www.miruro.bz/",
    "Sec-Fetch-Dest": "empty",
    "Sec-Fetch-Mode": "cors",
    "Sec-Fetch-Site": "same-origin",
    "sec-ch-ua": '"Chromium";v="131", "Not_A Brand";v="24"',
    "sec-ch-ua-mobile": "?0",
    "sec-ch-ua-platform": '"Windows"',
}


def b64url_decode(s: str) -> bytes:
    pad = "=" * (-len(s) % 4)
    return base64.urlsafe_b64decode(s + pad)


def decode_pipe(body: str, obf: str) -> str:
    if not obf:
        return body
    raw = b64url_decode(body.strip())
    if obf == "2":
        raw = bytes(b ^ OBF_KEY[i % len(OBF_KEY)] for i, b in enumerate(raw))
    return gzip.decompress(raw).decode("utf-8", "replace")


def request(session, url: str, headers: dict | None):
    t0 = time.time()
    try:
        r = session.get(url, headers=headers, timeout=20)
    except Exception as e:  # noqa: BLE001 - a refused handshake is a result, not a crash
        return {"err": f"{type(e).__name__}: {e}", "ms": int((time.time() - t0) * 1000)}
    out = {
        "status": r.status_code,
        "cf_mitigated": r.headers.get("cf-mitigated", "-"),
        "server": r.headers.get("server", "-"),
        "len": len(r.content),
        "ms": int((time.time() - t0) * 1000),
    }
    body = r.text
    if "pipe?e=" in url and r.status_code == 200:
        try:
            decoded = decode_pipe(body, r.headers.get("x-obfuscated", ""))
            obj = json.loads(decoded)
            out["decoded"] = f"keys={sorted(obj)[:6]} providers={sorted((obj.get('providers') or {}).keys())}"
        except Exception as e:  # noqa: BLE001
            out["decoded"] = f"decode failed: {type(e).__name__}: {e}"
    else:
        snippet = " ".join(body[:90].split())
        out["body"] = snippet
        out["blocked_page"] = "cf-error-details" in body or "lt IE 7" in body
    return out


def run_arm(name: str, mirror: str, impersonate: str | None, extra_headers: dict | None):
    session = requests.Session(impersonate=impersonate)
    session.headers.update({"User-Agent": CHROME_UA} if impersonate else {})
    results = {}
    for label, path in (
        ("health", "/health"),
        ("jwks", "/api/secure/jwks"),
        ("pipe", f"/api/secure/pipe?e={E}"),
    ):
        hdr = dict(XHR_HEADERS) if extra_headers else None
        if hdr and label != "jwks":
            pass  # same header set for all three; Referer/Sec-Fetch are per-origin below
        if hdr:
            hdr["Referer"] = f"{mirror}/"
        results[label] = request(session, mirror + path, hdr)
        time.sleep(1.0)
    print(f"  arm={name:<11} mirror={mirror}", flush=True)
    for label, res in results.items():
        print(f"    {label:<7} {res}", flush=True)
    return results


def self_check():
    """Prove the impersonation is actually seen as Chrome before trusting any arm."""
    try:
        r = requests.get("https://tls.browserleaks.com/json", impersonate="chrome", timeout=20)
        j = r.json()
        print(
            f"  self-check tls.browserleaks: ja3_hash={str(j.get('ja3_hash'))[:16]} "
            f"ja4={str(j.get('ja4'))[:40]} peetprint_hash={str(j.get('peetprint_hash'))[:16]}",
            flush=True,
        )
        c = requests.get("https://tls.browserleaks.com/json", timeout=20)
        cj = c.json()
        print(
            f"  self-check control (no impersonation): ja3_hash={str(cj.get('ja3_hash'))[:16]} "
            f"ja4={str(cj.get('ja4'))[:40]}",
            flush=True,
        )
    except Exception as e:  # noqa: BLE001
        print(f"  self-check FAILED ({e}) - treat all arms as unproven", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mirror", action="append", help="e.g. .bz; repeatable; default .bz and .to")
    ap.add_argument("--once", action="store_true", help="single pass, no interleaved repeat")
    args = ap.parse_args()

    mirrors = args.mirror or MIRRORS
    if args.mirror:
        mirrors = [f"https://www.miruro{m}" for m in args.mirror]

    print("== fingerprint self-check (instrument validation, docs block doc §0.4) ==", flush=True)
    self_check()

    passes = 1 if args.once else 2
    for p in range(passes):
        print(f"== pass {p + 1} of {passes} ==", flush=True)
        for mirror in mirrors:
            run_arm("control", mirror, None, None)
            run_arm("chrome", mirror, "chrome", None)
            run_arm("chrome-xhr", mirror, "chrome", XHR_HEADERS)
    print("done", flush=True)


if __name__ == "__main__":
    sys.exit(main())
