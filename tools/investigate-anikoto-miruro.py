"""Read-only comparison with Miruro's real provider IDs and source responses.

Run with scripts/.venv/Scripts/python.exe tools/investigate-anikoto-miruro.py.
Uses the existing curl_cffi dependency and pipe protocol; never executes site JS.
The default sample is the episode supplied for the 2026-09-22 investigation.
All source timings exclude catalog discovery and player buffering. A new local
session does not imply a cold upstream cache.
"""

import argparse
import base64
import gzip
import json
import time
from pathlib import Path

from curl_cffi import requests


def decode(body, obfuscation):
    if not obfuscation:
        return json.loads(body)
    raw = base64.urlsafe_b64decode(body + "=" * (-len(body) % 4))
    if obfuscation == "2":
        key = bytes.fromhex("71951034f8fbcf53d89db52ceb3dc22c")
        raw = bytes(value ^ key[index % len(key)] for index, value in enumerate(raw))
    return json.loads(gzip.decompress(raw))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--anilist", type=int, default=197754)
    parser.add_argument("--episode", type=int, default=25)
    parser.add_argument("--out", type=Path, default=Path("build/anikoto-investigation/miruro-comparison"))
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    origin = "https://www.miruro.to"
    calls = []
    with requests.Session(impersonate="chrome") as session:
        headers = {
            "Referer": origin + "/", "Origin": origin,
            "Sec-Fetch-Site": "same-origin", "Sec-Fetch-Mode": "cors", "Sec-Fetch-Dest": "empty",
            "Accept": "*/*", "Accept-Language": "en-US,en;q=0.9",
        }

        def pipe(path, query):
            envelope = {"path": path, "method": "GET", "query": query, "body": None, "version": "0.1.0"}
            encoded = base64.urlsafe_b64encode(json.dumps(envelope, separators=(",", ":")).encode()).decode().rstrip("=")
            start = time.monotonic()
            response = session.get(origin + "/api/secure/pipe?e=" + encoded, headers=headers, timeout=15)
            row = {"path": path, "query": query, "status": response.status_code,
                   "ms": round((time.monotonic() - start) * 1000)}
            calls.append(row)
            if response.status_code == 200:
                row["data"] = decode(response.text, response.headers.get("x-obfuscated"))
            else:
                row["error"] = response.text[:200]
            return row

        catalog = pipe("episodes", {"anilistId": args.anilist})
        (args.out / "catalog.json").write_text(json.dumps(catalog, indent=2), encoding="utf-8")
        print("catalog", catalog["status"], catalog["ms"], "ms")
        providers = catalog.get("data", {}).get("providers", {})
        for name, count, variant in [("bee", 3, "ssub"), ("hop", 1, "ssub")]:
            buckets = providers.get(name, {}).get("episodes", {})
            episodes = buckets if isinstance(buckets, list) else buckets.get("sub", [])
            episode = next((entry for entry in episodes if entry.get("number") == args.episode), None)
            if not episode:
                print(name, "episode unavailable")
                continue
            decoded_id = base64.urlsafe_b64decode(episode["id"] + "=" * (-len(episode["id"]) % 4)).decode()
            print(name, "upstream identity:", decoded_id.split(":")[0])
            for _ in range(count):
                row = pipe("sources", {"episodeId": episode["id"], "provider": name,
                                       "category": variant, "anilistId": args.anilist})
                streams = row.get("data", {}).get("streams", [])
                print(name, row["status"], row["ms"], "ms", [entry.get("url") for entry in streams])
    (args.out / "results.json").write_text(json.dumps({"calls": calls}, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
