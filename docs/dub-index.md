# Dub metadata updates

The app loads bundled positives plus its last valid CDN overlay before drawing cards.
Once per launch it checks whether the overlay is older than 24 hours, refreshing in
background when needed. A successful refresh updates visible badges; offline, malformed,
empty and HTTP-error responses retain local positives. The overlay cannot remove a bundled badge.
Existing app versions need an update to gain this behavior.

## Refresh and publish

Run `scripts/.venv/Scripts/python.exe scripts/dub_index_backfill.py --top 2000 --redo`
to revisit existing observations (omit `--redo` for new titles only).
Then run `python scripts/publish_dub_index.py`. The publisher uses the DATA_OWNER
credential from gh without changing the active account, unions the asset, generated
index and existing remote positives, publishes only to the metadata repository, purges
jsDelivr and verifies the result. It preserves unrelated changes in the live daily workflow.

It writes to one repo (`media-index`) but purges and verifies **both** CDN paths, because that
repo was called `anilili-metadata` until 2026-09-21 and builds older than 0.1.80 still request
that name. Same bytes, reached through GitHub's rename redirect — but jsDelivr caches the two
URLs separately, so verifying only one leaves half the installed base on a stale index. See
AGENTS.md, "The metadata dataset".
No monthly automation has been installed; these commands can be run monthly.

## Kotlin crawler

Enable `ANILILI_CRAWL=1` and run the `DubIndexCrawler` test explicitly. It fetches AniList
Media records in batches of 50, supplying actual English, romaji, native names and MAL IDs.
If enrichment fails, it falls back to the catalogue seed and records `titleEnriched=false`.
`app/build/dub-index/dub-index-outcomes.json` records `dub`, `no_dub_observed`, or
`all_probes_failed`, with per-provider errors and a timestamp, including the title that
triggers the consecutive-failure stop. Total failures never enter the legacy Boolean
results file and remain eligible on the next run. The legacy file remains compatible
with the Python backfill. A no-dub observation is not proof a dub does not exist.

The CDN publisher and dataset workflow preserve independently maintained dub data when
rebuilding the episode tree. The app's source repository must never be pushed.
