"""
Earn a cf_clearance in headed desktop Chrome (CDP, docs/PIPE_CLOUDFLARE_BLOCK.md §8.2),
then replay /health and /api/secure/pipe from curl_cffi holding it.

Answers the question §0.19 left open: is /api/secure/* reachable off-engine when the
client's TLS/HTTP2 fingerprint is Chrome-coherent? §0.18's "no" was measured with Node's
OpenSSL fingerprint, which the control arm of pipe_fingerprint_probe.py just showed gets
the WAF block page regardless of cookies.

Arms after earning:
  spend-chrome  clearance + chrome impersonation + same-origin XHR headers + earner UA
  spend-control clearance + NO impersonation (libcurl TLS) + same headers   [known-walls control]

Exit code 0 only if spend-chrome got a 200 pipe body that decodes to providers.
"""

import argparse
import base64
import gzip
import json
import subprocess
import sys
import tempfile
import time

import requests as plain_requests
import websocket
from curl_cffi import requests

CHROME = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
DEBUG_PORT = 9501

MIRROR = "https://www.miruro.bz"

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
OBF_KEY = bytes.fromhex("71951034f8fbcf53d89db52ceb3dc22c")


def b64url_decode(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def decode_pipe(body: str, obf: str):
    if not obf:
        return json.loads(body)
    raw = b64url_decode(body.strip())
    if obf == "2":
        raw = bytes(b ^ OBF_KEY[i % len(OBF_KEY)] for i, b in enumerate(raw))
    return json.loads(gzip.decompress(raw))


class Cdp:
    def __init__(self, ws_url: str):
        self.ws = websocket.create_connection(ws_url, timeout=10, suppress_origin=True)
        self.msg_id = 0

    def send(self, method: str, params: dict | None = None) -> dict:
        self.msg_id += 1
        self.ws.send(json.dumps({"id": self.msg_id, "method": method, "params": params or {}}))
        while True:
            msg = json.loads(self.ws.recv())
            if msg.get("id") == self.msg_id:
                return msg.get("result", {})

    def close(self):
        self.ws.close()


def earn_clearance(url: str, budget_s: int = 90, attach_after_s: int = 45):
    """Launch headed Chrome straight at the URL and attach CDP only AFTER attach_after_s.

    The challenge runs devtools detection (block doc §8.1), so the tab is left completely
    unattached for the first window -- if it clears, the clearance is already in the jar and
    reading it cannot retroactively contaminate the outcome.
    """
    user_data = tempfile.mkdtemp(prefix="miruro-earn-")
    proc = subprocess.Popen(
        [
            CHROME,
            f"--remote-debugging-port={DEBUG_PORT}",
            "--remote-allow-origins=*",
            f"--user-data-dir={user_data}",
            "--no-first-run",
            "--no-default-browser-check",
            "--window-size=1280,900",
            url,
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        for _ in range(50):
            try:
                plain_requests.get(f"http://127.0.0.1:{DEBUG_PORT}/json/version", timeout=2)
                break
            except Exception:  # noqa: BLE001
                time.sleep(0.3)
        else:
            raise RuntimeError("chrome devtools endpoint never came up")

        print(f"  leaving chrome unattached for {attach_after_s}s...", flush=True)
        time.sleep(attach_after_s)

        targets = plain_requests.get(f"http://127.0.0.1:{DEBUG_PORT}/json/list", timeout=5).json()
        tab = next((t for t in targets if t.get("type") == "page" and "miruro" in (t.get("url") or "")), targets[0])
        cdp = Cdp(tab["webSocketDebuggerUrl"])
        cdp.send("Network.enable")

        ua = cdp.send("Runtime.evaluate", {"expression": "navigator.userAgent", "returnByValue": True})
        user_agent = ua["result"]["value"]

        deadline = time.time() + max(0, budget_s - attach_after_s)
        title = ""
        while time.time() < deadline:
            r = cdp.send("Runtime.evaluate", {"expression": "document.title", "returnByValue": True})
            title = r.get("result", {}).get("value", "") or ""
            print(f"  title: {title!r}", flush=True)
            if title and "just a moment" not in title.lower():
                break
            time.sleep(3.0)

        cookies = cdp.send("Network.getCookies", {"urls": [url]})["cookies"]
        jar = {c["name"]: c["value"] for c in cookies}
        cdp.close()
        return user_agent, jar, title
    finally:
        proc.terminate()


def replay(name: str, impersonate, cookie_header: str, ua: str, mirror: str):
    session = requests.Session(impersonate=impersonate)
    headers = {
        "User-Agent": ua,
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "Referer": f"{mirror}/",
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-origin",
    }
    if cookie_header:
        headers["Cookie"] = cookie_header
    for label, path in (
        ("health", "/health"),
        ("pipe", f"/api/secure/pipe?e={E}"),
    ):
        try:
            r = session.get(mirror + path, headers=headers, timeout=20)
        except Exception as e:  # noqa: BLE001
            print(f"  {name:<15} {label:<7} ERR {type(e).__name__}: {e}", flush=True)
            continue
        line = (
            f"  {name:<15} {label:<7} {r.status_code} cf-mitigated={r.headers.get('cf-mitigated', '-')} "
            f"len={len(r.content)}"
        )
        if label == "pipe" and r.status_code == 200:
            try:
                obj = decode_pipe(r.text, r.headers.get("x-obfuscated", ""))
                providers = sorted((obj.get("providers") or {}).keys())
                line += f" DECODED keys={sorted(obj)[:6]} providers={providers}"
            except Exception as e:  # noqa: BLE001
                line += f" decode-failed {type(e).__name__}: {e}"
        else:
            body = " ".join(r.text[:70].split())
            line += f" body={body!r}"
        print(line, flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mirror", default=MIRROR)
    ap.add_argument("--keep-open", action="store_true", help="leave chrome running for inspection")
    args = ap.parse_args()

    print(f"== earning clearance at {args.mirror} (headed Chrome, fresh profile) ==", flush=True)
    ua, jar, title = earn_clearance(args.mirror + "/")
    cf = jar.get("cf_clearance", "")
    print(f"  earner UA: {ua}", flush=True)
    print(f"  final title: {title!r}", flush=True)
    print(f"  cookies: {sorted(jar)} clearance={'yes' if cf else 'NO'}", flush=True)
    if not cf:
        print("  no clearance earned; replay arms will show the no-cookie wall", flush=True)

    cookie_header = "; ".join(f"{k}={v}" for k, v in jar.items() if k in ("cf_clearance", "__cf_bm"))

    print("== replay from curl_cffi ==", flush=True)
    replay("spend-chrome", "chrome", cookie_header, ua, args.mirror)
    time.sleep(1.0)
    replay("spend-control", None, cookie_header, ua, args.mirror)
    if args.keep_open:
        input("chrome kept open; enter to exit")


if __name__ == "__main__":
    sys.exit(main())
