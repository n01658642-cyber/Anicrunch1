"""Fill in the dub index by AniList id, instead of by searching for a title.

`DubIndexCrawler` (the Kotlin one, in the test source set) asks each provider "do you have a show
called *Attack on Titan Season 3*". Providers answer that question by searching their own
catalogue, and every one of them raises rather than returning an empty answer when nothing scores
well enough — so a title no provider can *name* is recorded as nothing at all, not as "no dub".
That is why the badge is missing on the middle seasons of the biggest franchises on the service:
they were attempted and every probe threw, so they never reached the file.

This asks a different question. `miruro.anifake.com/episodes/<anilist id>` is keyed by the AniList
id we already hold, so no title is ever matched, and one request answers for all seven Miruro
providers at once — sub and dub episode lists each. A title either has a dub somewhere or it does
not, and a sequel season is no harder to ask about than a first one.

The result feeds `app/src/main/assets/dub-index.json`, which is a flat array of the AniList ids
that positively have a dub. Absent still means *unknown*, exactly as `DubIndex` documents: a title
nobody has asked about and a title with genuinely no dub both go unbadged, because a missing badge
costs a viewer nothing and a wrong one sends them to a server that cannot serve what it promised.

Usage:

    scripts/.venv/Scripts/python.exe scripts/dub_index_backfill.py --top 2000
    scripts/.venv/Scripts/python.exe scripts/dub_index_backfill.py --ids 99147,100166
    scripts/.venv/Scripts/python.exe scripts/dub_index_backfill.py --top 5000 --write-asset

Resumable: every answer is kept in `build/dub-index/dub-index-anifake.json` and titles already
there are skipped, so an interrupted run costs nothing and a long backfill can be done in sittings.
Nothing is written into the app until `--write-asset` is passed.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import random
import time
import urllib.error
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
ASSETS = ROOT / "app" / "src" / "main" / "assets"
# Beside the Kotlin crawler's own output: its test runs with the module as its working directory,
# so "build/dub-index" there is this path, and the two should not end up with separate histories.
OUT = ROOT / "app" / "build" / "dub-index"
STATE = OUT / "dub-index-anifake.json"
# What the Kotlin crawler left behind, read only so its answers are not asked for a second time.
LEGACY = OUT / "dub-index-raw.json"

BASE = "https://miruro.anifake.com/episodes"
USER_AGENT = "anilili-dub-backfill/1.0"


def load_json(path: pathlib.Path, default):
    if not path.is_file():
        return default
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def targets(top: int, ids: list[int] | None) -> list[dict]:
    """Catalogue entries to ask about, most popular first."""
    catalogue = load_json(ASSETS / "index.json", [])
    if ids:
        wanted = set(ids)
        found = [entry for entry in catalogue if entry["id"] in wanted]
        # An id the catalogue snapshot does not carry is still worth asking about: the snapshot is
        # a month old by the time anyone reads this and the endpoint is keyed by id, not by us.
        known = {entry["id"] for entry in found}
        return found + [{"id": i, "title": f"(not in catalogue) {i}"} for i in ids if i not in known]
    return sorted(catalogue, key=lambda e: -(e.get("popularity") or 0))[:top]


def dub_providers(payload: dict) -> tuple[list[str], list[str]]:
    """Which providers list dub episodes, and which list sub, for one title."""
    dub, sub = [], []
    for name, provider in (payload.get("providers") or {}).items():
        episodes = provider.get("episodes") or {}
        if episodes.get("dub"):
            dub.append(name)
        if episodes.get("sub"):
            sub.append(name)
    return sorted(dub), sorted(sub)


def ask(anilist_id: int, timeout: float) -> dict:
    request = urllib.request.Request(
        f"{BASE}/{anilist_id}",
        headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--top", type=int, default=2000, help="how many of the most popular titles to cover")
    parser.add_argument("--ids", help="comma-separated AniList ids instead of a popularity band")
    parser.add_argument("--min-pause", type=float, default=0.4)
    parser.add_argument("--max-pause", type=float, default=0.9)
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument(
        "--abort-after",
        type=int,
        default=15,
        # Same discipline as the Kotlin crawler: a run of total failures means something is
        # refusing us, and pushing on from there is how an address gets remembered.
        help="stop after this many consecutive failures",
    )
    parser.add_argument("--redo", action="store_true", help="re-ask about titles already answered")
    parser.add_argument("--write-asset", action="store_true", help="update app/src/main/assets/dub-index.json")
    args = parser.parse_args()

    OUT.mkdir(parents=True, exist_ok=True)
    state = {int(k): v for k, v in load_json(STATE, {}).items()}
    legacy = {int(k): v for k, v in load_json(LEGACY, {}).items()}

    wanted = targets(args.top, [int(i) for i in args.ids.split(",")] if args.ids else None)
    if not args.redo:
        answered = set(state) | set(legacy)
        todo = [entry for entry in wanted if entry["id"] not in answered]
    else:
        todo = wanted

    print(
        f"dub backfill: {len(todo)} to ask about, "
        f"{len(state)} already answered here, {len(legacy)} answered by the old crawler"
    )

    started = time.time()
    yes = no = failed = 0
    consecutive_failures = 0
    for index, entry in enumerate(todo):
        anilist_id = entry["id"]
        try:
            payload = ask(anilist_id, args.timeout)
            dub, sub = dub_providers(payload)
            # `found` separates "every provider knows this title and none of them dubs it" from
            # "no provider carries this title at all". Both leave the badge off, but only the first
            # is evidence; the second is a title to ask a different source about, and keeping them
            # apart is what stops a gap in one catalogue reading as a fact about the anime.
            state[anilist_id] = {
                "dub": bool(dub),
                "found": bool(dub or sub),
                "dubProviders": dub,
                "subProviders": sub,
            }
            consecutive_failures = 0
            if dub:
                yes += 1
            else:
                no += 1
        except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, ValueError) as error:
            failed += 1
            consecutive_failures += 1
            print(f"  ! {anilist_id} {type(error).__name__}: {str(error)[:70]}")
            if consecutive_failures >= args.abort_after:
                print(
                    f"ABORT: {consecutive_failures} in a row answered by nobody. "
                    "Something is rejecting us; stopping rather than pushing through."
                )
                break

        if index % 50 == 0 or index == len(todo) - 1:
            write_state(state)
            elapsed = int(time.time() - started)
            print(
                f"[{index + 1:>5}/{len(todo)}] dub={yes} nodub={no} failed={failed} {elapsed}s"
                f"  last={entry.get('title', '')[:40]}"
            )
        if index != len(todo) - 1:
            time.sleep(random.uniform(args.min_pause, args.max_pause))

    write_state(state)
    dubbed = merged_dubbed(state, legacy)
    summary = "\n".join(
        [
            "=== dub index backfill ===",
            f"asked this run   : {yes + no} ({yes} with a dub, {no} without), {failed} failed",
            f"seconds          : {int(time.time() - started)}",
            f"answers on file  : {len(state)} here + {len(legacy)} legacy",
            f"carried by nobody: {sum(1 for a in state.values() if not a.get('found', True))}",
            f"ids with a dub   : {len(dubbed)}",
        ]
    )
    print(summary)
    (OUT / "summary-anifake.txt").write_text(summary + "\n", encoding="utf-8")

    index_text = "[" + ",".join(str(i) for i in sorted(dubbed)) + "]"
    (OUT / "dub-index.json").write_text(index_text, encoding="utf-8")
    if args.write_asset:
        (ASSETS / "dub-index.json").write_text(index_text, encoding="utf-8")
        print(f"wrote {len(dubbed)} ids to app/src/main/assets/dub-index.json")
    return 0


def write_state(state: dict) -> None:
    STATE.write_text(
        json.dumps({str(k): v for k, v in sorted(state.items())}, indent=1),
        encoding="utf-8",
    )


def merged_dubbed(state: dict, legacy: dict) -> set[int]:
    """
    Union of both crawls' positives.

    The legacy answers are kept rather than replaced because the two ask different questions: the
    old crawler covered providers this endpoint knows nothing about (allanime, kaa, animekai and
    the rest), so a `False` there is not evidence against a `True` here, and a `True` there is
    evidence this endpoint cannot contradict.
    """
    dubbed = {i for i, answer in legacy.items() if answer}
    dubbed |= {i for i, answer in state.items() if answer.get("dub")}
    return dubbed


if __name__ == "__main__":
    raise SystemExit(main())
