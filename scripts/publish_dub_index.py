"""Publish only dub metadata to the dedicated dataset repo and verify jsDelivr.
Run after a backfill: python scripts/publish_dub_index.py
Credentials stay in gh's credential store. No app source is uploaded.
"""
import base64
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
# The one dataset repo: what the daily Action writes and what DATA_BASE in KonohaClient.kt reads.
REPO = 'DATA_OWNER/media-index'
# The name this repo had before 2026-09-21, still compiled into every build older than 0.1.80.
# Those installs reach the tree through GitHub's rename redirect, which jsDelivr follows -- but
# jsDelivr keys its cache on the URL, so this spelling is a separate set of cached objects and has
# to be purged and verified in its own right. Delete once builds older than 0.1.80 are gone.
LEGACY = 'DATA_OWNER/anilili-metadata'
GH = shutil.which('gh') or str(Path.home() / 'AppData/Local/Programs/GitHubCLI/bin/gh.exe')

def main():
    env = dict(os.environ)
    env['GH_TOKEN'] = subprocess.check_output([GH, 'auth', 'token', '--user', 'DATA_OWNER'], text=True).strip()
    def api(path, payload=None):
        args = [GH, 'api', path]
        if payload is not None:
            args += ['--method', 'PUT', '--input', '-']
        result = subprocess.run(args, input=json.dumps(payload) if payload else None,
                                capture_output=True, text=True, env=env)
        if result.returncode:
            if payload is None and '404' in result.stderr:
                return None
            raise RuntimeError(result.stderr)
        return json.loads(result.stdout)
    def read(path):
        row = api(f'repos/{REPO}/contents/{path}?ref=main')
        return row, base64.b64decode(row['content']) if row else None
    def write(path, content, previous, message):
        payload = dict(message=message, content=base64.b64encode(content).decode(), branch='main')
        if previous:
            payload['sha'] = previous['sha']
        api(f'repos/{REPO}/contents/{path}', payload)
    # Update the live workflow in place, retaining unrelated generator changes.
    path = '.github/workflows/refresh.yml'
    previous, raw = read(path)
    text = raw.decode()
    old = '        run: python scripts/konoha_build.py emit --out data'
    new = '''        run: |
          if [ -f data/dub-index.json ]; then cp data/dub-index.json /tmp/dub-index.json; fi
          python scripts/konoha_build.py emit --out data
          if [ -f /tmp/dub-index.json ]; then cp /tmp/dub-index.json data/dub-index.json; fi'''
    if old in text:
        text = text.replace(old, new)
    elif '/tmp/dub-index.json' not in text:
        raise RuntimeError('Unexpected workflow: review emit step before publishing')
    text = text.replace('for path in index.json', 'for path in dub-index.json index.json')
    if text.encode() != raw:
        write(path, text.encode(), previous, 'Preserve and purge independently refreshed dub metadata')
    path = 'data/dub-index.json'
    previous, raw = read(path)
    ids = set(json.loads((ROOT / 'app/src/main/assets/dub-index.json').read_text()))
    generated = ROOT / 'app/build/dub-index/dub-index.json'
    if generated.exists():
        ids.update(json.loads(generated.read_text()))
    if raw:
        ids.update(json.loads(raw))
    if not ids or any(type(i) is not int or i <= 0 for i in ids):
        raise ValueError('Invalid index')
    content = json.dumps(sorted(ids), separators=(',', ':')).encode()
    if content != raw:
        write(path, content, previous, 'Make verified dub additions available between app releases')
    def verify(repo, attempts):
        with urllib.request.urlopen(f'https://purge.jsdelivr.net/gh/{repo}@main/{path}', timeout=30) as response:
            response.read()
        for _ in range(attempts):
            try:
                with urllib.request.urlopen(f'https://cdn.jsdelivr.net/gh/{repo}@main/{path}', timeout=30) as response:
                    if set(json.load(response)) == ids:
                        print(f'Verified {len(ids)} dub ids on CDN for {repo}')
                        return
            except Exception:
                pass
            time.sleep(5)
        raise RuntimeError(f'Published but CDN verification failed for {repo}; retry purge and readback')

    # Both spellings have to be right or half the installed base is served a stale index. Same
    # bytes either way -- one repo, reached by two URLs -- but two independent CDN caches.
    verify(REPO, 6)
    verify(LEGACY, 6)

if __name__ == '__main__':
    main()
