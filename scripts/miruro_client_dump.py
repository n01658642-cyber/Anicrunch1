"""Pull miruro's live client (HTML + every bundle + key endpoints) for offline analysis.

Reuses the unattached-Chrome earner: the HTML itself is behind the challenge, so this
cannot be done cookie-less. Artifacts land in build/miruro-client/ for rg-ing.
"""

import json
import re
import sys
import time
from pathlib import Path

import pipe_clearance_replay as pcr
from curl_cffi import requests

OUT = Path("build/miruro-client")
MIRRORS = ["https://www.miruro.to", "https://www.miruro.bz"]

SCRIPT_RE = re.compile(r'<script[^>]+src=["\']([^"\']+)["\']', re.I)
LINK_RE = re.compile(r'<link[^>]+href=["\']([^"\']+\.(?:js|json))["\']', re.I)


def fetch(session, url: str, headers: dict) -> tuple[int, bytes]:
    r = session.get(url, headers=headers, timeout=25)
    return r.status_code, r.content


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    ua, jar, title = pcr.earn_clearance(MIRRORS[0] + "/", budget_s=240, attach_after_s=100)
    cf = jar.get("cf_clearance", "")
    print(f"earned clearance={'yes' if cf else 'NO'} title={title!r}")
    if not cf:
        return 1
    cookie = f"cf_clearance={cf}"

    session = requests.Session(impersonate="chrome")
    base_headers = {
        "User-Agent": ua,
        "Accept": "*/*",
        "Accept-Language": "en-US,en;q=0.9",
        "Referer": f"{MIRRORS[0]}/",
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-origin",
        "Cookie": cookie,
    }

    # The document: navigation headers, not XHR ones.
    doc_headers = {
        "User-Agent": ua,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
        "Cookie": cookie,
    }
    status, html = fetch(session, MIRRORS[0] + "/", doc_headers)
    print(f"GET / -> {status} len={len(html)}")
    if status != 200:
        return 1
    (OUT / "index.html").write_bytes(html)

    urls = set()
    for regex in (SCRIPT_RE, LINK_RE):
        for m in regex.finditer(html.decode("utf-8", "replace")):
            src = m.group(1)
            if src.startswith("http"):
                urls.add(src)
            elif src.startswith("/"):
                urls.add(MIRRORS[0] + src)
    # Known-fixed extras from the block doc's earlier audit.
    for extra in ("/sw.js", "/env2.js", "/api/secure/jwks", "/api/version", "/api/monkey"):
        urls.add(MIRRORS[0] + extra)
    print(f"{len(urls)} candidate URLs")

    for url in sorted(urls):
        name = url.split(MIRRORS[0] + "/")[-1] if url.startswith(MIRRORS[0]) else \
            "ext_" + re.sub(r"[^A-Za-z0-9._-]", "_", url.split("//", 1)[1])
        name = name.replace("/", "_")
        status, body = fetch(session, url, base_headers)
        ctype = ""
        print(f"  {status} {url} len={len(body)}")
        if status == 200 and body:
            (OUT / name).write_bytes(body)
        time.sleep(0.6)

    meta = {"ua": ua, "mirror": MIRRORS[0], "cookie": cookie, "fetched": time.strftime("%FT%T")}
    (OUT / "_meta.json").write_text(json.dumps(meta, indent=2))
    print(f"done -> {OUT.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
