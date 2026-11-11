# Konoha dataset repo

The static anime metadata tree the app reads for title details, episode lists and MAL→AniList
mapping. It replaces [AlokRepo/Konoha](https://github.com/AlokRepo/Konoha), a third-party mirror
that stopped syncing — its `sync-status.json` still reads `lastFinishedYear: 2010`, `stats.json`
was last written 2026-06-08 and `airing.json` was last checked 2026-05-19.

The layout is deliberately identical to Konoha's, so `KonohaClient` needed one constant changed and
no parser work. Anything that consumed Konoha consumes this.

The repo is `DATA_OWNER/media-index`, and `refresh.yml` here belongs in *that* repo's
`.github/workflows/`, not in this one's.

It was named `anilili-metadata` until 2026-09-21. The rename is safe because GitHub redirects the
old name and jsDelivr follows the redirect, so builds with the old name compiled into `DATA_BASE`
keep reading the same tree — but it leaves two standing rules. **Never re-create
`anilili-metadata` under `DATA_OWNER`**, which would drop the redirect and blank metadata on
every pre-0.1.80 install. And purge **both** spellings: jsDelivr's cache key is the URL, so the old
name is a separate set of cached objects. `refresh.yml` and `scripts/publish_dub_index.py` both
already loop over the pair.

## Setting it up

This directory is not the dataset repo. It holds the pieces that go *into* it.

1. Create a public GitHub repo for the data. jsDelivr only serves public repos.
2. Copy in:
   - `scripts/konoha_build.py` from the app repo → `scripts/konoha_build.py`
   - `refresh.yml` from here → `.github/workflows/refresh.yml`
3. Add a repository secret `TMDB_READ_TOKEN` (Settings → Secrets and variables → Actions). It is
   the same v4 read token the Gradle build uses.
4. Build the tree once locally and push it. About 45 minutes end to end (~25 min walking AniList,
   ~20 min on TMDB), and better watched than left to a cron:

   ```bash
   scripts/.venv/Scripts/python.exe scripts/konoha_build.py catalog
   scripts/.venv/Scripts/python.exe scripts/konoha_build.py episodes
   scripts/.venv/Scripts/python.exe scripts/konoha_build.py emit --out build/konoha/data
   scripts/.venv/Scripts/python.exe scripts/konoha_publish.py \
     --repo <owner>/<name> --checkout ../anilili-konoha --message "Initial dataset"
   ```

5. Point the app at it: `DATA_BASE` in `KonohaClient.kt`.
6. Refresh the bundled snapshot so a cold install has a current catalogue:

   ```bash
   scripts/.venv/Scripts/python.exe scripts/konoha_build.py assets --out build/konoha/data
   ```

After that the workflow keeps it current daily and only the app-side constant is manual.

## The part that is easy to get wrong

**jsDelivr does not notice a push.** It caches `@main` for up to twelve hours. A tree that is
committed but not purged serves yesterday's episode lists, and the app's own six-hour cache sits on
top of that — so a new episode can be a full day late with nothing in the logs to explain it. Both
`konoha_publish.py` and the workflow purge what changed; if you ever push by hand, purge by hand:

```bash
curl "https://purge.jsdelivr.net/gh/<owner>/<name>@main/data/index.json"
```

**Which token signs what.** `TMDB_READ_TOKEN` is a read-only v4 token and is the only secret this
repo needs. It is not the app signing key and not the Nostr key; neither of those belongs here.

## Cost and growth

Roughly 21,000 titles: one `index.json` and one `episodes.json` each, plus a MAL mapping shard per
title — about 60,000 small files. The daily job rewrites only what changed, but git still stores a
commit per run, so the repo grows steadily. If clones get slow, squash the history; nothing depends
on it, because the app reads files and never the log.

The daily run is cheap because it only re-asks TMDB about titles that can still change
(`--airing-only`) — a few hundred, not fifteen thousand. A generator change that alters output for
finished titles needs a manual `workflow_dispatch` with **full** ticked, which is the ~45 minute
path rather than the ~25 minute one. When only some finished titles change, pass them in **ids**
instead (comma-separated AniList ids): they are re-fetched on top of the airing pass. Anything
published into `data/` by hand for a finished title needs that too, because emit deletes an
`episodes.json` whose cached answer on the runner is empty.
