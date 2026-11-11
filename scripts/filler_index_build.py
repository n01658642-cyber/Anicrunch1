"""Build and publish the filler index: which episodes of which title are filler, by AniList id.

The app used to learn this three ways, none of them dependable. Miruro's servers flag filler on
their own rows, but only theirs, so a viewer on any other server saw no badge at all. MAL's list
through Jikan was the fallback, fetched on the device page by page, and it rarely finished inside
the few seconds anyone waited for it. So the answer is worked out here once and published beside the
dub index, and the app reads it before either.

The first source is `miruro.anifake.com/episodes/<anilist id>`: keyed by the id we already hold, one
request per title, and each row carries animefillerlist's `fillerType`. Only `filler` counts —
`mixed_canon/filler` is a canon episode with padding, and badging it marked Naruto Shippuden's whole
opening arc (see `isFillerEpisode` in PipeClient.kt).

Output, `data/filler-index.json`:

    {"v": 1, "generated": "2026-09-25", "titles": {"20": [97, 101, ...], "5114": []}}

A title with an empty list was checked and has none, which lets the app skip MAL for it. A title
that is absent was never answered, and the app falls back to what it did before.

Usage:

    python scripts/filler_index_build.py crawl            # resumable; state in app/build/filler-index
    python scripts/filler_index_build.py crawl --ids 20,1735 --redo
    python scripts/filler_index_build.py mal              # MAL's own pages for what anifake left unclassified
    python scripts/filler_index_build.py publish          # push to DATA_OWNER/media-index, verify CDN
"""
from __future__ import annotations

import argparse
import base64
import datetime
import json
import os
import pathlib
import random
import re
import shutil
import subprocess
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
OUT = ROOT / "app" / "build" / "filler-index"
STATE = OUT / "filler-anifake.json"
# MAL's own episode pages, for the titles anifake could not classify; see mal().
MAL_STATE = OUT / "filler-mal.json"
MAL_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) anilili-filler-index/1.0"
# A row tagged filler on MAL: the type badge, then the link naming the episode number.
MAL_FILLER_ROW = re.compile(r'icon-episode-type-bg">Filler</span><a href="[^"]*/episode/(\d+)"')
MAL_ROW = re.compile(r'class="episode-number nowrap" data-raw="(\d+)"')
# Long enough to be worth a MAL visit: filler needs an arc to pad.
MAL_MIN_EPISODES = 24
INDEX = OUT / "filler-index.json"
# Latest dubbed episode per title, from the same payload; see dub().
DUB_STATE = OUT / "dub-episodes-anifake.json"
DUB_INDEX = OUT / "dub-episodes.json"
DUB_REMOTE_PATH = "data/dub-episodes.json"

BASE = "https://miruro.anifake.com/episodes"
USER_AGENT = "anilili-filler-index/1.0"
# Filler is a long-running-show thing; a twelve-episode season never has an arc to pad.
MIN_EPISODES = 13
# The order to trust providers in when they disagree; all of them relay the same upstream list.
PROVIDER_ORDER = ["bee", "ally", "pewe", "kiwi", "moo", "bonk", "hop"]

REPO = "DATA_OWNER/media-index"
# The pre-0.1.80 spelling, a separate jsDelivr cache of the same repo (see publish_dub_index.py).
LEGACY = "DATA_OWNER/anilili-metadata"
REMOTE_PATH = "data/filler-index.json"
GH = shutil.which("gh") or str(pathlib.Path.home() / "AppData/Local/Programs/GitHubCLI/bin/gh.exe")


def load_json(path: pathlib.Path, default):
    if not path.is_file():
        return default
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def targets(ids: list[int] | None) -> list[int]:
    if ids:
        return ids
    catalogue = load_json(ASSETS / "index.json", [])
    wanted = [
        entry
        for entry in catalogue
        if entry.get("episodes") is None or entry["episodes"] >= MIN_EPISODES
    ]
    return [entry["id"] for entry in sorted(wanted, key=lambda e: -(e.get("popularity") or 0))]


def filler_numbers(payload: dict) -> list[int] | None:
    """The filler episode numbers one title's providers agree on, or None when none of them knows.

    One provider's list is read rather than a union of all of them: they relay the same upstream
    classification, and a union would take every numbering mistake any one of them makes. A list
    that repeats a stream id is skipped — hop's Naruto Shippuden list puts two episodes' labels on
    each of its first 220 streams.
    """
    best: tuple[int, int, list[dict]] | None = None
    for name, provider in (payload.get("providers") or {}).items():
        for rows in (provider.get("episodes") or {}).values():
            if not isinstance(rows, list) or not rows:
                continue
            ids = [row.get("id") for row in rows]
            if len(set(ids)) != len(ids):
                continue
            classified = sum(1 for row in rows if row.get("fillerType"))
            if not classified:
                continue
            rank = PROVIDER_ORDER.index(name) if name in PROVIDER_ORDER else len(PROVIDER_ORDER)
            key = (-classified, rank)
            if best is None or key < (best[0], best[1]):
                best = (key[0], key[1], rows)
    if best is None:
        return None
    numbers = set()
    for row in best[2]:
        number = row.get("number")
        if str(row.get("fillerType") or "").strip().lower() != "filler":
            continue
        if isinstance(number, (int, float)) and number > 0 and float(number).is_integer():
            numbers.add(int(number))
    return sorted(numbers)


def latest_dub(payload: dict) -> int | None:
    """The highest whole episode number any provider lists a dub for; None when none does.

    The highest rather than a count: a provider that skipped an episode still has the ones after it,
    and "dubbed through episode N" is what a viewer deciding whether to wait wants to know. A list
    that repeats a stream id is skipped for the same reason as in `filler_numbers`.
    """
    best = 0
    for provider in (payload.get("providers") or {}).values():
        rows = (provider.get("episodes") or {}).get("dub")
        if not isinstance(rows, list) or not rows:
            continue
        ids = [row.get("id") for row in rows]
        if len(set(ids)) != len(ids):
            continue
        for row in rows:
            number = row.get("number")
            if isinstance(number, (int, float)) and float(number).is_integer() and number > best:
                best = int(number)
    return best or None


def ask(anilist_id: int, timeout: float) -> dict:
    """One title's episode lists, waiting out the endpoint's rate limit rather than skipping it."""
    request = urllib.request.Request(
        f"{BASE}/{anilist_id}",
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    for attempt in range(6):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code != 429 or attempt == 5:
                raise
            retry_after = error.headers.get("Retry-After")
            wait = float(retry_after) if retry_after and retry_after.isdigit() else 15.0 * (attempt + 1)
            print(f"  {anilist_id}: 429, waiting {wait:.0f}s", flush=True)
            time.sleep(wait)
    raise RuntimeError("unreachable")


def save_state(state: dict[int, list[int] | None]) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    tmp = STATE.with_suffix(".tmp")
    tmp.write_text(json.dumps({str(k): v for k, v in state.items()}, separators=(",", ":")), encoding="utf-8")
    tmp.replace(STATE)


def build_index(state: dict[int, list[int] | None]) -> dict:
    """anifake's answer where it classified the title, MAL's where it could not."""
    mal_state = {int(k): v for k, v in load_json(MAL_STATE, {}).items()}
    merged = {k: v for k, v in mal_state.items() if v is not None}
    merged.update({k: v for k, v in state.items() if v is not None})
    titles = {str(k): v for k, v in sorted(merged.items())}
    return {"v": 1, "generated": datetime.date.today().isoformat(), "titles": titles}


def write_index() -> dict:
    state = {int(k): v for k, v in load_json(STATE, {}).items()}
    index = build_index(state)
    INDEX.write_text(json.dumps(index, separators=(",", ":")), encoding="utf-8")
    with_filler = sum(1 for v in index["titles"].values() if v)
    print(f"Wrote {INDEX}: {len(index['titles'])} titles classified, {with_filler} with filler")
    return index


def mal_filler(mal_id: int, timeout: float, pause: float) -> list[int] | None:
    """Filler episode numbers from MAL's episode pages, or None when MAL lists no episodes.

    Read from the site because Jikan, which relays exactly these pages, spent the day answering
    504. A hundred rows a page, walked with `?offset=` until a page comes back short.
    """
    fillers: set[int] = set()
    rows_seen = 0
    offset = 0
    while True:
        request = urllib.request.Request(
            f"https://myanimelist.net/anime/{mal_id}/_/episode?offset={offset}",
            headers={"User-Agent": MAL_USER_AGENT},
        )
        for attempt in range(5):
            try:
                with urllib.request.urlopen(request, timeout=timeout) as response:
                    html = response.read().decode("utf-8", "replace")
                break
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    return None
                if error.code not in (429, 403, 503) or attempt == 4:
                    raise
                wait = 30.0 * (attempt + 1)
                print(f"  mal {mal_id}: HTTP {error.code}, waiting {wait:.0f}s", flush=True)
                time.sleep(wait)
        rows = len(MAL_ROW.findall(html))
        fillers.update(int(n) for n in MAL_FILLER_ROW.findall(html))
        rows_seen += rows
        if rows < 100:
            break
        offset += 100
        time.sleep(pause)
    return sorted(fillers) if rows_seen else None


def mal(args: argparse.Namespace) -> int:
    anifake = {int(k): v for k, v in load_json(STATE, {}).items()}
    done = {int(k): v for k, v in load_json(MAL_STATE, {}).items()}
    id_map = load_json(ASSETS / "id-map.json", {})
    catalogue = load_json(ASSETS / "index.json", [])
    if args.ids:
        wanted = [int(i) for i in args.ids.split(",")]
    else:
        # Asked by anifake and left unclassified, long enough to have filler, most popular first.
        wanted = [
            e["id"]
            for e in sorted(catalogue, key=lambda e: -(e.get("popularity") or 0))
            if e["id"] in anifake
            and anifake[e["id"]] is None
            and (
                (e.get("episodes") or 0) >= MAL_MIN_EPISODES
                or (e.get("episodes") is None and e.get("status") == "RELEASING")
            )
        ][: args.top]
    todo = wanted if args.redo else [i for i in wanted if i not in done]
    print(f"{len(todo)} titles to read from MAL")

    def save() -> None:
        MAL_STATE.write_text(json.dumps({str(k): v for k, v in done.items()}, separators=(",", ":")), encoding="utf-8")

    failures = 0
    for count, anilist_id in enumerate(todo, 1):
        mal_id = (id_map.get(str(anilist_id)) or {}).get("mal")
        if not mal_id:
            done[anilist_id] = None
            continue
        try:
            done[anilist_id] = mal_filler(int(mal_id), args.timeout, args.pause)
            failures = 0
        except Exception as error:  # noqa: BLE001 - counted; a run of them stops the pass
            failures += 1
            print(f"  {anilist_id} (mal {mal_id}): {error}")
            if failures >= args.abort_after:
                print(f"Stopping after {failures} consecutive failures")
                break
        if count % 25 == 0:
            save()
            print(f"  {count}/{len(todo)}; {sum(1 for v in done.values() if v)} with filler", flush=True)
        time.sleep(args.pause)
    save()
    write_index()
    return 0


def crawl(args: argparse.Namespace) -> int:
    state = {int(k): v for k, v in load_json(STATE, {}).items()}
    wanted = targets([int(i) for i in args.ids.split(",")] if args.ids else None)
    todo = wanted if args.redo else [i for i in wanted if i not in state]
    print(f"{len(todo)} to ask, {len(state)} already answered")
    failures = 0
    for count, anilist_id in enumerate(todo, 1):
        try:
            state[anilist_id] = filler_numbers(ask(anilist_id, args.timeout))
            failures = 0
        except urllib.error.HTTPError as error:
            # 404: the endpoint has never heard of the title. An answer, recorded as unknown.
            if error.code == 404:
                state[anilist_id] = None
                failures = 0
            else:
                failures += 1
                print(f"  {anilist_id}: HTTP {error.code}")
        except Exception as error:  # noqa: BLE001 - network noise; counted, never fatal alone
            failures += 1
            print(f"  {anilist_id}: {error}")
        if failures >= args.abort_after:
            print(f"Stopping after {failures} consecutive failures")
            break
        if count % 25 == 0:
            save_state(state)
            with_filler = sum(1 for v in state.values() if v)
            print(f"  {count}/{len(todo)} asked; {with_filler} titles with filler so far", flush=True)
        time.sleep(random.uniform(args.min_pause, args.max_pause))
    save_state(state)
    write_index()
    return 0


def dub(args: argparse.Namespace) -> int:
    """Record every popular title's latest dubbed episode, most popular first. Resumable."""
    state = {int(k): v for k, v in load_json(DUB_STATE, {}).items()}
    catalogue = load_json(ASSETS / "index.json", [])
    if args.ids:
        wanted = [int(i) for i in args.ids.split(",")]
    else:
        # Every length: a twelve-episode seasonal is exactly where "dubbed through 8" matters.
        wanted = [e["id"] for e in sorted(catalogue, key=lambda e: -(e.get("popularity") or 0))][: args.top]
    todo = wanted if args.redo else [i for i in wanted if i not in state]
    print(f"{len(todo)} to ask for dubs")
    failures = 0

    def save() -> None:
        OUT.mkdir(parents=True, exist_ok=True)
        DUB_STATE.write_text(json.dumps({str(k): v for k, v in state.items()}, separators=(",", ":")), encoding="utf-8")

    for count, anilist_id in enumerate(todo, 1):
        try:
            state[anilist_id] = latest_dub(ask(anilist_id, args.timeout))
            failures = 0
        except urllib.error.HTTPError as error:
            if error.code in (404, 500):
                state[anilist_id] = None
                failures = 0
            else:
                failures += 1
                print(f"  {anilist_id}: HTTP {error.code}")
        except Exception as error:  # noqa: BLE001
            failures += 1
            print(f"  {anilist_id}: {error}")
        if failures >= args.abort_after:
            print(f"Stopping after {failures} consecutive failures")
            break
        if count % 25 == 0:
            save()
            print(f"  {count}/{len(todo)}; {sum(1 for v in state.values() if v)} dubbed", flush=True)
        time.sleep(random.uniform(args.min_pause, args.max_pause))
    save()
    titles = {str(k): v for k, v in sorted(state.items()) if v}
    DUB_INDEX.write_text(
        json.dumps({"v": 1, "generated": datetime.date.today().isoformat(), "titles": titles}, separators=(",", ":")),
        encoding="utf-8",
    )
    print(f"Wrote {DUB_INDEX}: {len(titles)} titles with a dub")
    return 0


def publish(_: argparse.Namespace) -> int:
    index = load_json(INDEX, None)
    if not index or not index.get("titles"):
        raise SystemExit("No index built yet: run `crawl` first")
    for key, numbers in index["titles"].items():
        if not key.isdigit() or any(type(n) is not int or n <= 0 for n in numbers):
            raise SystemExit(f"Invalid entry {key}")

    env = dict(os.environ)
    env["GH_TOKEN"] = subprocess.check_output([GH, "auth", "token", "--user", "DATA_OWNER"], text=True).strip()

    def api(path: str, payload: dict | None = None):
        args = [GH, "api", path]
        if payload is not None:
            args += ["--method", "PUT", "--input", "-"]
        result = subprocess.run(
            args, input=json.dumps(payload) if payload else None, capture_output=True, text=True, env=env
        )
        if result.returncode:
            if payload is None and "404" in result.stderr:
                return None
            raise RuntimeError(result.stderr)
        return json.loads(result.stdout)

    def read(path: str):
        row = api(f"repos/{REPO}/contents/{path}?ref=main")
        return row, (base64.b64decode(row["content"]) if row and row.get("content") else None)

    def write(path: str, content: bytes, previous, message: str) -> None:
        payload = dict(message=message, content=base64.b64encode(content).decode(), branch="main")
        if previous:
            payload["sha"] = previous["sha"]
        api(f"repos/{REPO}/contents/{path}", payload)

    # The daily Action's `emit` rewrites data/ from scratch; carry the index across it the way the
    # dub index is carried, and purge it with the other top-level files.
    path = ".github/workflows/refresh.yml"
    previous, raw = read(path)
    text = raw.decode()
    keep_before = "          if [ -f data/dub-index.json ]; then cp data/dub-index.json /tmp/dub-index.json; fi\n"
    keep_after = "          if [ -f /tmp/dub-index.json ]; then cp /tmp/dub-index.json data/dub-index.json; fi\n"
    for name in ("filler-index.json", "dub-episodes.json"):
        if f"/tmp/{name}" in text:
            continue
        if keep_before not in text or keep_after not in text:
            raise SystemExit("Unexpected workflow: review the emit step before publishing")
        text = text.replace(keep_before, keep_before + f"          if [ -f data/{name} ]; then cp data/{name} /tmp/{name}; fi\n")
        text = text.replace(keep_after, keep_after + f"          if [ -f /tmp/{name} ]; then cp /tmp/{name} data/{name}; fi\n")
    if "/tmp/filler-index.json" not in text:
        if keep_before not in text or keep_after not in text:
            raise SystemExit("Unexpected workflow: review the emit step before publishing")
        text = text.replace(
            keep_before,
            keep_before
            + "          if [ -f data/filler-index.json ]; then cp data/filler-index.json /tmp/filler-index.json; fi\n",
        )
        text = text.replace(
            keep_after,
            keep_after
            + "          if [ -f /tmp/filler-index.json ]; then cp /tmp/filler-index.json data/filler-index.json; fi\n",
        )
    if "for path in dub-index.json index.json" in text and "filler-index.json" not in text.split("for path in", 1)[1][:80]:
        text = text.replace("for path in dub-index.json index.json", "for path in dub-index.json filler-index.json index.json")
    if "dub-episodes.json index.json" not in text:
        text = text.replace("filler-index.json index.json", "filler-index.json dub-episodes.json index.json")
    if text.encode() != raw:
        write(path, text.encode(), previous, "Preserve and purge the independently built filler and dub indexes")
        print("Workflow updated to preserve and purge filler-index.json")

    previous, raw = read(REMOTE_PATH)
    content = json.dumps(index, separators=(",", ":")).encode()
    if raw and json.loads(raw).get("titles") == index["titles"]:
        print("Remote index already current")
    else:
        write(REMOTE_PATH, content, previous, "Publish the filler index")
        print(f"Published {REMOTE_PATH} ({len(content):,} bytes)")

    def verify(repo: str) -> None:
        with urllib.request.urlopen(f"https://purge.jsdelivr.net/gh/{repo}@main/{REMOTE_PATH}", timeout=30) as r:
            r.read()
        for _ in range(8):
            try:
                with urllib.request.urlopen(f"https://cdn.jsdelivr.net/gh/{repo}@main/{REMOTE_PATH}", timeout=30) as r:
                    if json.load(r).get("titles") == index["titles"]:
                        print(f"Verified {len(index['titles'])} titles on CDN for {repo}")
                        return
            except Exception:  # noqa: BLE001 - propagation lag; retried
                pass
            time.sleep(5)
        raise SystemExit(f"Published but CDN verification failed for {repo}; retry the purge")

    verify(REPO)
    verify(LEGACY)

    dub_index = load_json(DUB_INDEX, None)
    if dub_index and dub_index.get("titles"):
        previous, raw = read(DUB_REMOTE_PATH)
        if not (raw and json.loads(raw).get("titles") == dub_index["titles"]):
            write(DUB_REMOTE_PATH, json.dumps(dub_index, separators=(",", ":")).encode(), previous,
                  "Publish the latest dubbed episode per title")
            print(f"Published {DUB_REMOTE_PATH}")
        for repo in (REPO, LEGACY):
            urllib.request.urlopen(f"https://purge.jsdelivr.net/gh/{repo}@main/{DUB_REMOTE_PATH}", timeout=30).read()
            for _ in range(8):
                try:
                    with urllib.request.urlopen(f"https://cdn.jsdelivr.net/gh/{repo}@main/{DUB_REMOTE_PATH}", timeout=30) as r:
                        if json.load(r).get("titles") == dub_index["titles"]:
                            print(f"Verified {len(dub_index['titles'])} dubbed titles on CDN for {repo}")
                            break
                except Exception:  # noqa: BLE001
                    pass
                time.sleep(5)
            else:
                raise SystemExit(f"dub-episodes.json did not verify on {repo}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    c = sub.add_parser("crawl")
    c.add_argument("--ids", help="comma-separated AniList ids instead of the catalogue")
    c.add_argument("--redo", action="store_true", help="re-ask titles already answered")
    c.add_argument("--min-pause", type=float, default=0.8)
    c.add_argument("--max-pause", type=float, default=1.4)
    c.add_argument("--timeout", type=float, default=25.0)
    c.add_argument("--abort-after", type=int, default=15)
    m = sub.add_parser("mal")
    m.add_argument("--ids", help="comma-separated AniList ids instead of anifake's unclassified titles")
    m.add_argument("--top", type=int, default=600, help="how many of the most popular to read")
    m.add_argument("--redo", action="store_true")
    m.add_argument("--pause", type=float, default=2.0, help="seconds between MAL page requests")
    m.add_argument("--timeout", type=float, default=30.0)
    m.add_argument("--abort-after", type=int, default=8)
    d = sub.add_parser("dub")
    d.add_argument("--ids")
    d.add_argument("--top", type=int, default=2500)
    d.add_argument("--redo", action="store_true")
    d.add_argument("--min-pause", type=float, default=0.8)
    d.add_argument("--max-pause", type=float, default=1.4)
    d.add_argument("--timeout", type=float, default=25.0)
    d.add_argument("--abort-after", type=int, default=15)
    sub.add_parser("publish")
    args = parser.parse_args()
    return {"crawl": crawl, "mal": mal, "dub": dub, "publish": publish}[args.command](args)


if __name__ == "__main__":
    raise SystemExit(main())
