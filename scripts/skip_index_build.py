"""Mirror AniSkip's intro/outro/recap timings onto the metadata tree, one file per title.

The app asks AniSkip once per episode, as the episode starts. That is a request in front of the skip
button on every episode, and when AniSkip is slow or down the button never appears. The timings
change slowly — a new submission, a vote — so the popular titles are fetched here and published as
`data/skip/<shard>/<anilist id>.json`; the app reads that first and still asks AniSkip for any
episode the file does not hold (a new episode, a title outside the crawl).

Every candidate AniSkip returns is kept, not one pick: the app chooses the one whose recorded
episode length is closest to the stream actually playing (`AniSkipClient.pickSegment`), and that
length is only known on the device.

File shape (compact on purpose — One Piece is 1,100 episodes):

    {"v": 1, "mal": 21, "checked": 1150,
     "episodes": {"1": [["op", 13.9, 123.8, 1430.7, "5bcf..."], ...], ...}}

Each candidate is [type, start, end, episodeLength, skipId]. `checked` is the last episode asked.

Usage:

    python scripts/skip_index_build.py crawl --top 1500      # resumable; build/konoha/catalog.json first
    python scripts/skip_index_build.py publish               # commit + push via ../anilili-konoha-new
"""
from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import pathlib
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
CATALOG = ROOT / "build" / "konoha" / "catalog.json"
OUT = ROOT / "app" / "build" / "skip-index"
TITLES = OUT / "titles"
CHECKOUT = ROOT.parent / "anilili-konoha-new"
REPO = "DATA_OWNER/media-index"
LEGACY = "DATA_OWNER/anilili-metadata"
GH = shutil.which("gh") or str(pathlib.Path.home() / "AppData/Local/Programs/GitHubCLI/bin/gh.exe")

TYPES = ["op", "ed", "mixed-op", "mixed-ed", "recap"]
FORMATS = {"TV", "TV_SHORT", "ONA", "OVA", "SPECIAL", "MOVIE"}
USER_AGENT = "anilili-skip-index/1.0"


class Pacer:
    """At most `rate` requests a second across every worker, backing off together on a 429."""

    def __init__(self, rate: float) -> None:
        self.interval = 1.0 / rate
        self.lock = threading.Lock()
        self.next_at = 0.0

    def wait(self) -> None:
        with self.lock:
            now = time.monotonic()
            at = max(now, self.next_at)
            self.next_at = at + self.interval
        time.sleep(max(0.0, at - now))

    def penalise(self, seconds: float) -> None:
        with self.lock:
            self.next_at = max(self.next_at, time.monotonic() + seconds)


def aired_episodes(entry: dict) -> int:
    upcoming = (entry.get("nextAiringEpisode") or {}).get("episode")
    if upcoming:
        return max(0, upcoming - 1)
    if entry.get("status") in ("FINISHED", "CANCELLED"):
        return entry.get("episodes") or 0
    return 0


def fetch(mal_id: int, episode: int, pacer: Pacer) -> list | None:
    query = urllib.parse.urlencode([("types[]", t) for t in TYPES] + [("episodeLength", "0")])
    url = f"https://api.aniskip.com/v2/skip-times/{mal_id}/{episode}?{query}"
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    for attempt in range(6):
        pacer.wait()
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.load(response)
            break
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return None
            if error.code in (429, 500, 502, 503, 504) and attempt < 5:
                pacer.penalise(10.0 * (attempt + 1))
                continue
            raise
        except (urllib.error.URLError, TimeoutError):
            if attempt == 5:
                raise
            pacer.penalise(5.0)
    if not payload.get("found"):
        return None
    out = []
    for result in payload.get("results") or []:
        interval = result.get("interval") or {}
        start, end = interval.get("startTime"), interval.get("endTime")
        if not isinstance(start, (int, float)) or not isinstance(end, (int, float)) or end <= start:
            continue
        out.append([
            result.get("skipType"),
            round(float(start), 3),
            round(float(end), 3),
            round(float(result.get("episodeLength") or 0.0), 3),
            result.get("skipId"),
        ])
    return out or None


def crawl(args: argparse.Namespace) -> int:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    TITLES.mkdir(parents=True, exist_ok=True)
    if args.ids:
        wanted = {int(i) for i in args.ids.split(",")}
        entries = [e for e in catalog if e["id"] in wanted]
    else:
        entries = [
            e for e in catalog
            if e.get("idMal") and not e.get("isAdult") and e.get("format") in FORMATS and aired_episodes(e) > 0
        ]
        entries.sort(key=lambda e: -(e.get("popularity") or 0))
        entries = entries[: args.top]
    pacer = Pacer(args.rate)
    total = len(entries)
    print(f"{total} titles, {sum(aired_episodes(e) for e in entries)} aired episodes in scope", flush=True)
    for index, entry in enumerate(entries, 1):
        path = TITLES / f"{entry['id']}.json"
        aired = aired_episodes(entry)
        previous = json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None
        # Resumable, and an airing title only asks for the episodes it has gained since.
        start = 1
        episodes: dict[str, list] = {}
        if previous and previous.get("mal") == entry["idMal"] and not args.redo:
            if previous.get("checked", 0) >= aired:
                continue
            episodes = previous.get("episodes") or {}
            start = previous.get("checked", 0) + 1
        numbers = list(range(start, aired + 1))
        with concurrent.futures.ThreadPoolExecutor(args.workers) as pool:
            results = pool.map(lambda n: (n, fetch(entry["idMal"], n, pacer)), numbers)
            try:
                for number, candidates in results:
                    if candidates:
                        episodes[str(number)] = candidates
            except Exception as error:  # noqa: BLE001 - leave this title for the next run
                print(f"  {entry['id']}: {error}", flush=True)
                continue
        path.write_text(
            json.dumps({"v": 1, "mal": entry["idMal"], "checked": aired, "episodes": episodes}, separators=(",", ":")),
            encoding="utf-8",
        )
        if index % 20 == 0 or index == total:
            print(f"  {index}/{total} titles", flush=True)
    return 0


def publish(args: argparse.Namespace) -> int:
    files = sorted(TITLES.glob("*.json"))
    if not files:
        raise SystemExit("Nothing crawled yet")
    if not (CHECKOUT / ".git").is_dir():
        raise SystemExit(f"No dataset checkout at {CHECKOUT}")
    token = subprocess.check_output([GH, "auth", "token", "--user", "DATA_OWNER"], text=True).strip()
    remote = f"https://x-access-token:{token}@github.com/{REPO}.git"

    def git(*argv: str, capture: bool = False) -> str:
        result = subprocess.run(["git", "-C", str(CHECKOUT), *argv], capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(f"git {argv[0]} failed: {result.stderr.strip()}")
        return result.stdout if capture else ""

    git("pull", "--ff-only", "-q", remote, "main")
    changed: list[str] = []
    # Only a path jsDelivr has already served can be stale; purging the new ones is 3,000 wasted
    # requests (that is what made the first publish look hung).
    replaced: list[str] = []
    for source in files:
        anilist_id = int(source.stem)
        relative = f"data/skip/{anilist_id // 1000}/{anilist_id}.json"
        target = CHECKOUT / relative
        content = source.read_bytes()
        if target.is_file() and target.read_bytes() == content:
            continue
        if target.is_file():
            replaced.append(relative)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        changed.append(relative)
    if not changed:
        print("Nothing changed")
        return 0
    git("add", "--", "data/skip")
    git(
        "-c", "user.name=DATA_OWNER", "-c", "user.email=owner@example.com",
        "commit", "-q", "-m", f"Mirror AniSkip timings for {len(changed)} titles",
    )
    git("push", "-q", remote, "HEAD:main")
    print(f"Pushed {len(changed)} skip files")
    # New paths are not in jsDelivr's cache yet; replaced ones are, and have to be purged by URL.
    def purge(url: str) -> None:
        try:
            urllib.request.urlopen(url, timeout=30).read()
        except Exception:  # noqa: BLE001 - a missed purge only delays, never breaks
            pass

    urls = [f"https://purge.jsdelivr.net/gh/{repo}@main/{relative}" for relative in replaced for repo in (REPO, LEGACY)]
    with concurrent.futures.ThreadPoolExecutor(12) as pool:
        list(pool.map(purge, urls))
    print(f"Purged {len(replaced)} replaced files ({len(changed) - len(replaced)} new)")
    probe = changed[0]
    for _ in range(10):
        try:
            with urllib.request.urlopen(f"https://cdn.jsdelivr.net/gh/{REPO}@main/{probe}", timeout=30) as r:
                if json.load(r) == json.loads((CHECKOUT / probe).read_text(encoding="utf-8")):
                    print(f"Verified {probe} on the CDN")
                    return 0
        except Exception:  # noqa: BLE001 - propagation lag
            pass
        time.sleep(6)
    raise SystemExit(f"Pushed, but {probe} did not verify on the CDN yet")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("crawl")
    c.add_argument("--top", type=int, default=1500)
    c.add_argument("--ids")
    c.add_argument("--redo", action="store_true")
    c.add_argument("--rate", type=float, default=6.0, help="requests per second across all workers")
    c.add_argument("--workers", type=int, default=6)
    sub.add_parser("publish")
    args = parser.parse_args()
    return crawl(args) if args.command == "crawl" else publish(args)


if __name__ == "__main__":
    raise SystemExit(main())
