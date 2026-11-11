"""Publish the generated metadata tree to its GitHub repo and purge jsDelivr for what changed.

Committing is not publishing here. jsDelivr caches a branch reference (`@main`) for up to twelve
hours and does not notice a push, so a tree that is committed but not purged serves yesterday's
episode lists to every device — on top of the app's own six-hour cache, which is how a new episode
ends up a day late for no visible reason.

Only changed files are purged, because the purge endpoint is per-URL and a full tree is tens of
thousands of them. A daily run touches the handful of airing titles plus the roots, which is a few
hundred purges at most.

A repo that has been renamed needs `--alias` as well. GitHub redirects the old name and jsDelivr
follows it, so old installs still reach the tree — but the cache key is the URL, so that spelling
holds its own copy and goes stale on its own unless it is purged too.

Usage:
    scripts/.venv/Scripts/python.exe scripts/konoha_publish.py \
      --repo DATA_OWNER/media-index --alias DATA_OWNER/anilili-metadata \
      --data build/konoha/data --checkout ../anilili-konoha
"""
from __future__ import annotations

import argparse
import pathlib
import shutil
import subprocess
import time
import sys
from concurrent.futures import ThreadPoolExecutor

import requests

PURGE_BASE = "https://purge.jsdelivr.net/gh"
CDN_BASE = "https://cdn.jsdelivr.net/gh"

# Roots are read on every launch and change on every run, so they are always purged even when the
# diff says otherwise — a stale index.json is the one file a viewer cannot work around.
#
# Repo-relative, including the `data/` prefix, because that is what the CDN path contains. Stripping
# it produced a URL for a file that does not exist, which the purge endpoint answers "finished" for
# just the same — so every purge reported success and nothing was ever actually purged.
ALWAYS_PURGE = [
    "data/dub-index.json",
    "data/index.json",
    "data/id-map.json",
    "data/airing.json",
    "data/stats.json",
    "data/sync-status.json",
]


def run(args: list[str], cwd: pathlib.Path) -> str:
    result = subprocess.run(args, cwd=cwd, capture_output=True, text=True)
    if result.returncode != 0:
        raise SystemExit(f"{' '.join(args)} failed:\n{result.stderr}")
    return result.stdout


def sync_tree(data: pathlib.Path, checkout: pathlib.Path) -> None:
    """Mirror the generated tree into the checkout's data/ directory.

    Deletes first: a title that left the catalogue must lose its files, and copying over the top
    would leave it served forever.
    """
    target = checkout / "data"
    # Dub metadata is maintained independently of the episode generator.
    dub = target / "dub-index.json"
    preserved_dub = dub.read_bytes() if dub.exists() else None
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(data, target)
    if preserved_dub is not None and not dub.exists():
        dub.write_bytes(preserved_dub)


def changed_paths(checkout: pathlib.Path) -> list[str]:
    """Repo-relative paths git reports as added or modified, staged or not."""
    run(["git", "add", "-A", "data"], checkout)
    out = run(["git", "diff", "--cached", "--name-only", "--diff-filter=ACM", "--", "data"], checkout)
    return [line.strip() for line in out.splitlines() if line.strip()]


def purge(repo: str, paths: list[str], workers: int) -> None:
    session = requests.Session()

    def one(path: str) -> None:
        # Nothing useful comes back from this. The endpoint answers 200 with status "finished" and
        # both providers true for a path that does not exist in the repo at all — verified against
        # `data/does/not/exist.json` — so the response cannot distinguish a real purge from a
        # misspelled one. The outcome is checked by reading the CDN back instead; see `verify`.
        try:
            session.get(f"{PURGE_BASE}/{repo}@main/{path}", timeout=30)
        except requests.RequestException as error:  # noqa: BLE001 - a missed purge self-heals in 12h
            print(f"  purge request failed for {path}: {error}")

    with ThreadPoolExecutor(max_workers=workers) as pool:
        list(pool.map(one, paths))


def verify(repo: str, checkout: pathlib.Path, paths: list[str], sample: int) -> None:
    """Read a few purged files back off the CDN and compare them with what was pushed.

    This is the only check that means anything. A purge URL with the wrong prefix — `anime/...`
    where the CDN wants `data/anime/...` — reports success and purges nothing, and the symptom is
    devices quietly serving yesterday's episode lists for the next twelve hours with a clean log
    behind them. That happened; hence reading it back.
    """
    session = requests.Session()
    checked = mismatched = 0
    for path in paths[:sample]:
        local = checkout / path
        if not local.is_file():
            continue
        try:
            response = session.get(f"{CDN_BASE}/{repo}@main/{path}", timeout=30)
        except requests.RequestException as error:  # noqa: BLE001
            print(f"  could not read back {path}: {error}")
            continue
        checked += 1
        if response.content.strip() != local.read_bytes().strip():
            mismatched += 1
            print(f"  STALE on CDN: {path}")
    if mismatched:
        print(
            f"  {mismatched} of {checked} sampled files are still stale. Re-run the purge; if it "
            f"persists, the paths are wrong (they must include the data/ prefix)."
        )
    elif checked:
        print(f"  verified {checked} file(s) live on the CDN")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--repo", required=True, help="owner/name of the dataset repo")
    parser.add_argument(
        "--alias",
        action="append",
        default=[],
        metavar="OWNER/NAME",
        help=(
            "a former name of --repo that installs still request. jsDelivr keys its cache on the "
            "URL, so a repo reached through a GitHub rename redirect caches separately and stays "
            "stale unless it is purged under that spelling too. Repeatable."
        ),
    )
    parser.add_argument("--data", default="build/konoha/data", help="generated tree to publish")
    parser.add_argument("--checkout", required=True, help="local clone of the dataset repo")
    parser.add_argument("--message", default="Refresh metadata", help="commit message")
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--max-purge", type=int, default=2000, help="skip purging past this many files")
    parser.add_argument("--verify-sample", type=int, default=5, help="files to read back off the CDN")
    parser.add_argument("--dry-run", action="store_true", help="stage and report, do not push or purge")
    args = parser.parse_args(argv)

    data = pathlib.Path(args.data).resolve()
    checkout = pathlib.Path(args.checkout).resolve()
    if not data.is_dir():
        raise SystemExit(f"No generated tree at {data} — run `konoha_build.py emit` first.")
    if not (checkout / ".git").is_dir():
        raise SystemExit(f"{checkout} is not a git checkout of {args.repo}.")

    sync_tree(data, checkout)
    changed = changed_paths(checkout)
    if not changed:
        print("nothing changed")
        return 0
    print(f"{len(changed)} files changed")

    if args.dry_run:
        for path in changed[:20]:
            print(f"  {path}")
        print("(dry run — not pushed)")
        return 0

    run(["git", "commit", "-m", args.message], checkout)
    run(["git", "push", "origin", "main"], checkout)
    print("pushed")

    # A very large diff means a rebuild rather than a refresh. Purging it file by file would take
    # longer than jsDelivr's own twelve-hour expiry, so the roots are refreshed and the rest is
    # left to age out.
    if len(changed) > args.max_purge:
        print(f"{len(changed)} files is past --max-purge; purging roots only")
        targets = ALWAYS_PURGE
    else:
        targets = sorted(set(changed) | set(ALWAYS_PURGE))

    for repo in [args.repo, *args.alias]:
        print(f"purging {len(targets)} paths on {repo}")
        purge(repo, targets, args.workers)
    # jsDelivr needs a moment between accepting a purge and serving the new bytes.
    time.sleep(5)
    for repo in [args.repo, *args.alias]:
        verify(repo, checkout, targets, args.verify_sample)
    print("done")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
