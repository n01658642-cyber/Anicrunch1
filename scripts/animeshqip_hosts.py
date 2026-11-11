#!/usr/bin/env python3
"""Enumerate animeshqip.live embed hosts across popular episodes.

For each sample series: find the serie via search, take the newest season's newest
episode, run the adblock-verify flow, resolve every server via /ajax/embed and
record what comes back (iframe host or native <video>).
"""
import http.cookiejar
import json
import re
import sys
import urllib.parse
import urllib.request

BASE = "https://www.animeshqip.live"
UA = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/126.0.6478.122 Mobile Safari/537.36")

cj = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cj))

def req(url, data=None, referer=None, xhr=False):
    r = urllib.request.Request(url, data=data)
    r.add_header("User-Agent", UA)
    if referer:
        r.add_header("Referer", referer)
    if xhr:
        r.add_header("X-Requested-With", "XMLHttpRequest")
    return opener.open(r, timeout=20)

def search(query):
    q = urllib.parse.quote(query)
    body = req(f"{BASE}/search/{q}?format=json&limit=5").read().decode()
    return json.loads(body).get("results", [])

def seasons(html, slug):
    out = {}
    for m in re.finditer(rf"/serie/{re.escape(slug)}/(\d+)-season/(\d+)-episode", html):
        out.setdefault(int(m.group(1)), set()).add(int(m.group(2)))
    return out

def servers(html):
    found = {}
    for m in re.finditer(r'<(?:a|button)\b[^>]*\bdata-embed\s*=\s*["\']?(\d+)["\']?[^>]*>', html, re.I):
        tag = m.group(0)
        player = re.search(r'data-player\s*=\s*["\']?(\d)', tag)
        found[int(m.group(1))] = int(player.group(1)) if player else 0
    return found

def verify(page_url, page_html):
    tok = re.search(r"adsbygoogle\.js\?token=([a-zA-Z0-9]+)", page_html)
    if tok:
        try:
            req(f"{BASE}/adsbygoogle.js?token={tok.group(1)}", referer=page_url).read()
        except Exception as e:
            print(f"  canary failed: {e}")
    body = req(f"{BASE}/ajax/adblock/status", data=b"status=clear",
               referer=page_url, xhr=True).read().decode()
    return json.loads(body).get("verified", False)

def resolve(page_url, source_id):
    body = req(f"{BASE}/ajax/embed", data=f"id={source_id}".encode(),
               referer=page_url, xhr=True).read().decode()
    iframe = re.search(r'<iframe\b[^>]*\bsrc\s*=\s*["\'](.*?)["\']', body, re.I | re.S)
    if iframe:
        return ("iframe", iframe.group(1))
    vids = re.findall(r'<source\b[^>]*\bsrc\s*=\s*["\'](.*?)["\']', body, re.I)
    if vids:
        return ("video", vids)
    return ("other", body[:120])

def main():
    queries = sys.argv[1:] or [
        "One Piece", "Solo Leveling", "Jujutsu Kaisen", "Demon Slayer",
        "Frieren", "Attack on Titan", "Naruto", "Dragon Ball",
        "My Hero Academia", "Chainsaw Man", "Spy x Family", "One Punch Man",
        "Hunter x Hunter", "Death Note", "Fullmetal Alchemist", "Bleach",
    ]
    host_table = {}
    for query in queries:
        results = [r for r in search(query) if r.get("type") == "serie"]
        if not results:
            print(f"{query}: no serie result")
            continue
        serie_url = results[0]["url"]
        slug = serie_url.rstrip("/").rsplit("/", 1)[-1]
        serie_html = req(serie_url, referer=BASE + "/").read().decode(errors="replace")
        seas = seasons(serie_html, slug)
        if not seas:
            print(f"{query}: no seasons for {slug}")
            continue
        season = max(seas)
        episode = max(seas[season])
        page_url = f"{BASE}/serie/{slug}/{season}-season/{episode}-episode"
        page_html = req(page_url, referer=serie_url).read().decode(errors="replace")
        srv = servers(page_html)
        ok = verify(page_url, page_html)
        print(f"{query} -> {slug} S{season}E{episode} verified={ok} servers={srv}")
        for sid, player in srv.items():
            try:
                kind, payload = resolve(page_url, sid)
            except Exception as e:
                kind, payload = "error", str(e)
            if kind == "iframe":
                host = urllib.parse.urlparse(payload if payload.startswith("http") else "https:" + payload).netloc
                host_table.setdefault(host, []).append((slug, sid, player, payload))
                print(f"    {sid} p{player} iframe {host}  {payload}")
            else:
                host_table.setdefault(f"<{kind}>", []).append((slug, sid, player, str(payload)[:100]))
                print(f"    {sid} p{player} {kind} {str(payload)[:100]}")
    print("\n== host tally ==")
    for host, rows in sorted(host_table.items(), key=lambda kv: -len(kv[1])):
        print(f"{len(rows):3d}  {host}")

if __name__ == "__main__":
    main()
